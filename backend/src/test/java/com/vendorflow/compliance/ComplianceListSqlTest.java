package com.vendorflow.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.compliance.ComplianceVectors.ReqSpec;
import com.vendorflow.compliance.ComplianceVectors.VendorVector;
import com.vendorflow.compliance.ComplianceVectors.Vector;
import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.compliance.domain.VendorCompliance;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL side of the compliance engine against a real Postgres: the shared vectors (same files as
 * ComplianceCalculatorVectorsTest), filters, sorts, tenant isolation, time zones, settings changes, query count.
 * Fixtures are inserted with JDBC (uploads are covered elsewhere) so every review/date combination is possible.
 */
class ComplianceListSqlTest extends IntegrationTest {

    static final String VENDORS = "/api/v1/vendors";
    /** Seeded types: five with expiration, three without (DocumentTypeService defaults). */
    static final List<String> EXPIRING_CODES = List.of("COI", "GENERAL_LIABILITY", "WORKERS_COMP", "BUSINESS_LICENSE",
            "PROFESSIONAL_LICENSE");
    static final List<String> PLAIN_CODES = List.of("W9", "CONTRACT", "OTHER");

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired ComplianceContextService contexts;

    Account owner;
    Account other;
    String orgId;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Compliance Org " + UUID.randomUUID());
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Compliance Other " + UUID.randomUUID());
        orgId = owner.organizationId();
    }

    // ---- fixtures ----

    UUID typeId(String org, String code) {
        return jdbc.queryForObject("select id from document_type where organization_id = ?::uuid and code = ?",
                UUID.class, org, code);
    }

    /** A vendor with NO requirements (the API would add the default ones). */
    UUID vendor(String org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into vendor (id, organization_id, company_name, status, created_at, updated_at)
                values (?, ?::uuid, ?, 'ACTIVE', now(), now())""", id, org, name);
        return id;
    }

    void require(String org, UUID vendor, String typeCode) {
        jdbc.update("""
                insert into vendor_requirement (id, organization_id, vendor_id, document_type_id, created_at)
                values (gen_random_uuid(), ?::uuid, ?, ?, now())""", org, vendor, typeId(org, typeCode));
    }

    void document(String org, UUID vendor, String typeCode, String state, ReviewStatus review, LocalDate expiration) {
        jdbc.update("""
                insert into document (id, organization_id, vendor_id, document_type_id, state, review_status,
                    expiration_date, storage_key, original_filename, mime_type, size_bytes, sha256, created_at,
                    updated_at)
                values (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, 'k/' || gen_random_uuid(), 'f.pdf',
                    'application/pdf', 10, repeat('a', 64), now(), now())""",
                org, vendor, typeId(org, typeCode), state, review.name(), expiration);
    }

    /** One requirement of the given code for a vendor, with an optional CURRENT document. */
    void requirement(String org, UUID vendor, String code, ReviewStatus review, LocalDate expiration) {
        require(org, vendor, code);
        if (review != null) {
            document(org, vendor, code, "CURRENT", review, expiration);
        }
    }

    void setWindow(Account who, int days) throws Exception {
        who.client().patch("/api/v1/organization", Map.of("expiringWindowDays", days)).andExpect(status().isOk());
    }

    void setZone(Account who, String zone) throws Exception {
        who.client().patch("/api/v1/organization", Map.of("timeZone", zone)).andExpect(status().isOk());
    }

    LocalDate today(String zone) {
        return contexts.todayIn(zone);
    }

    JsonNode page(Account who, String query) throws Exception {
        return json.readTree(who.client().get(VENDORS + "?" + query).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    Map<String, JsonNode> byName(Account who) throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        page(who, "status=ALL&size=100").get("items").forEach(i -> out.put(i.get("companyName").asString(), i));
        return out;
    }

    List<String> names(Account who, String query) throws Exception {
        List<String> out = new ArrayList<>();
        page(who, query).get("items").forEach(i -> out.add(i.get("companyName").asString()));
        return out;
    }

    // ---- shared vectors against the SQL ----

    @Test
    void requirementVectorsGiveTheSameResultInSql() throws Exception {
        String zone = "America/New_York";
        LocalDate today = today(zone);
        List<Vector> vectors = ComplianceVectors.requirementVectors();
        Map<Integer, List<Vector>> byWindow = new TreeMap<>();
        for (Vector v : vectors) {
            UUID vendor = vendor(orgId, "RV " + v.name());
            requirement(orgId, vendor, v.hasExpiration() ? "COI" : "W9", v.review(),
                    v.offset() == null ? null : today.plusDays(v.offset()));
            byWindow.computeIfAbsent(v.windowDays(), w -> new ArrayList<>()).add(v);
        }

        for (var entry : byWindow.entrySet()) {
            setWindow(owner, entry.getKey());
            Map<String, JsonNode> items = byName(owner);
            for (Vector v : entry.getValue()) {
                JsonNode c = items.get("RV " + v.name()).get("compliance");
                String what = v.name() + " (window " + v.windowDays() + ")";
                String counts = c.get("missing").asInt() + "/" + c.get("expired").asInt() + "/"
                        + c.get("expiring").asInt() + "/" + c.get("reviewRequired").asInt() + "/" + c.get("ok").asInt();
                int[] expected = new int[5];
                expected[switch (v.expected()) {
                    case MISSING -> 0;
                    case EXPIRED -> 1;
                    case EXPIRING -> 2;
                    case REVIEW_REQUIRED -> 3;
                    case OK -> 4;
                }] = 1;
                assertThat(counts).as(what).isEqualTo(expected[0] + "/" + expected[1] + "/" + expected[2] + "/"
                        + expected[3] + "/" + expected[4]);
                VendorCompliance vendorStatus = switch (v.expected()) {
                    case MISSING, EXPIRED -> VendorCompliance.NON_COMPLIANT;
                    case EXPIRING, REVIEW_REQUIRED -> VendorCompliance.ATTENTION;
                    case OK -> VendorCompliance.COMPLIANT;
                };
                assertThat(c.get("status").asString()).as(what).isEqualTo(vendorStatus.name());
                assertThat(items.get("RV " + v.name()).get("requirementCount").asInt()).as(what).isEqualTo(1);
                String next = c.get("nextExpiration").isNull() ? null : c.get("nextExpiration").asString();
                assertThat(next).as(what)
                        .isEqualTo(v.countsNext() ? today.plusDays(v.offset()).toString() : null);
            }
        }
    }

    @Test
    void vendorVectorsGiveTheSameResultInSql() throws Exception {
        LocalDate today = today("America/New_York");
        Map<Integer, List<VendorVector>> byWindow = new TreeMap<>();
        for (VendorVector v : ComplianceVectors.vendorVectors()) {
            UUID vendor = vendor(orgId, "VV " + v.name());
            int expiringIdx = 0;
            int plainIdx = 0;
            for (ReqSpec r : v.requirements()) {
                String code = r.hasExpiration() ? EXPIRING_CODES.get(expiringIdx++) : PLAIN_CODES.get(plainIdx++);
                requirement(orgId, vendor, code, r.review(), r.offset() == null ? null : today.plusDays(r.offset()));
            }
            byWindow.computeIfAbsent(v.windowDays(), w -> new ArrayList<>()).add(v);
        }
        for (var entry : byWindow.entrySet()) {
            setWindow(owner, entry.getKey());
            Map<String, JsonNode> items = byName(owner);
            for (VendorVector v : entry.getValue()) {
                JsonNode item = items.get("VV " + v.name());
                JsonNode c = item.get("compliance");
                assertThat(c.get("status").asString()).as(v.name()).isEqualTo(v.expected().name());
                assertThat(List.of(c.get("missing").asInt(), c.get("expired").asInt(), c.get("expiring").asInt(),
                        c.get("reviewRequired").asInt(), c.get("ok").asInt())).as(v.name())
                        .containsExactly(v.missing(), v.expired(), v.expiring(), v.reviewRequired(), v.ok());
                assertThat(c.get("nextExpiration").isNull() ? null : c.get("nextExpiration").asString())
                        .as(v.name()).isEqualTo(v.nextOffset() == null ? null : today.plusDays(v.nextOffset()).toString());
                assertThat(c.get("daysUntilNextExpiration").isNull() ? null : c.get("daysUntilNextExpiration").asInt())
                        .as(v.name()).isEqualTo(v.nextOffset());
                assertThat(item.get("requirementCount").asInt()).as(v.name()).isEqualTo(v.requirements().size());
            }
        }
    }

    // ---- filter, sort, count ----

    /** Three vendors: NON_COMPLIANT (missing), ATTENTION (expiring in 10 days), COMPLIANT (ok in 100 days). */
    void threeVendors() {
        LocalDate today = today("America/New_York");
        UUID bad = vendor(orgId, "Bravo Bad");
        requirement(orgId, bad, "COI", null, null);
        UUID warn = vendor(orgId, "Charlie Warn");
        requirement(orgId, warn, "COI", ReviewStatus.APPROVED, today.plusDays(10));
        UUID good = vendor(orgId, "Alpha Good");
        requirement(orgId, good, "COI", ReviewStatus.APPROVED, today.plusDays(100));
        UUID bad2 = vendor(orgId, "Delta Bad");
        requirement(orgId, bad2, "COI", ReviewStatus.APPROVED, today.minusDays(1));
        UUID none = vendor(orgId, "Echo NoReqs");
    }

    @Test
    void complianceFilterAndTotalsAreConsistent() throws Exception {
        threeVendors();
        assertThat(names(owner, "compliance=NON_COMPLIANT")).containsExactly("Bravo Bad", "Delta Bad");
        assertThat(names(owner, "compliance=ATTENTION")).containsExactly("Charlie Warn");
        // zero requirements = COMPLIANT
        assertThat(names(owner, "compliance=COMPLIANT")).containsExactly("Alpha Good", "Echo NoReqs");
        JsonNode p = page(owner, "compliance=NON_COMPLIANT&size=1&page=1");
        assertThat(p.get("totalItems").asInt()).isEqualTo(2);
        assertThat(p.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p.get("items").get(0).get("companyName").asString()).isEqualTo("Delta Bad");
        assertThat(names(owner, "compliance=NON_COMPLIANT&q=delta")).containsExactly("Delta Bad");
        owner.client().get(VENDORS + "?compliance=compliant").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("compliance"));
        owner.client().get(VENDORS + "?compliance=BOGUS").andExpect(status().isBadRequest());
    }

    @Test
    void sortByComplianceAndNextExpiration() throws Exception {
        threeVendors();
        assertThat(names(owner, "sort=compliance")).containsExactly("Bravo Bad", "Delta Bad", "Charlie Warn",
                "Alpha Good", "Echo NoReqs");
        assertThat(names(owner, "sort=compliance,desc")).containsExactly("Alpha Good", "Echo NoReqs", "Charlie Warn",
                "Bravo Bad", "Delta Bad");
        // Delta (yesterday) < Charlie (+10) < Alpha (+100); no date (Bravo missing, Echo none) last, ties by id.
        List<String> asc = names(owner, "sort=nextExpiration");
        assertThat(asc.subList(0, 3)).containsExactly("Delta Bad", "Charlie Warn", "Alpha Good");
        assertThat(asc.subList(3, 5)).containsExactlyInAnyOrder("Bravo Bad", "Echo NoReqs");
        List<String> desc = names(owner, "sort=nextExpiration,desc");
        assertThat(desc.subList(0, 3)).containsExactly("Alpha Good", "Charlie Warn", "Delta Bad");
        assertThat(desc.subList(3, 5)).containsExactlyInAnyOrder("Bravo Bad", "Echo NoReqs");
    }

    @Test
    void requirementsOnInactiveTypesAreIgnored() throws Exception {
        UUID v = vendor(orgId, "Inactive Req");
        requirement(orgId, v, "COI", ReviewStatus.APPROVED, today("America/New_York").plusDays(200));
        requirement(orgId, v, "WORKERS_COMP", null, null); // MISSING while the type is active
        assertThat(byName(owner).get("Inactive Req").get("compliance").get("status").asString())
                .isEqualTo("NON_COMPLIANT");

        jdbc.update("update document_type set active = false where organization_id = ?::uuid and code = 'WORKERS_COMP'",
                orgId);
        JsonNode item = byName(owner).get("Inactive Req");
        assertThat(item.get("requirementCount").asInt()).isEqualTo(1);
        assertThat(item.get("compliance").get("status").asString()).isEqualTo("COMPLIANT");
        assertThat(item.get("compliance").get("missing").asInt()).isZero();
    }

    // ---- tenant isolation ----

    @Test
    void complianceNeverCountsAnotherOrganizationsDocuments() throws Exception {
        LocalDate today = today("America/New_York");
        // Org A: vendor requires COI and has no document.
        UUID mine = vendor(orgId, "Shared Name Vendor");
        requirement(orgId, mine, "COI", null, null);
        // Org B: same vendor name and same type code, with a CURRENT approved far-future document.
        UUID theirs = vendor(other.organizationId(), "Shared Name Vendor");
        requirement(other.organizationId(), theirs, "COI", ReviewStatus.APPROVED, today.plusDays(365));

        JsonNode a = byName(owner).get("Shared Name Vendor").get("compliance");
        assertThat(a.get("status").asString()).isEqualTo("NON_COMPLIANT");
        assertThat(a.get("missing").asInt()).isEqualTo(1);
        assertThat(a.get("ok").asInt()).isZero();
        assertThat(a.get("nextExpiration").isNull()).isTrue();

        JsonNode b = byName(other).get("Shared Name Vendor").get("compliance");
        assertThat(b.get("status").asString()).isEqualTo("COMPLIANT");
        assertThat(b.get("nextExpiration").asString()).isEqualTo(today.plusDays(365).toString());
        assertThat(page(owner, "status=ALL").get("totalItems").asInt()).isEqualTo(1);
    }

    // ---- time zone and settings ----

    @Test
    void timeZoneDecidesTodayAndTherefore_TheStatus() throws Exception {
        // Kiritimati (UTC+14) is exactly 24h ahead of Honolulu (UTC-10): at the same instant their dates differ by 1 day.
        LocalDate honolulu = today("Pacific/Honolulu");
        LocalDate kiritimati = today("Pacific/Kiritimati");
        assertThat(kiritimati).isEqualTo(honolulu.plusDays(1));

        setZone(owner, "Pacific/Honolulu");
        setZone(other, "Pacific/Kiritimati");
        UUID a = vendor(orgId, "Tz Vendor");
        requirement(orgId, a, "COI", ReviewStatus.APPROVED, honolulu);
        UUID b = vendor(other.organizationId(), "Tz Vendor");
        requirement(other.organizationId(), b, "COI", ReviewStatus.APPROVED, honolulu);

        JsonNode inHonolulu = byName(owner).get("Tz Vendor").get("compliance");
        assertThat(inHonolulu.get("expiring").asInt()).isEqualTo(1); // expires today
        JsonNode inKiritimati = byName(other).get("Tz Vendor").get("compliance");
        assertThat(inKiritimati.get("expired").asInt()).isEqualTo(1); // expired yesterday

        // Detail uses the same "today" (Java calculator).
        String vendorA = byName(owner).get("Tz Vendor").get("id").asString();
        String vendorB = byName(other).get("Tz Vendor").get("id").asString();
        JsonNode reqA = json.readTree(owner.client().get(VENDORS + "/" + vendorA).andReturn().getResponse()
                .getContentAsString()).get("requirements").get(0);
        JsonNode reqB = json.readTree(other.client().get(VENDORS + "/" + vendorB).andReturn().getResponse()
                .getContentAsString()).get("requirements").get(0);
        assertThat(reqA.get("status").asString()).isEqualTo("EXPIRING");
        assertThat(reqA.get("daysUntilExpiration").asInt()).isZero();
        assertThat(reqB.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(reqB.get("daysUntilExpiration").asInt()).isEqualTo(-1);
    }

    @Test
    void clockAdvanceChangesStatusWithoutAnyStoredState() throws Exception {
        UUID v = vendor(orgId, "Clock Vendor");
        requirement(orgId, v, "COI", ReviewStatus.APPROVED, today("America/New_York").plusDays(1));
        assertThat(byName(owner).get("Clock Vendor").get("compliance").get("expiring").asInt()).isEqualTo(1);
        clock.advance(java.time.Duration.ofDays(2));
        assertThat(byName(owner).get("Clock Vendor").get("compliance").get("expired").asInt()).isEqualTo(1);
    }

    @Test
    void changingTheExpiringWindowChangesStatusesImmediately() throws Exception {
        UUID v = vendor(orgId, "Window Vendor");
        requirement(orgId, v, "COI", ReviewStatus.APPROVED, today("America/New_York").plusDays(20));
        String id = byName(owner).get("Window Vendor").get("id").asString();

        assertThat(byName(owner).get("Window Vendor").get("compliance").get("status").asString())
                .isEqualTo("ATTENTION"); // default window 30
        setWindow(owner, 10);
        assertThat(byName(owner).get("Window Vendor").get("compliance").get("status").asString())
                .isEqualTo("COMPLIANT");
        owner.client().get(VENDORS + "/" + id).andExpect(jsonPath("$.compliance.status").value("COMPLIANT"))
                .andExpect(jsonPath("$.requirements[0].status").value("OK"));
        setWindow(owner, 20);
        owner.client().get(VENDORS + "/" + id).andExpect(jsonPath("$.compliance.status").value("ATTENTION"))
                .andExpect(jsonPath("$.requirements[0].status").value("EXPIRING"));
    }

    // ---- vendor detail ----

    @Test
    void detailShowsStatusDaysAndActiveAndExcludesInactiveRequirementsFromTheSummary() throws Exception {
        LocalDate today = today("America/New_York");
        UUID v = vendor(orgId, "Detail Vendor");
        requirement(orgId, v, "COI", ReviewStatus.APPROVED, today.plusDays(10)); // EXPIRING, 10 days
        requirement(orgId, v, "WORKERS_COMP", null, null); // MISSING
        requirement(orgId, v, "W9", ReviewStatus.APPROVED, null); // OK, no days
        requirement(orgId, v, "BUSINESS_LICENSE", ReviewStatus.APPROVED, today.minusDays(5)); // inactive below
        jdbc.update("update document_type set active = false where organization_id = ?::uuid"
                + " and code = 'BUSINESS_LICENSE'", orgId);

        JsonNode d = json.readTree(owner.client().get(VENDORS + "/" + v).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        Map<String, JsonNode> reqs = new LinkedHashMap<>();
        d.get("requirements").forEach(r -> reqs.put(r.get("code").asString(), r));
        assertThat(reqs.get("COI").get("status").asString()).isEqualTo("EXPIRING");
        assertThat(reqs.get("COI").get("daysUntilExpiration").asInt()).isEqualTo(10);
        assertThat(reqs.get("COI").get("active").asBoolean()).isTrue();
        assertThat(reqs.get("WORKERS_COMP").get("status").asString()).isEqualTo("MISSING");
        assertThat(reqs.get("WORKERS_COMP").get("daysUntilExpiration").isNull()).isTrue();
        assertThat(reqs.get("W9").get("status").asString()).isEqualTo("OK");
        assertThat(reqs.get("W9").get("daysUntilExpiration").isNull()).isTrue();
        assertThat(reqs.get("BUSINESS_LICENSE").get("active").asBoolean()).isFalse();
        assertThat(reqs.get("BUSINESS_LICENSE").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(reqs.get("BUSINESS_LICENSE").get("daysUntilExpiration").asInt()).isEqualTo(-5);

        assertThat(d.get("requirementCount").asInt()).isEqualTo(3);
        JsonNode c = d.get("compliance");
        assertThat(c.get("status").asString()).isEqualTo("NON_COMPLIANT");
        assertThat(c.get("missing").asInt()).isEqualTo(1);
        assertThat(c.get("expired").asInt()).isZero(); // the expired one is on an inactive type
        assertThat(c.get("expiring").asInt()).isEqualTo(1);
        assertThat(c.get("ok").asInt()).isEqualTo(1);
        assertThat(c.get("nextExpiration").asString()).isEqualTo(today.plusDays(10).toString());

        // The list agrees with the detail.
        assertThat(byName(owner).get("Detail Vendor").get("compliance")).isEqualTo(c);
    }

    // ---- N+1 guard ----

    @Test
    void listWithComplianceExecutesABoundedNumberOfStatementsRegardlessOfVendorCount() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        ApiClient client = owner.client();
        LocalDate today = today("America/New_York");

        for (int i = 0; i < 3; i++) {
            populate("Few " + i, today);
        }
        long few = statementsFor(stats, client, "size=100&sort=compliance&compliance=ATTENTION");
        for (int i = 0; i < 30; i++) {
            populate("Many " + i, today);
        }
        client.get(VENDORS + "?size=100").andExpect(jsonPath("$.items.length()").value(33));
        long many = statementsFor(stats, client, "size=100&sort=compliance&compliance=ATTENTION");

        assertThat(many).as("statements with 33 vendors vs 3 vendors").isEqualTo(few);
        // tenant lookup + organization settings + page + count
        assertThat(many).isLessThanOrEqualTo(5);
    }

    private void populate(String name, LocalDate today) {
        UUID v = vendor(orgId, name);
        requirement(orgId, v, "COI", ReviewStatus.APPROVED, today.plusDays(5));
        requirement(orgId, v, "WORKERS_COMP", ReviewStatus.PENDING, today.plusDays(50));
        requirement(orgId, v, "W9", null, null);
    }

    private long statementsFor(Statistics stats, ApiClient client, String query) throws Exception {
        stats.clear();
        client.get(VENDORS + "?" + query).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }
}
