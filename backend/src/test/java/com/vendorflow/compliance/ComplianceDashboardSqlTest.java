package com.vendorflow.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.compliance.ComplianceVectors.Vector;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.compliance.domain.RequirementStatus;
import com.vendorflow.support.ComplianceFixtures;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard is held to the same parity as the vendor list: the very same requirement vectors (vectors.csv) are
 * inserted and the dashboard counts / attention items must equal the expected statuses.
 */
class ComplianceDashboardSqlTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ComplianceContextService contexts;

    @Test
    void requirementVectorsGiveTheSameResultOnTheDashboard() throws Exception {
        ComplianceFixtures fx = new ComplianceFixtures(jdbc);
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        // One organization per window size so each summary covers exactly the vectors of that window.
        Map<Integer, List<Vector>> byWindow = new TreeMap<>();
        for (Vector v : ComplianceVectors.requirementVectors()) {
            byWindow.computeIfAbsent(v.windowDays(), w -> new ArrayList<>()).add(v);
        }
        for (var entry : byWindow.entrySet()) {
            Account owner = accounts.signup("Vectors Dash " + UUID.randomUUID());
            String org = owner.organizationId();
            owner.client().patch("/api/v1/organization", Map.of("expiringWindowDays", entry.getKey()))
                    .andExpect(status().isOk());
            LocalDate today = contexts.todayIn("America/New_York");
            Map<RequirementStatus, Integer> expected = new EnumMap<>(RequirementStatus.class);
            for (Vector v : entry.getValue()) {
                UUID vendor = fx.vendor(org, "DV " + v.name());
                fx.requirement(org, vendor, v.hasExpiration() ? "COI" : "W9", v.review(),
                        v.offset() == null ? null : today.plusDays(v.offset()));
                expected.merge(v.expected(), 1, Integer::sum);
            }
            String what = "window " + entry.getKey();

            JsonNode summary = json.readTree(owner.client().get("/api/v1/dashboard/summary").andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            JsonNode docs = summary.get("documents");
            assertThat(docs.get("missing").asInt()).as(what).isEqualTo(expected.getOrDefault(RequirementStatus.MISSING, 0));
            assertThat(docs.get("expired").asInt()).as(what).isEqualTo(expected.getOrDefault(RequirementStatus.EXPIRED, 0));
            assertThat(docs.get("expiring").asInt()).as(what).isEqualTo(expected.getOrDefault(RequirementStatus.EXPIRING, 0));
            assertThat(docs.get("reviewRequired").asInt()).as(what)
                    .isEqualTo(expected.getOrDefault(RequirementStatus.REVIEW_REQUIRED, 0));
            int ok = expected.getOrDefault(RequirementStatus.OK, 0);
            JsonNode vendors = summary.get("vendors");
            assertThat(vendors.get("active").asInt()).as(what).isEqualTo(entry.getValue().size());
            assertThat(vendors.get("compliant").asInt()).as(what).isEqualTo(ok);
            assertThat(vendors.get("attention").asInt()).as(what)
                    .isEqualTo(expected.getOrDefault(RequirementStatus.EXPIRING, 0)
                            + expected.getOrDefault(RequirementStatus.REVIEW_REQUIRED, 0));
            assertThat(vendors.get("nonCompliant").asInt()).as(what)
                    .isEqualTo(expected.getOrDefault(RequirementStatus.MISSING, 0)
                            + expected.getOrDefault(RequirementStatus.EXPIRED, 0));

            JsonNode attention = json.readTree(owner.client().get("/api/v1/dashboard/attention?size=100")
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            Map<String, JsonNode> byVendor = new HashMap<>();
            attention.get("items").forEach(i -> byVendor.put(i.get("vendorName").asString(), i));
            assertThat(attention.get("totalItems").asInt()).as(what).isEqualTo(entry.getValue().size() - ok);
            for (Vector v : entry.getValue()) {
                JsonNode item = byVendor.get("DV " + v.name());
                if (v.expected() == RequirementStatus.OK) {
                    assertThat(item).as(v.name()).isNull();
                    continue;
                }
                assertThat(item).as(v.name()).isNotNull();
                assertThat(item.get("status").asString()).as(v.name()).isEqualTo(v.expected().name());
                assertThat(item.get("action").asString()).as(v.name()).isEqualTo(switch (v.expected()) {
                    case MISSING, EXPIRED -> "UPLOAD";
                    case EXPIRING -> "UPLOAD_RENEWAL";
                    default -> "REVIEW";
                });
            }
        }
    }
}
