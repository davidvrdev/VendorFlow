package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.shared.ratelimit.RateLimitProperties;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ASVS V11.1 / V2.2: every configured rate-limit rule demonstrably answers 429 problem+json with a Retry-After header
 * once its threshold is crossed. Rules whose behavior is richer (windows, per-user vs per-IP) have their detailed
 * test elsewhere; {@link #COVERAGE} says where, and {@link #everyConfiguredRuleHasATest} fails when someone adds a
 * rule to {@link RateLimitProperties} without adding a test.
 */
class RateLimitRulesTest extends IntegrationTest {

    /** rule -> test that proves its 429. */
    private static final Map<String, String> COVERAGE = new TreeMap<>(Map.ofEntries(
            Map.entry("login", "SecurityAndRateLimitTest.loginIsRateLimitedPerIpWith429AndRetryAfter"),
            Map.entry("signup", "SecurityAndRateLimitTest.signupAndOtherAuthRulesHaveTheirOwnLimits"),
            Map.entry("resend-verification", "SecurityAndRateLimitTest.signupAndOtherAuthRulesHaveTheirOwnLimits"),
            Map.entry("password-reset-request", "RateLimitRulesTest.passwordResetRequest"),
            Map.entry("password-change", "PasswordChangeTest.isRateLimitedPerUser"),
            Map.entry("token-redemption", "RateLimitRulesTest.tokenRedemption"),
            Map.entry("invitation", "RateLimitRulesTest.invitationLookupAndAcceptShareOneBudget"),
            Map.entry("document-upload", "DocumentUploadTest.uploadsAreRateLimitedPerIpAt30PerMinute"),
            Map.entry("document-upload-user", "DocumentQuotaAndRateTest.uploadBudgetIsPerUserNotPerIp"),
            Map.entry("document-request-user", "RateLimitRulesTest.documentRequestsArePerUser"),
            Map.entry("vendor-import-preview-user", "VendorImportPreviewTest (rate limit test)"),
            Map.entry("vendor-export-user", "VendorCsvExportTest.exportsAreLimitedToTenPerTenMinutesPerUser"),
            Map.entry("portal-view", "PortalRateLimitTest.viewIsLimitedPerIp"),
            Map.entry("portal-upload", "PortalRateLimitTest.uploadIsLimitedPerIp"),
            Map.entry("portal-link", "PortalRateLimitTest.linkBudgetIsPerLinkNotPerIp"),
            Map.entry("portal-link-upload", "PortalRateLimitTest.uploadAttemptsAreLimitedPerLinkAndFailedAttemptsCount"),
            Map.entry("portal-link-create-user", "PortalRateLimitTest.linkCreationIsLimitedPerUser"),
            Map.entry("stripe-webhook", "RateLimitRulesTest.stripeWebhook")));

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimitProperties properties;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    @Test
    void everyConfiguredRuleHasATest() {
        assertThat(properties.getLimits().keySet()).as("a rate-limit rule without a 429 test")
                .containsExactlyInAnyOrderElementsOf(COVERAGE.keySet());
    }

    /** 429 + Retry-After (positive integer) + RFC 9457 body with requestId. */
    private void assertRateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/rate-limited"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.requestId").exists());
        String retryAfter = result.andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 3600L);
    }

    private static void assertNotRateLimited(ResultActions result) throws Exception {
        assertThat(result.andReturn().getResponse().getStatus()).isNotEqualTo(429);
    }

    @Test
    void passwordResetRequest() throws Exception {
        ApiClient client = new ApiClient(mvc, json).remoteAddr("203.0.113.101").primeCsrf();
        for (int i = 0; i < 5; i++) {
            client.post("/api/v1/auth/password-reset/request", Map.of("email", "nobody@example.com"))
                    .andExpect(status().is2xxSuccessful());
        }
        assertRateLimited(client.post("/api/v1/auth/password-reset/request", Map.of("email", "nobody@example.com")));
        // Another address is unaffected.
        new ApiClient(mvc, json).remoteAddr("203.0.113.102").primeCsrf()
                .post("/api/v1/auth/password-reset/request", Map.of("email", "nobody@example.com"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void tokenRedemption() throws Exception {
        ApiClient client = new ApiClient(mvc, json).remoteAddr("203.0.113.103").primeCsrf();
        // verify-email and password-reset/confirm draw on the same per-IP budget of 20.
        for (int i = 0; i < 10; i++) {
            assertNotRateLimited(client.post("/api/v1/auth/verify-email", Map.of("token", "unknown-" + i)));
            assertNotRateLimited(client.post("/api/v1/auth/password-reset/confirm",
                    Map.of("token", "unknown-" + i, "newPassword", "Another-Long-Password-1")));
        }
        assertRateLimited(client.post("/api/v1/auth/verify-email", Map.of("token", "unknown-x")));
        assertRateLimited(client.post("/api/v1/auth/password-reset/confirm",
                Map.of("token", "unknown-x", "newPassword", "Another-Long-Password-1")));
    }

    @Test
    void invitationLookupAndAcceptShareOneBudget() throws Exception {
        ApiClient client = new ApiClient(mvc, json).remoteAddr("203.0.113.104").primeCsrf();
        for (int i = 0; i < 20; i++) {
            assertNotRateLimited(client.post("/api/v1/invitations/lookup", Map.of("token", "unknown-" + i)));
        }
        assertRateLimited(client.post("/api/v1/invitations/lookup", Map.of("token", "unknown-x")));
        assertRateLimited(client.post("/api/v1/invitations/accept",
                Map.of("token", "unknown-x", "fullName", "A B", "password", "Another-Long-Password-1")));
    }

    @Test
    void stripeWebhook() throws Exception {
        // The production default (600/min per IP) is too high to exhaust in a test: lowered for this test only.
        Integer original = properties.getLimits().put("stripe-webhook", 3);
        try {
            ApiClient stripe = new ApiClient(mvc, json).remoteAddr("203.0.113.105"); // no CSRF: the webhook is exempt
            for (int i = 0; i < 3; i++) {
                assertNotRateLimited(stripe.postWithoutCsrf("/api/v1/webhooks/stripe", Map.of("id", "evt_x")));
            }
            assertRateLimited(stripe.postWithoutCsrf("/api/v1/webhooks/stripe", Map.of("id", "evt_x")));
        } finally {
            properties.getLimits().put("stripe-webhook", original);
        }
    }

    @Test
    void documentRequestsArePerUser() throws Exception {
        // Limit lowered for this test (production: 30/min per user) so it stays fast; the code path is the same.
        Integer original = properties.getLimits().put("document-request-user", 2);
        try {
            TestAccounts.Account owner = accounts.signup("Request Limit Org " + UUID.randomUUID());
            TestAccounts.Account admin = accounts.memberOf(owner.organizationId(), "ADMIN", "Adam Admin");
            String vendorId = json.readTree(owner.client().post("/api/v1/vendors",
                            Map.of("companyName", "Acme Roofing", "contactName", "Carl", "email", "carl@example.com"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                    .get("id").asString();
            JsonNode types = json.readTree(owner.client().get("/api/v1/document-types").andReturn().getResponse()
                    .getContentAsString());
            String typeId = types.get(0).get("id").asString();
            String path = "/api/v1/vendors/" + vendorId + "/document-requests";
            for (int i = 0; i < 2; i++) {
                assertNotRateLimited(owner.client().post(path, Map.of("documentTypeId", typeId)));
            }
            assertRateLimited(owner.client().post(path, Map.of("documentTypeId", typeId)));
            // The budget is per user, not per IP or organization.
            assertNotRateLimited(admin.client().post(path, Map.of("documentTypeId", typeId)));
        } finally {
            properties.getLimits().put("document-request-user", original);
        }
    }
}
