package com.vendorflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.dashboard.infrastructure.DashboardRepository;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 500 vendors x 6 requirements (~2,850 current documents): bounded statements and a generous latency budget. */
class DashboardPerformanceTest extends IntegrationTest {

    static final int VENDORS = 500;
    static final List<String> CODES = List.of("COI", "GENERAL_LIABILITY", "WORKERS_COMP", "BUSINESS_LICENSE",
            "PROFESSIONAL_LICENSE", "W9");
    static final long BUDGET_MS = 2_000;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired ComplianceContextService contexts;

    @Test
    void dashboardEndpointsAreBoundedAndFastAtFiveHundredVendors() throws Exception {
        Account owner = new TestAccounts(mvc, json, jdbc).signup("Perf Org " + UUID.randomUUID());
        String org = owner.organizationId();
        LocalDate today = contexts.todayIn("America/New_York");
        seed(org, today);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        ApiClient client = owner.client();
        // Warm-up (JIT, plan cache), then measure.
        client.get("/api/v1/dashboard/summary").andExpect(status().isOk());
        client.get("/api/v1/dashboard/attention?size=100").andExpect(status().isOk());

        stats.clear();
        long t0 = System.nanoTime();
        JsonNode summary = json.readTree(client.get("/api/v1/dashboard/summary").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        long summaryMs = (System.nanoTime() - t0) / 1_000_000;
        long summaryStatements = stats.getPrepareStatementCount();

        stats.clear();
        t0 = System.nanoTime();
        JsonNode attention = json.readTree(client.get("/api/v1/dashboard/attention?size=100")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long attentionMs = (System.nanoTime() - t0) / 1_000_000;
        long attentionStatements = stats.getPrepareStatementCount();

        System.out.printf("DASHBOARD PERF (%d vendors): summary %d ms / %d statements; attention(size=100) %d ms / %d "
                + "statements; attention total=%d%n", VENDORS, summaryMs, summaryStatements, attentionMs,
                attentionStatements, attention.get("totalItems").asInt());

        explain(org, today);
        assertThat(summary.get("vendors").get("active").asInt()).isEqualTo(VENDORS - VENDORS / 20); // every 20th vendor is INACTIVE
        assertThat(summary.get("documents").get("missing").asInt()).isPositive();
        assertThat(attention.get("items")).hasSize(100);
        // tenant lookup + organization settings + the query (+ count for the page)
        assertThat(summaryStatements).isLessThanOrEqualTo(3);
        assertThat(attentionStatements).isLessThanOrEqualTo(4);
        assertThat(summaryMs).isLessThan(BUDGET_MS);
        assertThat(attentionMs).isLessThan(BUDGET_MS);
    }

    /** Prints the plan of the summary statement (evidence for the index decision in docs/DATABASE.md). */
    private void explain(String org, LocalDate today) {
        String sql = DashboardRepository.SUMMARY_SQL.replace(":organizationId", "'" + org + "'::uuid")
                .replace(":today", "'" + today + "'").replace(":windowDays", "30");
        List<String> plan = jdbc.queryForList("explain (analyze, buffers) " + sql, String.class);
        System.out.println("EXPLAIN ANALYZE summary:\n" + String.join("\n", plan));
        String page = DashboardRepository.ATTENTION_PAGE_SQL.replace(":organizationId", "'" + org + "'::uuid")
                .replace(":today", "'" + today + "'").replace(":windowDays", "30").replace(":limit", "100")
                .replace(":offset", "0");
        System.out.println("EXPLAIN ANALYZE attention page:\n"
                + String.join("\n", jdbc.queryForList("explain (analyze, buffers) " + page, String.class)));
    }

    private void seed(String org, LocalDate today) {
        Random rnd = new Random(42);
        List<UUID> vendorIds = new ArrayList<>();
        List<Object[]> vendorRows = new ArrayList<>();
        for (int i = 0; i < VENDORS; i++) {
            UUID id = UUID.randomUUID();
            vendorIds.add(id);
            // ~5% inactive
            vendorRows.add(new Object[] {id, org, "Perf Vendor " + String.format("%04d", i),
                    i % 20 == 0 ? "INACTIVE" : "ACTIVE"});
        }
        jdbc.batchUpdate("""
                insert into vendor (id, organization_id, company_name, status, created_at, updated_at)
                values (?, ?::uuid, ?, ?, now(), now())""", vendorRows);

        List<Object[]> typeIds = new ArrayList<>();
        for (String code : CODES) {
            typeIds.add(new Object[] {jdbc.queryForObject(
                    "select id from document_type where organization_id = ?::uuid and code = ?", UUID.class, org,
                    code), code});
        }
        List<Object[]> reqRows = new ArrayList<>();
        List<Object[]> docRows = new ArrayList<>();
        Instant now = Instant.now();
        for (UUID vendor : vendorIds) {
            for (Object[] type : typeIds) {
                UUID typeId = (UUID) type[0];
                boolean expiring = !type[1].equals("W9");
                reqRows.add(new Object[] {org, vendor, typeId});
                int roll = rnd.nextInt(100);
                if (roll < 5) {
                    continue; // missing
                }
                String review = roll < 15 ? "PENDING" : roll < 20 ? "REJECTED" : "APPROVED";
                Date expiration = expiring ? Date.valueOf(today.plusDays(rnd.nextInt(400) - 60)) : null;
                docRows.add(new Object[] {org, vendor, typeId, review, expiration,
                        Timestamp.from(now.minusSeconds(rnd.nextInt(10_000_000)))});
            }
        }
        jdbc.batchUpdate("""
                insert into vendor_requirement (id, organization_id, vendor_id, document_type_id, created_at)
                values (gen_random_uuid(), ?::uuid, ?, ?, now())""", reqRows);
        jdbc.batchUpdate("""
                insert into document (id, organization_id, vendor_id, document_type_id, state, review_status,
                    expiration_date, storage_key, original_filename, mime_type, size_bytes, sha256, created_at,
                    updated_at)
                values (gen_random_uuid(), ?::uuid, ?, ?, 'CURRENT', ?, ?, 'k/' || gen_random_uuid(), 'f.pdf',
                    'application/pdf', 10, repeat('a', 64), ?, ?)""",
                docRows.stream().map(r -> new Object[] {r[0], r[1], r[2], r[3], r[4], r[5], r[5]}).toList());
        jdbc.execute("analyze vendor_requirement");
        jdbc.execute("analyze document");
        jdbc.execute("analyze vendor");
    }
}
