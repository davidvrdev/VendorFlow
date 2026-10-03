package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class SecurityAndRateLimitTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired ApplicationContext context;

    @Test
    void loginIsRateLimitedPerIpWith429AndRetryAfter() throws Exception {
        ApiClient attacker = new ApiClient(mvc, json).remoteAddr("203.0.113.7").primeCsrf();
        for (int i = 0; i < 10; i++) {
            attacker.post("/api/v1/auth/login", Map.of("email", "x@example.com", "password", "Wrong-Password-123"))
                    .andExpect(status().isUnauthorized());
        }
        attacker.post("/api/v1/auth/login", Map.of("email", "x@example.com", "password", "Wrong-Password-123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/rate-limited"))
                .andExpect(jsonPath("$.requestId").exists());

        // A different client address has its own budget.
        ApiClient other = new ApiClient(mvc, json).remoteAddr("203.0.113.8").primeCsrf();
        other.post("/api/v1/auth/login", Map.of("email", "x@example.com", "password", "Wrong-Password-123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupAndOtherAuthRulesHaveTheirOwnLimits() throws Exception {
        ApiClient client = new ApiClient(mvc, json).remoteAddr("203.0.113.20").primeCsrf();
        for (int i = 0; i < 5; i++) {
            client.post("/api/v1/auth/signup", Map.of("email", "bad", "password", "x", "fullName", "", "organizationName", ""))
                    .andExpect(status().isBadRequest());
        }
        client.post("/api/v1/auth/signup", Map.of("email", "bad", "password", "x", "fullName", "", "organizationName", ""))
                .andExpect(status().isTooManyRequests());
        // Login budget of the same IP is independent from the signup budget.
        client.post("/api/v1/auth/login", Map.of("email", "x@example.com", "password", "Wrong-Password-123"))
                .andExpect(status().isUnauthorized());
        // The B2 endpoints are limited already (they 401/404 or similar now, but must hit 429 eventually).
        for (int i = 0; i < 3; i++) {
            client.post("/api/v1/auth/resend-verification", null);
        }
        client.post("/api/v1/auth/resend-verification", null).andExpect(status().isTooManyRequests());
    }

    @Test
    void getRequestsAreNotRateLimited() throws Exception {
        ApiClient client = new ApiClient(mvc, json).remoteAddr("203.0.113.30");
        for (int i = 0; i < 30; i++) {
            client.get("/api/v1/auth/csrf").andExpect(status().isNoContent());
        }
    }

    @Test
    void protectedEndpointsReturn401ProblemWhenAnonymous() throws Exception {
        ApiClient client = new ApiClient(mvc, json);
        client.get("/api/v1/me").andExpect(status().isUnauthorized());
        client.get("/api/v1/organization").andExpect(status().isUnauthorized());
        client.get("/api/v1/vendors").andExpect(status().isUnauthorized());
        client.perform(HttpMethod.POST, "/api/v1/session/organization", Map.of(), false)
                .andExpect(status().isForbidden()); // CSRF is checked before authentication for unsafe methods
    }

    @Test
    void noDefaultUserDetailsServiceIsCreated() {
        // Spring Boot's generated "user"/random password must not exist (we authenticate in AuthService).
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void sessionCookieIsNotSetOnAnonymousRequests() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
