package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.vendorflow.shared.security.SecurityConfig;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * ASVS V14.4 / V8.3: security headers on API responses, for success AND error paths (401, 403, 404, 429, 400).
 * HSTS and the absence of Server / X-Powered-By need a real server: see SecurityHeadersRealServerTest.
 */
class SecurityHeadersTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    private static void assertApiHeaders(String what, MockHttpServletResponse r) {
        assertThat(r.getHeader("X-Content-Type-Options")).as(what).isEqualTo("nosniff");
        assertThat(r.getHeader("X-Frame-Options")).as(what).isEqualTo("DENY");
        assertThat(r.getHeader("Referrer-Policy")).as(what).isEqualTo("no-referrer");
        assertThat(r.getHeader("Content-Security-Policy")).as(what)
                .isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(r.getHeader("Permissions-Policy")).as(what)
                .contains("camera=()", "microphone=()", "geolocation=()", "payment=()");
        assertThat(r.getHeader("Cache-Control")).as(what).contains("no-store");
        assertThat(r.getHeader("Server")).as(what).isNull();
        assertThat(r.getHeader("X-Powered-By")).as(what).isNull();
    }

    @Test
    void authenticatedApiResponsesCarryAllSecurityHeaders() throws Exception {
        TestAccounts.Account owner = accounts.signup("Headers Org");
        assertApiHeaders("GET /me", owner.client().get("/api/v1/me").andReturn().getResponse());
        assertApiHeaders("GET /vendors", owner.client().get("/api/v1/vendors").andReturn().getResponse());
        assertApiHeaders("GET /dashboard", owner.client().get("/api/v1/dashboard/summary").andReturn().getResponse());
        assertApiHeaders("404 foreign id", owner.client().get("/api/v1/vendors/" + java.util.UUID.randomUUID())
                .andReturn().getResponse());
        assertApiHeaders("400 validation", owner.client().post("/api/v1/vendors", Map.of()).andReturn().getResponse());
    }

    @Test
    void anonymousPublicAndErrorResponsesCarryTheHeadersToo() throws Exception {
        ApiClient anonymous = accounts.newClient();
        assertApiHeaders("csrf", anonymous.get("/api/v1/auth/csrf").andReturn().getResponse());
        assertApiHeaders("401", anonymous.get("/api/v1/me").andReturn().getResponse());
        assertApiHeaders("403 csrf", anonymous.postWithoutCsrf("/api/v1/auth/login",
                Map.of("email", "a@example.com", "password", "x")).andReturn().getResponse());
        assertApiHeaders("401 login", anonymous.post("/api/v1/auth/login",
                Map.of("email", "a@example.com", "password", "Wrong-Password-123")).andReturn().getResponse());
        assertApiHeaders("404 unknown path", mvc.perform(get("/api/v1/nope")).andReturn().getResponse());
    }

    @Test
    void rateLimitResponsesCarryNoSniffAndNoStoreEvenThoughTheyShortCircuitBeforeTheSecurityChain() throws Exception {
        ApiClient attacker = new ApiClient(mvc, json).remoteAddr("203.0.113.99").primeCsrf();
        MockHttpServletResponse last = null;
        for (int i = 0; i < 11; i++) {
            last = attacker.post("/api/v1/auth/login", Map.of("email", "x@example.com", "password", "Wrong-Password-123"))
                    .andReturn().getResponse();
        }
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(last.getHeader("Cache-Control")).contains("no-store");
        assertThat(last.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void hstsIsSentOnlyOnSecureRequests() throws Exception {
        assertThat(mvc.perform(get("/api/v1/auth/csrf").secure(true)).andReturn().getResponse()
                .getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains");
        assertThat(mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse()
                .getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void policyConstantsAreStrict() {
        assertThat(SecurityConfig.API_CSP).startsWith("default-src 'none'").contains("frame-ancestors 'none'");
        assertThat(SecurityConfig.API_CSP).doesNotContain("unsafe");
    }
}
