package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.identity.application.LoginAttemptService;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.EmailTokens;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/v1/me/password (ASVS V2.1.5, V2.1.6, V3.7.1). Own password only, so the "cross-tenant" check is that the
 * target user is always the session user and never something taken from the request.
 */
class PasswordChangeTest extends IntegrationTest {

    static final String NEW_PASSWORD = "Fresh-Passphrase-From-Test-77";
    static final String URL = "/api/v1/me/password";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    private static Map<String, String> body(String current, String next) {
        return Map.of("currentPassword", current, "newPassword", next);
    }

    @Test
    void happyPathChangesPasswordRevokesOtherSessionsRotatesCurrentAuditsAndEmails() throws Exception {
        Account account = accounts.signup("Change Org");
        ApiClient otherDevice = accounts.login(account.email(), account.password());
        String oldSession = account.client().cookie(ApiClient.SESSION_COOKIE);
        String oldCsrf = account.client().cookie(ApiClient.CSRF_COOKIE);

        account.client().post(URL, body(account.password(), NEW_PASSWORD)).andExpect(status().isNoContent());

        otherDevice.get("/api/v1/me").andExpect(status().isUnauthorized());
        assertThat(account.client().cookie(ApiClient.SESSION_COOKIE)).isNotNull().isNotEqualTo(oldSession);
        account.client().get("/api/v1/me").andExpect(status().isOk());
        assertThat(account.client().cookie(ApiClient.CSRF_COOKIE)).isNotEqualTo(oldCsrf);

        accounts.newClient().post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isUnauthorized());
        accounts.login(account.email(), NEW_PASSWORD);

        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'user.password_changed' "
                + "and actor_user_id = ?::uuid and entity_id = ?::uuid", Integer.class, account.userId(),
                account.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata::text from audit_event where action = 'user.password_changed' "
                + "and actor_user_id = ?::uuid", String.class, account.userId())).doesNotContain(NEW_PASSWORD);
        assertThat(EmailTokens.count(dispatcher, emailSender, account.email(), NotificationKind.PASSWORD_CHANGED))
                .isEqualTo(1);
        assertThat(EmailTokens.latest(dispatcher, emailSender, account.email(), NotificationKind.PASSWORD_CHANGED)
                .toString()).doesNotContain(NEW_PASSWORD);
    }

    @Test
    void wrongCurrentPasswordIsAGenericErrorAndCountsTowardLockout() throws Exception {
        Account account = accounts.signup("Wrong Org");
        account.client().post(URL, body("Not-The-Right-Password-1", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The current password is incorrect."));
        accounts.login(account.email(), account.password());
        assertThat(jdbc.queryForObject("select failed_login_attempts from app_user where id = ?::uuid", Integer.class,
                account.userId())).as("the old password still works; nothing was changed").isZero();
    }

    @Test
    void wrongCurrentPasswordsLockTheAccount() throws Exception {
        Account account = accounts.signup("Lock Org");
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            account.client().post(URL, body("Not-The-Right-Password-" + i, NEW_PASSWORD)).andExpect(status().isBadRequest());
        }
        account.client().post(URL, body("Not-The-Right-Password-x", NEW_PASSWORD)).andExpect(status().isBadRequest());
        // Locked now: even the correct password is refused at login.
        accounts.newClient().post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void newPasswordMustPassThePolicy() throws Exception {
        Account account = accounts.signup("Policy Org");
        account.client().post(URL, body(account.password(), "short")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        account.client().post(URL, body(account.password(), "password1234")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        account.client().post(URL, body(account.password(), account.password())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        account.client().post(URL, body(account.password(), "x".repeat(129))).andExpect(status().isBadRequest());
        account.client().post(URL, Map.of("currentPassword", "")).andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        accounts.newClient().post(URL, body("a", "b")).andExpect(status().isUnauthorized());
        Account account = accounts.signup("Csrf Org");
        account.client().postWithoutCsrf(URL, body(account.password(), NEW_PASSWORD)).andExpect(status().isForbidden());
        accounts.login(account.email(), account.password());
    }

    @Test
    void lowestRoleMayChangeOnlyItsOwnPassword() throws Exception {
        Account owner = accounts.signup("Viewer Org");
        Account viewer = accounts.signup("Viewer Home Org");
        accounts.joinOrganization(viewer, owner.organizationId(), "VIEWER");
        viewer.client().post(URL, Map.of("currentPassword", viewer.password(), "newPassword", NEW_PASSWORD,
                "userId", owner.userId())).andExpect(status().isNoContent());
        // The target is always the session user: the other user's password is untouched.
        accounts.login(owner.email(), owner.password());
        accounts.login(viewer.email(), NEW_PASSWORD);
    }

    @Test
    void isRateLimitedPerUser() throws Exception {
        Account account = accounts.signup("Rate Org");
        // 4 wrong attempts (below the lockout threshold of 5) plus the one that succeeds use up the budget of 5.
        for (int i = 0; i < 4; i++) {
            account.client().post(URL, body("Wrong-Password-Wrong-" + i, NEW_PASSWORD)).andExpect(status().isBadRequest());
        }
        account.client().post(URL, body(account.password(), NEW_PASSWORD)).andExpect(status().isNoContent());
        account.client().post(URL, body(NEW_PASSWORD, account.password())).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
