package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.EmailMessage;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

class PasswordResetTest extends IntegrationTest {

    static final String NEW_PASSWORD = "Brand-New-Password-42";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    private String requestReset(Account account) throws Exception {
        accounts.newClient().post("/api/v1/auth/password-reset/request", Map.of("email", account.email()))
                .andExpect(status().isAccepted());
        return EmailTokens.token(dispatcher, emailSender, account.email(), NotificationKind.PASSWORD_RESET);
    }

    private void confirm(String token, String password, int expectedStatus) throws Exception {
        accounts.newClient().post("/api/v1/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", password)).andExpect(status().is(expectedStatus));
    }

    // ---- request ----

    @Test
    void requestAnswersTheSameForKnownAndUnknownEmails() throws Exception {
        Account account = accounts.signup("Reset Org");
        String unknown = "nobody-" + TestAccounts.uniqueEmail();

        MvcResult known = accounts.newClient().post("/api/v1/auth/password-reset/request",
                Map.of("email", account.email())).andExpect(status().isAccepted()).andReturn();
        MvcResult unknownResult = accounts.newClient().post("/api/v1/auth/password-reset/request",
                Map.of("email", unknown)).andExpect(status().isAccepted()).andReturn();

        assertThat(known.getResponse().getContentAsString()).isEqualTo(unknownResult.getResponse().getContentAsString())
                .isEmpty();
        assertThat(known.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(
                unknownResult.getResponse().getHeaderNames());
        assertThat(EmailTokens.count(dispatcher, emailSender, account.email(), NotificationKind.PASSWORD_RESET))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_email = ?", Integer.class,
                unknown)).isZero();
    }

    @Test
    void emailMatchingIsCaseInsensitiveAndTheLinkPointsAtTheResetPage() throws Exception {
        Account account = accounts.signup("Case Org");
        accounts.newClient().post("/api/v1/auth/password-reset/request",
                Map.of("email", account.email().toUpperCase())).andExpect(status().isAccepted());

        EmailMessage mail = EmailTokens.latest(dispatcher, emailSender, account.email(), NotificationKind.PASSWORD_RESET);
        assertThat(mail.textBody()).contains("http://localhost:3000/reset-password#token=");
        assertThat(mail.htmlBody()).contains("http://localhost:3000/reset-password#token=");
    }

    @Test
    void malformedEmailIsAValidationError() throws Exception {
        accounts.newClient().post("/api/v1/auth/password-reset/request", Map.of("email", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void aNewRequestInvalidatesThePreviousLink() throws Exception {
        Account account = accounts.signup("Two Requests Org");
        String first = requestReset(account);
        String second = requestReset(account);

        assertThat(second).isNotEqualTo(first);
        confirm(first, NEW_PASSWORD, 400);
        confirm(second, NEW_PASSWORD, 204);
    }

    // ---- confirm ----

    @Test
    void confirmSetsThePasswordAndDoesNotLogIn() throws Exception {
        Account account = accounts.signup("Confirm Org");
        String token = requestReset(account);

        ApiClient anonymous = accounts.newClient();
        MvcResult result = anonymous.post("/api/v1/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", NEW_PASSWORD)).andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.contains("VF_SESSION="));
        anonymous.get("/api/v1/me").andExpect(status().isUnauthorized());
        accounts.newClient().post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isUnauthorized());
        accounts.login(account.email(), NEW_PASSWORD).get("/api/v1/me").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'user.password_reset' "
                + "and actor_user_id = ?::uuid and organization_id is null", Integer.class, account.userId()))
                .isEqualTo(1);
    }

    @Test
    void confirmEndsEverySessionOfTheUser() throws Exception {
        Account account = accounts.signup("Sessions Org");
        ApiClient secondBrowser = accounts.login(account.email(), account.password());
        ApiClient bystander = accounts.signup("Someone Else Org").client();
        account.client().get("/api/v1/me").andExpect(status().isOk());
        secondBrowser.get("/api/v1/me").andExpect(status().isOk());

        confirm(requestReset(account), NEW_PASSWORD, 204);

        account.client().get("/api/v1/me").andExpect(status().isUnauthorized());
        secondBrowser.get("/api/v1/me").andExpect(status().isUnauthorized());
        // Other users' sessions are untouched.
        bystander.get("/api/v1/me").andExpect(status().isOk());
    }

    @Test
    void confirmClearsTheLockout() throws Exception {
        Account account = accounts.signup("Locked Org");
        for (int i = 0; i < 5; i++) {
            accounts.newClient().post("/api/v1/auth/login", Map.of("email", account.email(), "password", "wrong-password-1"))
                    .andExpect(status().isUnauthorized());
        }
        // Locked: even the right password is refused.
        accounts.newClient().post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isUnauthorized());

        confirm(requestReset(account), NEW_PASSWORD, 204);

        assertThat(jdbc.queryForObject("select failed_login_attempts from app_user where id = ?::uuid",
                Integer.class, account.userId())).isZero();
        accounts.login(account.email(), NEW_PASSWORD);
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        Account account = accounts.signup("Single Use Org");
        String token = requestReset(account);
        confirm(token, NEW_PASSWORD, 204);
        confirm(token, "Another-Password-77", 400);
        accounts.login(account.email(), NEW_PASSWORD);
    }

    @Test
    void expiredTokenIsRejectedWithTheGenericProblem() throws Exception {
        Account account = accounts.signup("Expired Reset Org");
        String token = requestReset(account);
        clock.advance(Duration.ofMinutes(31));

        accounts.newClient().post("/api/v1/auth/password-reset/confirm",
                        Map.of("token", token, "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid or expired link"));
        accounts.login(account.email(), account.password()); // old password still works
    }

    @Test
    void tokenStillValidJustBeforeThirtyMinutes() throws Exception {
        Account account = accounts.signup("Almost Expired Org");
        String token = requestReset(account);
        clock.advance(Duration.ofMinutes(29));
        confirm(token, NEW_PASSWORD, 204);
    }

    @Test
    void unknownTokenGetsTheGenericProblem() throws Exception {
        accounts.newClient().post("/api/v1/auth/password-reset/confirm",
                        Map.of("token", "does-not-exist", "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid or expired link"));
    }

    @Test
    void weakPasswordIsRejectedOnNewPasswordAndTheLinkSurvives() throws Exception {
        Account account = accounts.signup("Weak Org");
        String token = requestReset(account);

        accounts.newClient().post("/api/v1/auth/password-reset/confirm", Map.of("token", token, "newPassword", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        accounts.newClient().post("/api/v1/auth/password-reset/confirm",
                        Map.of("token", token, "newPassword", "password1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        accounts.newClient().post("/api/v1/auth/password-reset/confirm",
                        Map.of("token", token, "newPassword", account.email()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));

        // Nothing changed and the same link still works.
        accounts.login(account.email(), account.password());
        confirm(token, NEW_PASSWORD, 204);
    }
}
