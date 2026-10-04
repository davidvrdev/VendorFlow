package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deny-by-default regression net (ASVS V4.1): enumerates EVERY mapped endpoint from Spring MVC and proves that
 * each one either is on an explicit, reviewed public allowlist or answers 401 to an anonymous caller, and that every
 * state-changing endpoint rejects a request without a CSRF token with 403. A new controller method that someone
 * forgot to protect (or a permitAll that is too wide) fails this test; it must then be added to the allowlists below
 * on purpose, in a reviewed change.
 */
class EndpointInventoryTest extends IntegrationTest {

    /** Endpoints reachable without a session. Key "METHOD pattern". Keep in sync with SecurityConfig. */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/auth/csrf",
            "POST /api/v1/auth/signup",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/verify-email",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/password-reset/confirm",
            "POST /api/v1/invitations/lookup",
            "POST /api/v1/invitations/accept",
            "POST /api/v1/webhooks/stripe",
            // Vendor portal (ADR-0011): the token in the X-Portal-Token header is the credential; PortalApiTest covers behavior.
            "GET /api/v1/portal/link",
            "POST /api/v1/portal/link/documents");

    /**
     * Mutating endpoints exempt from CSRF: the Stripe webhook has no browser session and is authenticated by its
     * signature over the raw body (StripeWebhookTest proves a bad/missing signature is rejected).
     * The portal upload uses no cookie or session at all (the token in the X-Portal-Token header is the credential), so there is no
     * ambient credential for a cross-site request to ride on (PortalApiTest proves it ignores a session cookie).
     */
    private static final Set<String> CSRF_EXEMPT = Set.of("POST /api/v1/webhooks/stripe",
            "POST /api/v1/portal/link/documents");

    private static final Set<RequestMethod> SAFE = Set.of(RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.OPTIONS, RequestMethod.TRACE);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    /** "METHOD pattern" for every mapped endpoint, sorted. */
    private Map<String, RequestMethod> endpoints() {
        Map<String, RequestMethod> result = new TreeMap<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            if (patterns.equals(Set.of("/error"))) {
                continue; // Boot's BasicErrorController (any method, error dispatch only); see unmappedPathsAndActuator...
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            assertThat(methods).as("%s must declare its HTTP method explicitly", info).isNotEmpty();
            for (RequestMethod method : methods) {
                for (String pattern : patterns) {
                    result.put(method + " " + pattern, method);
                }
            }
        }
        return result;
    }

    private static String concretePath(String pattern) {
        return pattern.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
    }

    private static String pathOf(String key) {
        return key.substring(key.indexOf(' ') + 1);
    }

    @Test
    void inventoryIsNotEmptyAndAllowlistsHaveNoStaleEntries() {
        Map<String, RequestMethod> endpoints = endpoints();
        assertThat(endpoints.size()).isGreaterThan(40);
        assertThat(endpoints.keySet()).containsAll(PUBLIC);
        assertThat(endpoints.keySet()).containsAll(CSRF_EXEMPT);
    }

    @Test
    void everyNonPublicEndpointRejectsAnAnonymousCallerWith401() throws Exception {
        // A valid CSRF token is sent for unsafe methods, so the 401 is the authentication decision, not CSRF's 403.
        ApiClient anonymous = new ApiClient(mvc, json).primeCsrf();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, RequestMethod> e : endpoints().entrySet()) {
            if (PUBLIC.contains(e.getKey())) {
                continue;
            }
            HttpMethod method = HttpMethod.valueOf(e.getValue().name());
            int status = anonymous.perform(method, concretePath(pathOf(e.getKey())), null, true).andReturn()
                    .getResponse().getStatus();
            checked++;
            if (status != 401) {
                failures.add(e.getKey() + " -> " + status + " (expected 401)");
            }
        }
        assertThat(checked).isGreaterThan(30);
        assertThat(failures).as("endpoints reachable without authentication").isEmpty();
    }

    @Test
    void everyMutatingEndpointRejectsAMissingCsrfTokenWith403() throws Exception {
        // No cookies at all, no header: CSRF is evaluated before authentication, so even public mutating
        // endpoints (login, signup...) must refuse. Only the signed Stripe webhook is exempt.
        ApiClient noToken = new ApiClient(mvc, json);
        List<String> failures = new ArrayList<>();
        Set<String> checkedKeys = new TreeSet<>();
        for (Map.Entry<String, RequestMethod> e : endpoints().entrySet()) {
            if (SAFE.contains(e.getValue()) || CSRF_EXEMPT.contains(e.getKey())) {
                continue;
            }
            HttpMethod method = HttpMethod.valueOf(e.getValue().name());
            int status = noToken.perform(method, concretePath(pathOf(e.getKey())), null, false).andReturn()
                    .getResponse().getStatus();
            checkedKeys.add(e.getKey());
            if (status != 403) {
                failures.add(e.getKey() + " -> " + status + " (expected 403)");
            }
        }
        assertThat(checkedKeys.size()).isGreaterThan(25);
        assertThat(failures).as("mutating endpoints that do not require CSRF").isEmpty();
    }

    @Test
    void aMismatchedCsrfTokenIsRejectedToo() throws Exception {
        ApiClient client = new ApiClient(mvc, json).primeCsrf();
        client.perform(HttpMethod.POST, "/api/v1/auth/login",
                Map.of("email", "a@example.com", "password", "x"), false)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .cookie(new jakarta.servlet.http.Cookie(ApiClient.CSRF_COOKIE, "real-looking-token"))
                        .header(ApiClient.CSRF_HEADER, "a-different-token")
                        .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void publicGetEndpointsAreExactlyTheAllowlist() throws Exception {
        // Counterpart of the 401 test for the allowlist: the public GETs must really be reachable anonymously.
        ApiClient anonymous = new ApiClient(mvc, json);
        for (String key : PUBLIC) {
            if (key.startsWith("GET ") && key.contains("/portal/")) {
                // Reachable without a session: an unknown token is the generic 404, not the 401 of a protected path.
                assertThat(anonymous.perform(HttpMethod.GET, concretePath(pathOf(key)), null, false).andReturn()
                        .getResponse().getStatus()).as(key).isEqualTo(404);
            } else if (key.startsWith("GET ")) {
                int status = anonymous.perform(HttpMethod.GET, pathOf(key), null, false).andReturn().getResponse()
                        .getStatus();
                assertThat(status).as(key).isLessThan(400);
            }
        }
    }

    @Test
    void unmappedPathsAndActuatorAreDeniedToAnonymousCallers() throws Exception {
        // anyRequest().authenticated(): probing a path that does not exist must not reveal that it does not exist.
        ApiClient anonymous = new ApiClient(mvc, json);
        for (String path : new String[] {"/api/v1/does-not-exist", "/api/v2/anything", "/admin", "/actuator/env",
                "/actuator/beans", "/actuator/heapdump", "/actuator/mappings", "/api/test/mailbox",
                "/actuator/metrics", "/error"}) {
            int status = anonymous.perform(HttpMethod.GET, path, null, false).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isEqualTo(401);
        }
        // Only health is public on the actuator.
        assertThat(anonymous.perform(HttpMethod.GET, "/actuator/health", null, false).andReturn().getResponse()
                .getStatus()).isEqualTo(200);
    }
}
