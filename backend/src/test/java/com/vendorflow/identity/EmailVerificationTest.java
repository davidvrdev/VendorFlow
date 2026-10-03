package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.EmailTokens;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class EmailVerificationTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    private String verificationToken(Account account) {
        return EmailTokens.token(dispatcher, emailSender, account.email(), NotificationKind.EMAIL_VERIFICATION);
    }

    private boolean verified(Account account) throws Exception {
        String body = account.client().get("/api/v1/me").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return json.readTree(body).get("user").get("emailVerified").asBoolean();
    }

    @Test
    void validTokenVerifiesTheEmailWithoutNeedingASession() throws Exception {
        Account account = accounts.signup("Verify Org");
        assertThat(verified(account)).isFalse();
        String token = verificationToken(account);

        accounts.newClient().post("/api/v1/auth/verify-email", Map.of("token", token))
                .andExpect(status().isNoContent());

        assertThat(verified(account)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'user.email_verified' "
                + "and actor_user_id = ?::uuid and organization_id is null", Integer.class, account.userId()))
                .isEqualTo(1);
    }

    @Test
    void unknownExpiredAndUsedTokensAllGetTheSameBadRequest() throws Exception {
        Account account = accounts.signup("Bad Link Org");
        String token = verificationToken(account);
        ApiClient anonymous = accounts.newClient();

        String unknown = anonymous.post("/api/v1/auth/verify-email", Map.of("token", "no-such-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid or expired link"))
                .andReturn().getResponse().getContentAsString();

        anonymous.post("/api/v1/auth/verify-email", Map.of("token", token)).andExpect(status().isNoContent());
        String used = anonymous.post("/api/v1/auth/verify-email", Map.of("token", token))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        Account other = accounts.signup("Expired Org");
        String otherToken = verificationToken(other);
        clock.advance(Duration.ofHours(25));
        String expired = anonymous.post("/api/v1/auth/verify-email", Map.of("token", otherToken))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(verified(other)).isFalse();
        // Same title and detail for every failure: the response is not an oracle.
        assertThat(json.readTree(used).get("title")).isEqualTo(json.readTree(unknown).get("title"));
        assertThat(json.readTree(expired).get("detail")).isEqualTo(json.readTree(unknown).get("detail"));
        assertThat(json.readTree(used).get("detail")).isEqualTo(json.readTree(unknown).get("detail"));
    }

    @Test
    void blankTokenIsAValidationError() throws Exception {
        accounts.newClient().post("/api/v1/auth/verify-email", Map.of("token", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("token"));
    }

    @Test
    void alreadyVerifiedUserWithAValidTokenStillGets204() throws Exception {
        Account account = accounts.signup("Idempotent Org");
        String token = verificationToken(account);
        accounts.verified(account);

        accounts.newClient().post("/api/v1/auth/verify-email", Map.of("token", token))
                .andExpect(status().isNoContent());
        assertThat(verified(account)).isTrue();
    }

    @Test
    void aPasswordResetTokenCannotVerifyAnEmail() throws Exception {
        Account account = accounts.signup("Purpose Org");
        ApiClient anonymous = accounts.newClient();
        anonymous.post("/api/v1/auth/password-reset/request", Map.of("email", account.email()))
                .andExpect(status().isAccepted());
        String resetToken = EmailTokens.token(dispatcher, emailSender, account.email(), NotificationKind.PASSWORD_RESET);

        anonymous.post("/api/v1/auth/verify-email", Map.of("token", resetToken))
                .andExpect(status().isBadRequest());
        assertThat(verified(account)).isFalse();
    }

    @Test
    void resendRequiresASession() throws Exception {
        accounts.newClient().post("/api/v1/auth/resend-verification", null).andExpect(status().isUnauthorized());
    }

    @Test
    void resendIssuesANewTokenAndInvalidatesTheOldOne() throws Exception {
        Account account = accounts.signup("Resend Org");
        String oldToken = verificationToken(account);

        account.client().post("/api/v1/auth/resend-verification", null).andExpect(status().isNoContent());
        String newToken = verificationToken(account);

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(EmailTokens.count(dispatcher, emailSender, account.email(), NotificationKind.EMAIL_VERIFICATION))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from user_token where user_id = ?::uuid and purpose = 'EMAIL_VERIFICATION'
                and used_at is null and expires_at > now() + interval '23 hours'""", Integer.class,
                account.userId())).isEqualTo(1);

        ApiClient anonymous = accounts.newClient();
        anonymous.post("/api/v1/auth/verify-email", Map.of("token", oldToken)).andExpect(status().isBadRequest());
        anonymous.post("/api/v1/auth/verify-email", Map.of("token", newToken)).andExpect(status().isNoContent());
        assertThat(verified(account)).isTrue();
    }

    @Test
    void resendForAVerifiedUserIsANoOp() throws Exception {
        Account account = accounts.verified(accounts.signup("Verified Resend Org"));
        EmailTokens.count(dispatcher, emailSender, account.email(), NotificationKind.EMAIL_VERIFICATION); // drain signup mail
        Integer before = jdbc.queryForObject("select count(*) from notification where recipient_email = ?",
                Integer.class, account.email());

        account.client().post("/api/v1/auth/resend-verification", null).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_email = ?",
                Integer.class, account.email())).isEqualTo(before);
    }

    @Test
    void resendIsRateLimitedToThreePerMinute() throws Exception {
        Account account = accounts.signup("Rate Org");
        for (int i = 0; i < 3; i++) {
            account.client().post("/api/v1/auth/resend-verification", null).andExpect(status().isNoContent());
        }
        account.client().post("/api/v1/auth/resend-verification", null).andExpect(status().isTooManyRequests());
    }
}
