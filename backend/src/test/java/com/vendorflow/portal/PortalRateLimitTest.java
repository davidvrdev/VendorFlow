package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.shared.ratelimit.RateLimitProperties;
import com.vendorflow.support.ApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

/** The four portal rate-limit rules (production defaults are too high to exhaust, so each test lowers its own). */
class PortalRateLimitTest extends PortalTestBase {

    @Autowired RateLimitProperties properties;

    /** 429 + Retry-After + RFC 9457 body with requestId. */
    private static void assertRateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/rate-limited"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    private static int statusOf(ResultActions r) {
        return r.andReturn().getResponse().getStatus();
    }

    @Test
    void viewIsLimitedPerIp() throws Exception {
        Integer original = properties.getLimits().put("portal-view", 3);
        try {
            String vendor = createVendor(member, "Acme", null);
            String token = tokenOf(createLink(member, vendor, List.of(typeId(member, "W9"))));
            ApiClient ip = new ApiClient(mvc, json).remoteAddr("203.0.113.201");
            // unknown tokens count too: the per-IP budget is what stops token probing
            for (int i = 0; i < 3; i++) {
                assertThat(statusOf(ip.header("X-Portal-Token", "x".repeat(43)).perform(HttpMethod.GET, PORTAL, null, false)))
                        .isEqualTo(404);
            }
            assertRateLimited(ip.header("X-Portal-Token", "x".repeat(43)).perform(HttpMethod.GET, PORTAL, null, false));
            assertRateLimited(ip.header("X-Portal-Token", token).perform(HttpMethod.GET, PORTAL, null, false));
            // HEAD is routed to the GET handler and shares the budget
            assertRateLimited(ip.header("X-Portal-Token", token).perform(HttpMethod.HEAD, PORTAL, null, false));
            // another address is unaffected
            new ApiClient(mvc, json).remoteAddr("203.0.113.202").header("X-Portal-Token", token).perform(HttpMethod.GET, PORTAL, null, false)
                    .andExpect(status().isOk());
        } finally {
            properties.getLimits().put("portal-view", original);
        }
    }

    @Test
    void uploadIsLimitedPerIp() throws Exception {
        Integer original = properties.getLimits().put("portal-upload", 2);
        try {
            String vendor = createVendor(member, "Acme", null);
            String w9 = typeId(member, "W9");
            String token = tokenOf(createLink(member, vendor, List.of(w9)));
            ApiClient ip = new ApiClient(mvc, json).remoteAddr("203.0.113.203");
            ip.header("X-Portal-Token", token).postMultipartWithoutCsrf(PORTAL_DOCS, file("a.pdf", PDF), fields(w9, null, null))
                    .andExpect(status().isCreated());
            // a rejected attempt is counted as well (the filter runs before any validation)
            assertThat(statusOf(ip.header("X-Portal-Token", token).postMultipartWithoutCsrf(PORTAL_DOCS,
                    file("a.exe", PDF), fields(w9, null, null)))).isEqualTo(415);
            assertRateLimited(ip.header("X-Portal-Token", token).postMultipartWithoutCsrf(PORTAL_DOCS, file("b.pdf", PDF),
                    fields(w9, null, null)));
            assertThat(documentRows(vendor)).isEqualTo(1);
            // uploads and views are separate budgets
            ip.header("X-Portal-Token", token).perform(HttpMethod.GET, PORTAL, null, false).andExpect(status().isOk());
        } finally {
            properties.getLimits().put("portal-upload", original);
        }
    }

    @Test
    void linkBudgetIsPerLinkNotPerIp() throws Exception {
        Integer original = properties.getLimits().put("portal-link", 3);
        try {
            String vendor = createVendor(member, "Acme", null);
            String w9 = typeId(member, "W9");
            String token = tokenOf(createLink(member, vendor, List.of(w9)));
            String otherToken = tokenOf(createLink(member, vendor, List.of(w9)));
            // three different IPs, one link: the link budget (views + uploads together) is what runs out
            portalGetFrom("203.0.113.211", token).andExpect(status().isOk());
            portalGetFrom("203.0.113.212", token).andExpect(status().isOk());
            new ApiClient(mvc, json).remoteAddr("203.0.113.213").header("X-Portal-Token", token)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("a.pdf", PDF),
                            fields(w9, null, null)).andExpect(status().isCreated());
            assertRateLimited(portalGetFrom("203.0.113.214", token));
            assertRateLimited(new ApiClient(mvc, json).remoteAddr("203.0.113.215").header("X-Portal-Token", token)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("b.pdf", PDF),
                            fields(w9, null, null)));
            assertThat(documentRows(vendor)).isEqualTo(1);
            // another link of the same vendor, same IP: untouched
            portalGetFrom("203.0.113.214", otherToken).andExpect(status().isOk());
        } finally {
            properties.getLimits().put("portal-link", original);
        }
    }

    @Test
    void uploadAttemptsAreLimitedPerLinkAndFailedAttemptsCount() throws Exception {
        Integer original = properties.getLimits().put("portal-link-upload", 3);
        try {
            String vendor = createVendor(member, "Acme", null);
            String w9 = typeId(member, "W9");
            String token = tokenOf(createLink(member, vendor, List.of(w9)));
            String otherToken = tokenOf(createLink(member, vendor, List.of(w9)));
            // three different IPs: only the LINK budget can be what runs out. Two of the attempts fail validation.
            assertThat(statusOf(new ApiClient(mvc, json).remoteAddr("203.0.113.231").header("X-Portal-Token", token)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("a.exe", PDF), fields(w9, null, null)))).isEqualTo(415);
            assertThat(statusOf(new ApiClient(mvc, json).remoteAddr("203.0.113.232").header("X-Portal-Token", token)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("a.pdf", PDF), fields(null, null, null)))).isEqualTo(400);
            new ApiClient(mvc, json).remoteAddr("203.0.113.233").header("X-Portal-Token", token)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("a.pdf", PDF), fields(w9, null, null))
                    .andExpect(status().isCreated());
            assertRateLimited(new ApiClient(mvc, json).remoteAddr("203.0.113.234").header("X-Portal-Token", token)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("b.pdf", PDF), fields(w9, null, null)));
            assertThat(documentRows(vendor)).isEqualTo(1);
            // viewing is a different rule, another link is untouched, unknown tokens never reach the rule
            portalGetFrom("203.0.113.235", token).andExpect(status().isOk());
            new ApiClient(mvc, json).remoteAddr("203.0.113.236").header("X-Portal-Token", otherToken)
                    .postMultipartWithoutCsrf(PORTAL_DOCS, file("c.pdf", PDF), fields(w9, null, null))
                    .andExpect(status().isCreated());
        } finally {
            properties.getLimits().put("portal-link-upload", original);
        }
    }

    @Test
    void unknownTokensDoNotCreateLinkBudgetEntries() throws Exception {
        // The per-link counter is keyed by the link id after a successful lookup, so attacker-chosen tokens cannot
        // grow the limiter's map; a flood of unknown tokens is only ever counted per IP.
        Integer original = properties.getLimits().put("portal-link", 1);
        try {
            for (int i = 0; i < 5; i++) {
                portalGetFrom("203.0.113.22" + i, "y".repeat(43)).andExpect(status().isNotFound());
            }
        } finally {
            properties.getLimits().put("portal-link", original);
        }
    }

    @Test
    void linkCreationIsLimitedPerUser() throws Exception {
        Integer original = properties.getLimits().put("portal-link-create-user", 2);
        try {
            String vendor = createVendor(member, "Acme", null);
            String w9 = typeId(member, "W9");
            for (int i = 0; i < 2; i++) {
                postLink(member, vendor, body(List.of(w9))).andExpect(status().isCreated());
            }
            assertRateLimited(postLink(member, vendor, body(List.of(w9))));
            // per user, not per organization or IP
            postLink(admin, vendor, body(List.of(w9))).andExpect(status().isCreated());
        } finally {
            properties.getLimits().put("portal-link-create-user", original);
        }
    }

    private ResultActions portalGetFrom(String ip, String token) throws Exception {
        return new ApiClient(mvc, json).remoteAddr(ip).header("X-Portal-Token", token).perform(HttpMethod.GET, PORTAL, null, false);
    }
}
