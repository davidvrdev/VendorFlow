package com.vendorflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.compliance.application.ComplianceContextService;
import com.vendorflow.document.domain.ReviewStatus;
import com.vendorflow.support.ComplianceFixtures;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DashboardTest extends IntegrationTest {

    static final String SUMMARY = "/api/v1/dashboard/summary";
    static final String ATTENTION = "/api/v1/dashboard/attention";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ComplianceContextService contexts;

    ComplianceFixtures fx;
    TestAccounts accounts;
    Account owner;
    Account other;
    String org;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        fx = new ComplianceFixtures(jdbc);
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Dash Org " + UUID.randomUUID());
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Dash Other " + UUID.randomUUID());
        org = owner.organizationId();
        today = contexts.todayIn("America/New_York");
    }

    JsonNode get(Account who, String path) throws Exception {
        return json.readTree(who.client().get(path).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    /** Sorted-by-id helper: Postgres uuid order equals the lowercase-hex string order. */
    static List<UUID> byId(UUID... ids) {
        List<UUID> out = new ArrayList<>(List.of(ids));
        out.sort(Comparator.comparing(UUID::toString));
        return out;
    }

    String key(UUID vendor, String typeCode) {
        return vendor + "|" + fx.typeId(org, typeCode);
    }

    List<String> keys(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("vendorId").asString() + "|" + i.get("documentTypeId").asString()));
        return out;
    }

    // ---- summary ----

    @Test
    void summaryCountsActiveVendorsOnlyAndInvariantsHold() throws Exception {
        UUID expiring = fx.vendor(org, "A Expiring");
        fx.requirement(org, expiring, "COI", ReviewStatus.APPROVED, today.plusDays(5));
        fx.requirement(org, expiring, "W9", ReviewStatus.APPROVED, null);
        UUID none = fx.vendor(org, "B No Requirements");
        UUID missing = fx.vendor(org, "C Missing");
        fx.require(org, missing, "W9");
        UUID expired = fx.vendor(org, "D Expired");
        fx.requirement(org, expired, "COI", ReviewStatus.APPROVED, today.minusDays(1));
        UUID ok = fx.vendor(org, "E Ok");
        fx.requirement(org, ok, "COI", ReviewStatus.APPROVED, today.plusDays(300));
        UUID review = fx.vendor(org, "F Review");
        fx.requirement(org, review, "W9", ReviewStatus.PENDING, null);
        // Excluded: inactive vendor (non compliant), requirement on an inactive type.
        UUID inactive = fx.vendor(org, "G Inactive", "INACTIVE");
        fx.require(org, inactive, "W9");
        fx.requirement(org, inactive, "COI", ReviewStatus.APPROVED, today.minusDays(9));
        UUID onInactiveType = fx.vendor(org, "H Inactive Type");
        fx.require(org, onInactiveType, "CONTRACT");
        jdbc.update("update document_type set active = false where organization_id = ?::uuid and code = 'CONTRACT'", org);
        assertThat(none).isNotNull();

        JsonNode s = get(owner, SUMMARY);
        assertThat(s.get("today").asString()).isEqualTo(today.toString());
        assertThat(s.get("expiringWindowDays").asInt()).isEqualTo(30);
        JsonNode v = s.get("vendors");
        assertThat(v.get("active").asInt()).isEqualTo(7);
        assertThat(v.get("compliant").asInt()).isEqualTo(3); // B, E, H
        assertThat(v.get("attention").asInt()).isEqualTo(2); // A, F
        assertThat(v.get("nonCompliant").asInt()).isEqualTo(2); // C, D
        assertThat(v.get("noRequirements").asInt()).isEqualTo(2); // B, H
        assertThat(v.get("compliant").asInt() + v.get("attention").asInt() + v.get("nonCompliant").asInt())
                .isEqualTo(v.get("active").asInt());
        JsonNode d = s.get("documents");
        assertThat(d.get("missing").asInt()).isEqualTo(1);
        assertThat(d.get("expired").asInt()).isEqualTo(1);
        assertThat(d.get("expiring").asInt()).isEqualTo(1);
        assertThat(d.get("reviewRequired").asInt()).isEqualTo(1);

        // Attention agrees: 4 items, none from the inactive vendor / inactive type.
        JsonNode a = get(owner, ATTENTION);
        assertThat(a.get("totalItems").asInt()).isEqualTo(4);
        Set<String> vendors = new HashSet<>();
        a.get("items").forEach(i -> vendors.add(i.get("vendorName").asString()));
        assertThat(vendors).containsExactlyInAnyOrder("A Expiring", "C Missing", "D Expired", "F Review");
    }

    @Test
    void emptyOrganizationHasZeroEverything() throws Exception {
        JsonNode s = get(owner, SUMMARY);
        assertThat(s.get("vendors").get("active").asInt()).isZero();
        assertThat(s.get("vendors").get("compliant").asInt()).isZero();
        assertThat(s.get("documents").get("missing").asInt()).isZero();
        JsonNode a = get(owner, ATTENTION);
        assertThat(a.get("items")).isEmpty();
        assertThat(a.get("totalItems").asInt()).isZero();
    }

    @Test
    void attentionItemsCarryTheVendorEmailOrNull() throws Exception {
        UUID withEmail = fx.vendor(org, "With Email");
        fx.require(org, withEmail, "W9");
        jdbc.update("update vendor set email = 'contact@withemail.example' where id = ?", withEmail);
        UUID noEmail = fx.vendor(org, "No Email");
        fx.require(org, noEmail, "W9");
        Map<String, JsonNode> items = new java.util.HashMap<>();
        get(owner, ATTENTION).get("items").forEach(i -> items.put(i.get("vendorName").asString(), i));
        assertThat(items.get("With Email").get("vendorEmail").asString()).isEqualTo("contact@withemail.example");
        assertThat(items.get("No Email").has("vendorEmail")).isTrue();
        assertThat(items.get("No Email").get("vendorEmail").isNull()).isTrue();
    }

    @Test
    void attentionItemsCarryDocumentDatesAndActions() throws Exception {
        UUID expired = fx.vendor(org, "V Expired");
        fx.requirement(org, expired, "COI", ReviewStatus.APPROVED, today.minusDays(4));
        UUID expiring = fx.vendor(org, "V Expiring");
        fx.requirement(org, expiring, "COI", ReviewStatus.APPROVED, today.plusDays(7));
        UUID review = fx.vendor(org, "V Review");
        fx.requirement(org, review, "W9", ReviewStatus.PENDING, null);
        UUID missing = fx.vendor(org, "V Missing");
        fx.require(org, missing, "COI");
        UUID rejected = fx.vendor(org, "V Rejected");
        fx.requirement(org, rejected, "COI", ReviewStatus.REJECTED, today.plusDays(100));

        Map<String, JsonNode> items = new java.util.HashMap<>();
        get(owner, ATTENTION).get("items").forEach(i -> items.put(i.get("vendorName").asString(), i));
        JsonNode e = items.get("V Expired");
        assertThat(e.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(e.get("action").asString()).isEqualTo("UPLOAD");
        assertThat(e.get("daysUntilExpiration").asInt()).isEqualTo(-4);
        assertThat(e.get("expirationDate").asString()).isEqualTo(today.minusDays(4).toString());
        assertThat(e.get("documentId").isNull()).isFalse();
        assertThat(e.get("documentTypeName").asString()).isEqualTo("Certificate of Insurance");
        JsonNode x = items.get("V Expiring");
        assertThat(x.get("action").asString()).isEqualTo("UPLOAD_RENEWAL");
        assertThat(x.get("daysUntilExpiration").asInt()).isEqualTo(7);
        JsonNode r = items.get("V Review");
        assertThat(r.get("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(r.get("action").asString()).isEqualTo("REVIEW");
        assertThat(r.get("expirationDate").isNull()).isTrue();
        assertThat(r.get("daysUntilExpiration").isNull()).isTrue();
        JsonNode m = items.get("V Missing");
        assertThat(m.get("status").asString()).isEqualTo("MISSING");
        assertThat(m.get("documentId").isNull()).isTrue();
        assertThat(m.get("expirationDate").isNull()).isTrue();
        JsonNode j = items.get("V Rejected");
        assertThat(j.get("status").asString()).isEqualTo("MISSING");
        assertThat(j.get("action").asString()).isEqualTo("UPLOAD");
        assertThat(j.get("documentId").isNull()).isFalse(); // the rejected CURRENT document
        assertThat(j.get("expirationDate").isNull()).isTrue();
        assertThat(j.get("daysUntilExpiration").isNull()).isTrue();
    }

    // ---- ordering & paging ----

    /** Builds the crafted dataset and returns the expected key order (every tie-break of the contract). */
    List<String> craftedDataset() {
        List<String> expected = new ArrayList<>();
        Instant base = Instant.parse("2020-01-01T00:00:00Z");

        // EXPIRED: most overdue first; same date -> vendor id; same vendor+date -> type id.
        UUID multi = fx.vendor(org, "Multi Exp");
        fx.requirement(org, multi, "COI", ReviewStatus.APPROVED, today.minusDays(20));
        fx.requirement(org, multi, "GENERAL_LIABILITY", ReviewStatus.APPROVED, today.minusDays(20));
        UUID zed = fx.vendor(org, "Zed Exp");
        fx.requirement(org, zed, "COI", ReviewStatus.PENDING, today.minusDays(10)); // pending but expired = EXPIRED
        UUID twinE1 = fx.vendor(org, "Twin Exp A");
        UUID twinE2 = fx.vendor(org, "Twin Exp B");
        fx.requirement(org, twinE1, "COI", ReviewStatus.APPROVED, today.minusDays(5));
        fx.requirement(org, twinE2, "COI", ReviewStatus.APPROVED, today.minusDays(5));
        UUID alphaE = fx.vendor(org, "Alpha Exp");
        fx.requirement(org, alphaE, "BUSINESS_LICENSE", ReviewStatus.APPROVED, today.minusDays(1));
        List<String> multiKeys = new ArrayList<>(List.of(key(multi, "COI"), key(multi, "GENERAL_LIABILITY")));
        multiKeys.sort(Comparator.comparing(k -> k.substring(k.indexOf('|') + 1)));
        expected.addAll(multiKeys);
        expected.add(key(zed, "COI"));
        for (UUID id : byId(twinE1, twinE2)) {
            expected.add(key(id, "COI"));
        }
        expected.add(key(alphaE, "BUSINESS_LICENSE"));

        // MISSING: vendor name (case-insensitive), then type sortOrder, then ids. A rejected document is MISSING too.
        UUID bravo = fx.vendor(org, "bravo Missing");
        // CONTRACT and OTHER share sortOrder 80 (type id decides); COI is 10. Inserted last on purpose.
        jdbc.update("update document_type set sort_order = 80 where organization_id = ?::uuid and code = 'CONTRACT'", org);
        fx.require(org, bravo, "CONTRACT");
        fx.require(org, bravo, "OTHER");
        fx.require(org, bravo, "COI");
        UUID alphaM = fx.vendor(org, "Alpha Missing");
        fx.require(org, alphaM, "W9");
        UUID twinM1 = fx.vendor(org, "Twin Missing A");
        UUID twinM2 = fx.vendor(org, "Twin Missing B");
        fx.require(org, twinM1, "W9");
        fx.require(org, twinM2, "W9");
        UUID rejected = fx.vendor(org, "Charlie Rejected");
        fx.requirement(org, rejected, "W9", ReviewStatus.REJECTED, null);
        expected.add(key(alphaM, "W9"));
        expected.add(key(bravo, "COI"));
        List<String> tied = new ArrayList<>(List.of(key(bravo, "CONTRACT"), key(bravo, "OTHER")));
        tied.sort(Comparator.comparing(k -> k.substring(k.indexOf('|') + 1)));
        expected.addAll(tied);
        expected.add(key(rejected, "W9"));
        expected.add(key(twinM1, "W9"));
        expected.add(key(twinM2, "W9"));

        // EXPIRING: soonest first (today = 0 days left); ties -> vendor id.
        UUID soon0 = fx.vendor(org, "Soon Zero");
        fx.requirement(org, soon0, "COI", ReviewStatus.APPROVED, today);
        UUID soon2 = fx.vendor(org, "Soon Two");
        fx.requirement(org, soon2, "COI", ReviewStatus.APPROVED, today.plusDays(2));
        UUID soonT1 = fx.vendor(org, "Soon Twin A");
        UUID soonT2 = fx.vendor(org, "Soon Twin B");
        fx.requirement(org, soonT1, "COI", ReviewStatus.APPROVED, today.plusDays(5));
        fx.requirement(org, soonT2, "COI", ReviewStatus.APPROVED, today.plusDays(5));
        expected.add(key(soon0, "COI"));
        expected.add(key(soon2, "COI"));
        for (UUID id : byId(soonT1, soonT2)) {
            expected.add(key(id, "COI"));
        }

        // REVIEW_REQUIRED: oldest upload first; ties -> vendor id.
        UUID revOld = fx.vendor(org, "Zulu Review Old");
        fx.requirement(org, revOld, "W9", ReviewStatus.PENDING, null, base);
        UUID revT1 = fx.vendor(org, "Review Twin A");
        UUID revT2 = fx.vendor(org, "Review Twin B");
        fx.requirement(org, revT1, "W9", ReviewStatus.PENDING, null, base.plusSeconds(86_400));
        fx.requirement(org, revT2, "W9", ReviewStatus.PENDING, null, base.plusSeconds(86_400));
        UUID revNew = fx.vendor(org, "Alpha Review New");
        fx.requirement(org, revNew, "W9", ReviewStatus.PENDING, null, base.plusSeconds(86_400L * 30));
        expected.add(key(revOld, "W9"));
        for (UUID id : byId(revT1, revT2)) {
            expected.add(key(id, "W9"));
        }
        expected.add(key(revNew, "W9"));

        // Never listed: OK requirement, inactive vendor.
        UUID ok = fx.vendor(org, "Fine Co");
        fx.requirement(org, ok, "COI", ReviewStatus.APPROVED, today.plusDays(200));
        UUID inactive = fx.vendor(org, "Inactive Co", "INACTIVE");
        fx.requirement(org, inactive, "COI", ReviewStatus.APPROVED, today.minusDays(50));
        return expected;
    }

    @Test
    void attentionOrderFollowsTheContractIncludingEveryTieBreak() throws Exception {
        List<String> expected = craftedDataset();
        JsonNode all = get(owner, ATTENTION + "?size=100");
        assertThat(all.get("totalItems").asInt()).isEqualTo(expected.size());
        assertThat(keys(all)).containsExactlyElementsOf(expected);
    }

    @Test
    void pagingIsStableAndSizeIsClamped() throws Exception {
        List<String> expected = craftedDataset();
        List<String> paged = new ArrayList<>();
        int pages = (expected.size() + 2) / 3;
        for (int p = 0; p < pages; p++) {
            JsonNode page = get(owner, ATTENTION + "?size=3&page=" + p);
            assertThat(page.get("totalItems").asInt()).isEqualTo(expected.size());
            assertThat(page.get("totalPages").asInt()).isEqualTo(pages);
            paged.addAll(keys(page));
        }
        assertThat(paged).containsExactlyElementsOf(expected);
        assertThat(get(owner, ATTENTION + "?size=3&page=" + pages).get("items")).isEmpty();

        JsonNode defaults = get(owner, ATTENTION);
        assertThat(defaults.get("size").asInt()).isEqualTo(10);
        assertThat(keys(defaults)).containsExactlyElementsOf(expected.subList(0, 10));
        assertThat(get(owner, ATTENTION + "?size=1000").get("size").asInt()).isEqualTo(100);
        assertThat(get(owner, ATTENTION + "?size=0").get("size").asInt()).isEqualTo(1);
        assertThat(get(owner, ATTENTION + "?page=-3").get("page").asInt()).isZero();
    }

    // ---- permissions ----

    @Test
    void everyRoleCanReadAndAnonymousIsRejected() throws Exception {
        UUID v = fx.vendor(org, "Perm Vendor");
        fx.require(org, v, "W9");
        for (String role : List.of("ADMIN", "MEMBER", "VIEWER")) {
            Account a = accounts.memberOf(org, role, role + " Person");
            assertThat(get(a, SUMMARY).get("vendors").get("active").asInt()).as(role).isEqualTo(1);
            assertThat(get(a, ATTENTION).get("totalItems").asInt()).as(role).isEqualTo(1);
        }
        assertThat(get(owner, SUMMARY).get("vendors").get("active").asInt()).isEqualTo(1);

        var anonymous = accounts.newClient();
        anonymous.get(SUMMARY).andExpect(status().isUnauthorized());
        anonymous.get(ATTENTION).andExpect(status().isUnauthorized());
    }

    @Test
    void noActiveOrganizationIsForbidden() throws Exception {
        Account leaver = accounts.memberOf(org, "VIEWER", "Leaver Person");
        jdbc.update("delete from membership where organization_id = ?::uuid and user_id = ?::uuid", org,
                leaver.userId());
        leaver.client().get(SUMMARY).andExpect(status().isForbidden());
        leaver.client().get(ATTENTION).andExpect(status().isForbidden());
    }

    // ---- tenant isolation ----

    @Test
    void anotherOrganizationNeverLeaksIntoCountsOrItems() throws Exception {
        UUID mine = fx.vendor(org, "Mine");
        fx.require(org, mine, "W9");
        String otherOrg = other.organizationId();
        List<UUID> theirs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID v = fx.vendor(otherOrg, "Theirs " + i);
            fx.requirement(otherOrg, v, "COI", ReviewStatus.APPROVED, today.minusDays(i + 1));
            fx.require(otherOrg, v, "W9");
            theirs.add(v);
        }
        JsonNode s = get(owner, SUMMARY);
        assertThat(s.get("vendors").get("active").asInt()).isEqualTo(1);
        assertThat(s.get("vendors").get("nonCompliant").asInt()).isEqualTo(1);
        assertThat(s.get("documents").get("expired").asInt()).isZero();
        assertThat(s.get("documents").get("missing").asInt()).isEqualTo(1);
        JsonNode a = get(owner, ATTENTION + "?size=100");
        assertThat(a.get("totalItems").asInt()).isEqualTo(1);
        assertThat(a.get("items").get(0).get("vendorId").asString()).isEqualTo(mine.toString());

        JsonNode theirSummary = get(other, SUMMARY);
        assertThat(theirSummary.get("vendors").get("active").asInt()).isEqualTo(12);
        assertThat(theirSummary.get("documents").get("expired").asInt()).isEqualTo(12);
        for (JsonNode i : get(other, ATTENTION + "?size=100").get("items")) {
            assertThat(i.get("vendorId").asString()).isNotEqualTo(mine.toString());
        }
    }

    // ---- time zone ----

    @Test
    void timeZoneDecidesTodayAndTherefore_TheCounts() throws Exception {
        // Kiritimati (UTC+14) is exactly 24h ahead of Honolulu (UTC-10): at the same instant their dates differ.
        LocalDate honolulu = contexts.todayIn("Pacific/Honolulu");
        LocalDate kiritimati = contexts.todayIn("Pacific/Kiritimati");
        assertThat(kiritimati).isEqualTo(honolulu.plusDays(1));
        owner.client().patch("/api/v1/organization", Map.of("timeZone", "Pacific/Honolulu")).andExpect(status().isOk());
        other.client().patch("/api/v1/organization", Map.of("timeZone", "Pacific/Kiritimati"))
                .andExpect(status().isOk());
        UUID a = fx.vendor(org, "Tz Vendor");
        fx.requirement(org, a, "COI", ReviewStatus.APPROVED, honolulu);
        UUID b = fx.vendor(other.organizationId(), "Tz Vendor");
        fx.requirement(other.organizationId(), b, "COI", ReviewStatus.APPROVED, honolulu);

        JsonNode inHonolulu = get(owner, SUMMARY);
        assertThat(inHonolulu.get("today").asString()).isEqualTo(honolulu.toString());
        assertThat(inHonolulu.get("documents").get("expiring").asInt()).isEqualTo(1);
        assertThat(inHonolulu.get("documents").get("expired").asInt()).isZero();
        JsonNode inKiritimati = get(other, SUMMARY);
        assertThat(inKiritimati.get("today").asString()).isEqualTo(kiritimati.toString());
        assertThat(inKiritimati.get("documents").get("expired").asInt()).isEqualTo(1);
        assertThat(inKiritimati.get("documents").get("expiring").asInt()).isZero();
        assertThat(get(other, ATTENTION).get("items").get(0).get("daysUntilExpiration").asInt()).isEqualTo(-1);
        assertThat(get(owner, ATTENTION).get("items").get(0).get("daysUntilExpiration").asInt()).isZero();
    }

    @Test
    void windowSettingAppliesImmediately() throws Exception {
        UUID v = fx.vendor(org, "Window Vendor");
        fx.requirement(org, v, "COI", ReviewStatus.APPROVED, today.plusDays(20));
        assertThat(get(owner, SUMMARY).get("documents").get("expiring").asInt()).isEqualTo(1);
        owner.client().patch("/api/v1/organization", Map.of("expiringWindowDays", 10)).andExpect(status().isOk());
        JsonNode s = get(owner, SUMMARY);
        assertThat(s.get("expiringWindowDays").asInt()).isEqualTo(10);
        assertThat(s.get("documents").get("expiring").asInt()).isZero();
        assertThat(s.get("vendors").get("compliant").asInt()).isEqualTo(1);
    }
}
