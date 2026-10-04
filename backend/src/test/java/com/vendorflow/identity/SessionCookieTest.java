package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * ASVS V3 (session management) review, run with the production value of {@code app.security.session-cookie-secure}
 * (the test profile turns it off for plain-http convenience; every other test keeps that).
 * Complements AuthFlowTest (session id rotation on re-login, logout) and SessionLifetimeTest (absolute lifetime).
 */
@TestPropertySource(properties = "app.security.session-cookie-secure=true")
class SessionCookieTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    private static String setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith(name + "="))
                .findFirst().orElseThrow(() -> new AssertionError("no Set-Cookie for " + name));
    }

    @Test
    void sessionCookieIsSecureHttpOnlySameSiteLaxHostOnlyAndRootPath() throws Exception {
        ApiClient client = accounts.newClient();
        MockHttpServletResponse response = client.post("/api/v1/auth/signup", TestAccounts.signupBody(
                TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Cookie Tester", "Cookie Org"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String session = setCookie(response, "VF_SESSION");
        assertThat(session).contains("; Secure").contains("; HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(session).as("host-only cookie: no Domain attribute so subdomains never receive it")
                .doesNotContain("Domain=");
        assertThat(session).as("session cookie (browser session), not persistent").doesNotContain("Max-Age=")
                .doesNotContain("Expires=");
    }

    @Test
    void logoutExpiresTheCookieWithTheSameAttributesAndDeletesTheServerSideSession() throws Exception {
        Account account = accounts.signup("Logout Rows Org");
        assertThat(sessionRows(account)).isEqualTo(1);
        MockHttpServletResponse response = account.client().post("/api/v1/auth/logout", null)
                .andExpect(status().isNoContent()).andReturn().getResponse();
        String expired = setCookie(response, "VF_SESSION");
        assertThat(expired).contains("Max-Age=0").contains("; Secure").contains("; HttpOnly");
        assertThat(sessionRows(account)).as("row removed from spring_session, not just the cookie").isZero();
    }

    @Test
    void csrfCookieIsReadableByScriptAndSecureOnHttps() throws Exception {
        String overTls = mvc.perform(get("/api/v1/auth/csrf").secure(true)).andReturn().getResponse()
                .getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        // SameSite is asserted over real Tomcat (SecurityHeadersRealServerTest): MockHttpServletResponse does not render it.
        assertThat(overTls).contains("; Secure").doesNotContain("HttpOnly");
        String overHttp = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse()
                .getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(overHttp).doesNotContain("; Secure");
    }

    @Test
    void aSessionIdPlantedBeforeLoginNeverBecomesTheAuthenticatedSession() throws Exception {
        // Classic fixation: the attacker makes the victim's browser carry a session id the attacker knows.
        Account victim = accounts.signup("Fixation Org");
        String planted = "planted-by-attacker-0123456789";
        ApiClient browser = accounts.newClient();
        browser.setCookie(ApiClient.SESSION_COOKIE, planted);
        browser.post("/api/v1/auth/login", Map.of("email", victim.email(), "password", victim.password()))
                .andExpect(status().isOk());
        assertThat(browser.cookie(ApiClient.SESSION_COOKIE)).isNotNull().isNotEqualTo(planted);
        ApiClient attacker = new ApiClient(mvc, json);
        attacker.setCookie(ApiClient.SESSION_COOKIE, planted);
        attacker.get("/api/v1/me").andExpect(status().isUnauthorized());
        // Same with the base64 form Spring Session actually puts in the cookie.
        attacker.setCookie(ApiClient.SESSION_COOKIE, java.util.Base64.getEncoder().encodeToString(planted.getBytes()));
        attacker.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void loginInvalidatesTheServerSideRowOfTheSessionItReplaces() throws Exception {
        Account account = accounts.signup("Rotation Rows Org");
        long before = sessionRows(account);
        account.client().post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
        assertThat(before).isEqualTo(1);
        assertThat(sessionRows(account)).as("the old session row is gone, one new one exists").isEqualTo(1);
    }

    @Test
    void idleTimeoutIsEightHoursSliding() throws Exception {
        Account account = accounts.signup("Idle Org");
        Long seconds = jdbc.queryForObject(
                "select max_inactive_interval from spring_session where principal_name = ?", Long.class,
                account.userId());
        assertThat(seconds).isEqualTo(8 * 3600L);
    }

    @Test
    void productionDefaultsKeepTheSessionCookieSecure() throws Exception {
        // The global default and the prod profile both say true (fail safe: forgetting the property means Secure).
        // Local, e2e and test profiles switch it off explicitly for plain http. Read the files, not a running context.
        List<PropertySource<?>> main = new YamlPropertySourceLoader().load("main",
                new ClassPathResource("application.yml"));
        assertThat(main.stream().map(p -> p.getProperty("app.security.session-cookie-secure")).filter(v -> v != null))
                .containsExactly(true);
        List<PropertySource<?>> prod = new YamlPropertySourceLoader().load("prod",
                new ClassPathResource("application-prod.yml"));
        assertThat(prod.stream().map(p -> p.getProperty("app.security.session-cookie-secure")).filter(v -> v != null))
                .containsExactly(true);
        for (String profile : List.of("local", "e2e")) {
            List<PropertySource<?>> other = new YamlPropertySourceLoader().load(profile,
                    new ClassPathResource("application-" + profile + ".yml"));
            assertThat(other.stream().map(p -> p.getProperty("app.security.session-cookie-secure"))
                    .filter(v -> v != null)).containsExactly(false);
        }
    }

    private long sessionRows(Account account) {
        Long count = jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
                account.userId());
        return count == null ? 0 : count;
    }
}
