package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.identity.application.LoginAttemptService;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

class AuthFlowTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    // ---- signup ----

    @Test
    void signupCreatesAccountOrganizationAndSessionCookie() throws Exception {
        ApiClient client = accounts.newClient();
        String email = TestAccounts.uniqueEmail();
        MvcResult result = client.post("/api/v1/auth/signup",
                        TestAccounts.signupBody(email, TestAccounts.PASSWORD, "Ada Lovelace", "Acme Properties"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.user.emailVerified").value(false))
                .andExpect(jsonPath("$.activeOrganization.name").value("Acme Properties"))
                .andExpect(jsonPath("$.activeOrganization.role").value("OWNER"))
                .andExpect(jsonPath("$.organizations.length()").value(1))
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        String session = setCookies.stream().filter(c -> c.startsWith("VF_SESSION=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly").contains("SameSite=Lax");
        assertThat(session).doesNotContain("Secure"); // local/test profile; prod sets Secure
        String csrf = setCookies.stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(csrf).doesNotContain("HttpOnly");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");
    }

    @Test
    void signupStoresBcryptHashWithDelegatingPrefix() throws Exception {
        Account account = accounts.signup("Hash Org");
        String hash = jdbc.queryForObject("select password_hash from app_user where id = ?::uuid", String.class,
                account.userId());
        assertThat(hash).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches(account.password(), hash)).isTrue();
    }

    @Test
    void signupWithSameEmailInDifferentCaseIsConflict() throws Exception {
        Account first = accounts.signup("First Org");
        ApiClient other = accounts.newClient();
        other.post("/api/v1/auth/signup", TestAccounts.signupBody(first.email().toUpperCase(),
                        TestAccounts.PASSWORD, "Someone", "Other Org"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already registered"));
    }

    @Test
    void signupValidationReturnsFieldErrors() throws Exception {
        accounts.newClient().post("/api/v1/auth/signup",
                        Map.of("email", "not-an-email", "password", "short", "fullName", "", "organizationName", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/validation"))
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='fullName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='organizationName')]").exists());
    }

    @Test
    void commonPasswordIsRejected() throws Exception {
        accounts.newClient().post("/api/v1/auth/signup",
                        TestAccounts.signupBody(TestAccounts.uniqueEmail(), "password1234", "A", "Org"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        accounts.newClient().post("/api/v1/auth/signup",
                        TestAccounts.signupBody(TestAccounts.uniqueEmail(), "QWERTYUIOP123", "A", "Org"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordEqualToEmailIsRejected() throws Exception {
        String email = "averyverylongemail@example.com";
        accounts.newClient().post("/api/v1/auth/signup", TestAccounts.signupBody(email, email, "A", "Org"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void passwordUpTo72BytesWorksAndLongerIsRejectedCleanly() throws Exception {
        String longPassword = "Ab1!".repeat(18); // 72 bytes: the bcrypt limit
        Account account = accounts.signup(TestAccounts.uniqueEmail(), longPassword, "Long Pass", "Long Org");
        accounts.login(account.email(), longPassword);
        // 73+ bytes: signup says 400 (not a 500), login says the generic 401.
        accounts.newClient().post("/api/v1/auth/signup",
                        TestAccounts.signupBody(TestAccounts.uniqueEmail(), longPassword + "x", "A", "Org"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        accounts.newClient().post("/api/v1/auth/login", Map.of("email", account.email(), "password", longPassword + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupEnqueuesVerificationEmailAndAuditEvents() throws Exception {
        Account account = accounts.signup("Audit Org");
        Map<String, Object> row = jdbc.queryForMap("""
                select kind, recipient_email, status, organization_id from notification
                where recipient_email = ?""", account.email());
        assertThat(row.get("kind")).isEqualTo("EMAIL_VERIFICATION");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("organization_id")).isNull();
        Integer tokenRows = jdbc.queryForObject("""
                select count(*) from user_token where user_id = ?::uuid and purpose = 'EMAIL_VERIFICATION'
                and expires_at > now() + interval '23 hours'""", Integer.class, account.userId());
        assertThat(tokenRows).isEqualTo(1);
        List<String> actions = jdbc.queryForList(
                "select action from audit_event where actor_user_id = ?::uuid order by created_at",
                String.class, account.userId());
        assertThat(actions).contains("organization.created", "user.signed_up");
    }

    // ---- login ----

    @Test
    void loginReturnsMeAndRotatesSessionId() throws Exception {
        Account account = accounts.signup("Login Org");
        ApiClient browser = account.client();
        String sessionBefore = browser.cookie(ApiClient.SESSION_COOKIE);
        String csrfBefore = browser.cookie(ApiClient.CSRF_COOKIE);

        // Logging in again while presenting an existing session must produce a different session id.
        browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(account.email()))
                .andExpect(jsonPath("$.activeOrganization.name").value("Login Org"));
        assertThat(browser.cookie(ApiClient.SESSION_COOKIE)).isNotEqualTo(sessionBefore);
        assertThat(browser.cookie(ApiClient.CSRF_COOKIE)).isNotEqualTo(csrfBefore);

        // The OLD session id is dead (fixation protection) ...
        ApiClient old = new ApiClient(mvc, json);
        old.setCookie(ApiClient.SESSION_COOKIE, sessionBefore);
        old.get("/api/v1/me").andExpect(status().isUnauthorized());
        // ... and the new one works.
        browser.get("/api/v1/me").andExpect(status().isOk());
    }

    @Test
    void sessionIsIndexedByUserIdPrincipalName() throws Exception {
        Account account = accounts.signup("Principal Org");
        Integer count = jdbc.queryForObject("select count(*) from spring_session where principal_name = ?",
                Integer.class, account.userId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void csrfTokenRotatedByLoginIsUsableForNextUnsafeRequest() throws Exception {
        Account account = accounts.signup("Csrf Org");
        ApiClient fresh = accounts.newClient();
        String tokenBeforeLogin = fresh.cookie(ApiClient.CSRF_COOKIE);
        fresh.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
        String tokenAfterLogin = fresh.cookie(ApiClient.CSRF_COOKIE);
        assertThat(tokenAfterLogin).isNotNull().isNotEqualTo(tokenBeforeLogin);
        // The next unsafe request carries the cookie value from the login response (ApiClient reads the jar).
        fresh.post("/api/v1/session/organization", Map.of("organizationId", account.organizationId()))
                .andExpect(status().isOk());
        // The pre-login token no longer matches the cookie, but the cookie is what the client always sends.
        ApiClient stale = fresh.copy();
        stale.setCookie(ApiClient.CSRF_COOKIE, tokenAfterLogin);
        stale.perform(org.springframework.http.HttpMethod.POST, "/api/v1/session/organization",
                        Map.of("organizationId", account.organizationId()), false)
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        Account account = accounts.signup("Enum Org");
        String wrongPassword = accounts.newClient()
                .post("/api/v1/auth/login", Map.of("email", account.email(), "password", "Wrong-Password-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = accounts.newClient()
                .post("/api/v1/auth/login", Map.of("email", "nobody-here@example.com", "password", "Wrong-Password-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andReturn().getResponse().getContentAsString();
        assertThat(strip(wrongPassword)).isEqualTo(strip(unknownEmail));
    }

    private String strip(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "");
    }

    @Test
    void fiveFailuresLockAccountAndLockExpires() throws Exception {
        Account account = accounts.signup("Lock Org");
        ApiClient browser = accounts.newClient();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", "Wrong-Password-123"))
                    .andExpect(status().isUnauthorized());
        }
        // Locked: even the correct password is refused, with the same generic body.
        browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
        assertThat(jdbc.queryForObject("select locked_until is not null from app_user where id = ?::uuid",
                Boolean.class, account.userId())).isTrue();

        clock.advance(LoginAttemptService.LOCK_DURATION.plusSeconds(5));
        browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select failed_login_attempts from app_user where id = ?::uuid",
                Integer.class, account.userId())).isZero();
    }

    @Test
    void successfulLoginResetsFailureCounter() throws Exception {
        Account account = accounts.signup("Reset Org");
        ApiClient browser = accounts.newClient();
        for (int i = 0; i < 4; i++) {
            browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", "Wrong-Password-123"))
                    .andExpect(status().isUnauthorized());
        }
        browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select failed_login_attempts from app_user where id = ?::uuid",
                Integer.class, account.userId())).isZero();
        // 4 more failures after the reset must not lock (would be 8 without the reset).
        ApiClient other = accounts.newClient();
        for (int i = 0; i < 4; i++) {
            other.post("/api/v1/auth/login", Map.of("email", account.email(), "password", "Wrong-Password-123"))
                    .andExpect(status().isUnauthorized());
        }
        other.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithoutCsrfTokenIsForbiddenAndWithTokenSucceeds() throws Exception {
        Account account = accounts.signup("Csrf Login Org");
        ApiClient noToken = new ApiClient(mvc, json);
        noToken.postWithoutCsrf("/api/v1/auth/login",
                        Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isForbidden());
        ApiClient withToken = accounts.newClient();
        withToken.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
    }

    @Test
    void signupWithoutCsrfTokenIsForbidden() throws Exception {
        new ApiClient(mvc, json).postWithoutCsrf("/api/v1/auth/signup",
                        TestAccounts.signupBody(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "A", "Org"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointSetsCookieAndReturns204() throws Exception {
        MvcResult result = new ApiClient(mvc, json).get("/api/v1/auth/csrf")
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
    }

    // ---- logout / me ----

    @Test
    void logoutInvalidatesSession() throws Exception {
        Account account = accounts.signup("Logout Org");
        ApiClient browser = account.client();
        ApiClient replay = browser.copy(); // keeps the old cookies
        browser.post("/api/v1/auth/logout", null).andExpect(status().isNoContent());
        assertThat(browser.cookie(ApiClient.SESSION_COOKIE)).isNull(); // cookie cleared
        replay.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutSessionIsStill204() throws Exception {
        accounts.newClient().post("/api/v1/auth/logout", null).andExpect(status().isNoContent());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        new ApiClient(mvc, json).get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void meListsOrganizationsSortedByName() throws Exception {
        Account account = accounts.signup("Zeta Org");
        ApiClient browser = account.client();
        addMembership(account.userId(), "alpha Org", "ADMIN");
        addMembership(account.userId(), "Mike Org", "VIEWER");
        browser.get("/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations[0].name").value("alpha Org"))
                .andExpect(jsonPath("$.organizations[1].name").value("Mike Org"))
                .andExpect(jsonPath("$.organizations[2].name").value("Zeta Org"))
                .andExpect(jsonPath("$.activeOrganization.name").value("Zeta Org"));
    }

    // ---- session/organization ----

    @Test
    void switchToOwnSecondOrganizationWorks() throws Exception {
        Account account = accounts.signup("First Org");
        String second = addMembership(account.userId(), "Second Org", "MEMBER");
        ApiClient browser = account.client();
        browser.post("/api/v1/session/organization", Map.of("organizationId", second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeOrganization.name").value("Second Org"))
                .andExpect(jsonPath("$.activeOrganization.role").value("MEMBER"));
        browser.get("/api/v1/organization").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Second Org"));
        assertThat(jdbc.queryForObject("select last_active_organization_id::text from app_user where id = ?::uuid",
                String.class, account.userId())).isEqualTo(second);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_event where action = 'session.organization_switched' and actor_user_id = ?::uuid",
                Integer.class, account.userId())).isEqualTo(1);
        // A new login restores the last active organization.
        ApiClient later = accounts.login(account.email(), account.password());
        later.get("/api/v1/me").andExpect(jsonPath("$.activeOrganization.name").value("Second Org"));
    }

    @Test
    void switchToSomeoneElsesOrganizationIs404() throws Exception {
        Account me = accounts.signup("My Org");
        Account other = accounts.signup("Other Org");
        me.client().post("/api/v1/session/organization", Map.of("organizationId", other.organizationId()))
                .andExpect(status().isNotFound());
        me.client().post("/api/v1/session/organization",
                        Map.of("organizationId", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        me.client().get("/api/v1/organization").andExpect(jsonPath("$.name").value("My Org"));
    }

    @Test
    void membershipRemovedMidSessionClearsActiveOrganization() throws Exception {
        Account account = accounts.signup("Vanishing Org");
        ApiClient browser = account.client();
        browser.get("/api/v1/organization").andExpect(status().isOk());

        jdbc.update("delete from membership where user_id = ?::uuid", account.userId());

        browser.get("/api/v1/organization")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("No active organization"));
        browser.get("/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeOrganization").value((Object) null))
                .andExpect(jsonPath("$.organizations.length()").value(0));
    }

    /** Creates another organization and a membership for the user directly in the database. */
    private String addMembership(String userId, String orgName, String role) {
        String orgId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                insert into organization (id, name, created_at, updated_at) values (?::uuid, ?, now(), now())""",
                orgId, orgName);
        jdbc.update("""
                insert into membership (id, organization_id, user_id, role, created_at, updated_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?, now(), now())""", orgId, userId, role);
        return orgId;
    }
}
