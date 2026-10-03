package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.TestAccounts.Account;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Document-type management: roles, uniqueness, code generation, partial updates, tenant isolation. */
class DocumentTypeManagementTest extends DocumentTestBase {

    static final String TYPES = "/api/v1/document-types";

    static Map<String, Object> create(String name, boolean hasExpiration, boolean requiredByDefault) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("hasExpiration", hasExpiration);
        m.put("requiredByDefault", requiredByDefault);
        return m;
    }

    JsonNode createType(Account who, String name, boolean hasExpiration) throws Exception {
        return json.readTree(who.client().post(TYPES, create(name, hasExpiration, false))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    JsonNode list(Account who, String query) throws Exception {
        return json.readTree(who.client().get(TYPES + query).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    // ---- list ----

    @Test
    void defaultListShowsActiveTypesToEveryRoleWithoutTheActiveFlag() throws Exception {
        for (Account who : List.of(owner, admin, member, viewer)) {
            JsonNode types = list(who, "");
            assertThat(types).hasSize(8);
            assertThat(types.get(0).has("active")).isFalse();
        }
    }

    @Test
    void includeInactiveNeedsRequirementsManageAndReturnsTheActiveFlag() throws Exception {
        String contract = typeId(owner, "CONTRACT");
        owner.client().patch(TYPES + "/" + contract, Map.of("active", false)).andExpect(status().isOk());

        viewer.client().get(TYPES + "?includeInactive=true").andExpect(status().isForbidden());
        member.client().get(TYPES + "?includeInactive=true").andExpect(status().isForbidden());
        accounts.newClient().get(TYPES + "?includeInactive=true").andExpect(status().isUnauthorized());
        for (Account who : List.of(admin, owner)) {
            JsonNode all = list(who, "?includeInactive=true");
            assertThat(all).hasSize(8);
            assertThat(all).extracting(n -> n.get("active").asBoolean()).containsOnly(true, false);
            assertThat(all).filteredOn(n -> n.get("code").asString().equals("CONTRACT"))
                    .extracting(n -> n.get("active").asBoolean()).containsExactly(false);
        }
        // the default list hides it; includeInactive=false is the default
        assertThat(list(member, "")).hasSize(7);
        assertThat(list(member, "?includeInactive=false")).hasSize(7);
        // another organization's list is untouched
        assertThat(list(other, "")).hasSize(8);
    }

    // ---- create ----

    @Test
    void adminCreatesACustomTypeWithAGeneratedCodeAndTheEndSortOrder() throws Exception {
        owner.client().post(TYPES, create("  Pool Safety Certificate  ", true, true)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Pool Safety Certificate"))
                .andExpect(jsonPath("$.code").value("CUSTOM_POOL_SAFETY_CERTIFICATE"))
                .andExpect(jsonPath("$.hasExpiration").value(true))
                .andExpect(jsonPath("$.requiredByDefault").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sortOrder").value(90));
        JsonNode second = createType(admin, "Elevator Inspection", false);
        assertThat(second.get("sortOrder").asInt()).isEqualTo(100);
        assertThat(second.get("id").asString()).isNotBlank();
        // it shows up at the end of the picker list
        JsonNode types = list(member, "");
        assertThat(types).hasSize(10);
        assertThat(types.get(9).get("name").asString()).isEqualTo("Elevator Inspection");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'document_type.created' and "
                + "entity_id = ?::uuid and organization_id = ?::uuid", Integer.class, second.get("id").asString(),
                orgId)).isEqualTo(1);
    }

    @Test
    void clientCannotChooseCodeSortOrActive() throws Exception {
        Map<String, Object> body = create("Sneaky", false, false);
        body.put("code", "COI");
        body.put("sortOrder", 1);
        body.put("active", false);
        body.put("organizationId", other.organizationId());
        owner.client().post(TYPES, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CUSTOM_SNEAKY")).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sortOrder").value(90));
        assertThat(list(other, "")).hasSize(8);
    }

    @Test
    void codesStayUniqueWhenSlugsCollideAndAreAsciiAndShort() throws Exception {
        JsonNode a = createType(owner, "Pool Cert", false);
        JsonNode b = createType(owner, "Pool-Cert", false);
        JsonNode c = createType(owner, "Pool  cert!", false);
        assertThat(a.get("code").asString()).isEqualTo("CUSTOM_POOL_CERT");
        assertThat(List.of(b.get("code").asString(), c.get("code").asString()))
                .allMatch(code -> code.matches("CUSTOM_POOL_CERT_[0-9A-F]{4}"))
                .doesNotHaveDuplicates();
        assertThat(createType(owner, "Seguro de día", false).get("code").asString())
                .isEqualTo("CUSTOM_SEGURO_DE_DIA");
        assertThat(createType(owner, "“!!!”", false).get("code").asString()).isEqualTo("CUSTOM_TYPE");
        String longCode = createType(owner, "x".repeat(100), false).get("code").asString();
        assertThat(longCode.length()).isLessThanOrEqualTo(50);
    }

    @Test
    void nameIsUniquePerOrganizationCaseInsensitivelyIncludingInactiveAndDefaults() throws Exception {
        createType(owner, "Fire Alarm Report", true);
        for (String dup : List.of("fire alarm report", "FIRE ALARM REPORT", "  Fire Alarm Report  ")) {
            owner.client().post(TYPES, create(dup, true, false)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Document type already exists"));
        }
        owner.client().post(TYPES, create("w 9", false, false)).andExpect(status().isCreated());
        owner.client().post(TYPES, create("W-9", false, false)).andExpect(status().isConflict()); // default type
        // inactive types still reserve their name
        owner.client().patch(TYPES + "/" + typeId(owner, "CONTRACT"), Map.of("active", false)).andExpect(status().isOk());
        owner.client().post(TYPES, create("contract", false, false)).andExpect(status().isConflict());
        // another organization may use the same names
        other.client().post(TYPES, create("Fire Alarm Report", true, false)).andExpect(status().isCreated());
    }

    @Test
    void concurrentCreatesOfTheSameNameYieldOne201AndTheRest409() throws Exception {
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                var client = owner.client().copy();
                results.add(pool.submit(() -> {
                    start.await();
                    return client.post(TYPES, create("Race Type", false, false)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createValidatesTheBody() throws Exception {
        owner.client().post(TYPES, create("", true, true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.client().post(TYPES, create("   ", true, true)).andExpect(status().isBadRequest());
        owner.client().post(TYPES, create("x".repeat(101), true, true)).andExpect(status().isBadRequest());
        owner.client().post(TYPES, create("bad‮name", true, true)).andExpect(status().isBadRequest());
        owner.client().post(TYPES, create("line\nbreak", true, true)).andExpect(status().isBadRequest());
        Map<String, Object> noFlags = new HashMap<>();
        noFlags.put("name", "Valid name");
        owner.client().post(TYPES, noFlags).andExpect(status().isBadRequest());
        owner.client().post(TYPES, create("x".repeat(100), true, true)).andExpect(status().isCreated());
    }

    @Test
    void memberAndViewerCannotCreateOrUpdateTypes() throws Exception {
        for (Account who : List.of(member, viewer)) {
            who.client().post(TYPES, create("Not allowed", true, true)).andExpect(status().isForbidden());
            who.client().patch(TYPES + "/" + typeId(owner, "COI"), Map.of("name", "Hacked"))
                    .andExpect(status().isForbidden());
        }
        accounts.newClient().post(TYPES, create("Anon", true, true)).andExpect(status().isUnauthorized());
        assertThat(list(owner, "")).hasSize(8);
        assertThat(list(owner, "").get(0).get("name").asString()).isEqualTo("Certificate of Insurance");
    }

    // ---- update ----

    @Test
    void patchUpdatesOnlyTheSentFieldsAndNeverTheCode() throws Exception {
        String coi = typeId(owner, "COI");
        admin.client().patch(TYPES + "/" + coi, Map.of("name", "Insurance Certificate", "sortOrder", 5,
                "code", "HACKED", "organizationId", other.organizationId(), "id", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(coi))
                .andExpect(jsonPath("$.code").value("COI"))
                .andExpect(jsonPath("$.name").value("Insurance Certificate"))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andExpect(jsonPath("$.hasExpiration").value(true))
                .andExpect(jsonPath("$.requiredByDefault").value(true))
                .andExpect(jsonPath("$.active").value(true));
        admin.client().patch(TYPES + "/" + coi, Map.of("hasExpiration", false, "requiredByDefault", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasExpiration").value(false))
                .andExpect(jsonPath("$.requiredByDefault").value(false))
                .andExpect(jsonPath("$.name").value("Insurance Certificate"));
        assertThat(jdbc.queryForObject("select code from document_type where id = ?::uuid", String.class, coi))
                .isEqualTo("COI");
        assertThat(jdbc.queryForObject("select metadata->'changes'->'name'->>'after' from audit_event where action = "
                + "'document_type.updated' and entity_id = ?::uuid order by created_at limit 1", String.class, coi))
                .isEqualTo("Insurance Certificate");
        // reordering takes effect in the picker
        assertThat(list(member, "").get(0).get("code").asString()).isEqualTo("COI");
    }

    @Test
    void deactivatedTypeDisappearsFromPickersAndCanBeReactivated() throws Exception {
        String w9 = typeId(owner, "W9");
        owner.client().patch(TYPES + "/" + w9, Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(list(viewer, "")).extracting(n -> n.get("code").asString()).doesNotContain("W9");
        owner.client().patch(TYPES + "/" + w9, Map.of("active", true)).andExpect(status().isOk());
        assertThat(list(viewer, "")).extracting(n -> n.get("code").asString()).contains("W9");
    }

    @Test
    void renameToAnExistingNameIs409ButCaseOnlyChangeOfOwnNameIsFine() throws Exception {
        JsonNode a = createType(owner, "Alpha Type", false);
        createType(owner, "Beta Type", false);
        owner.client().patch(TYPES + "/" + a.get("id").asString(), Map.of("name", "beta type"))
                .andExpect(status().isConflict());
        owner.client().patch(TYPES + "/" + a.get("id").asString(), Map.of("name", "ALPHA TYPE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("ALPHA TYPE"));
    }

    @Test
    void patchValidatesAndNoOpWritesNoAudit() throws Exception {
        String coi = typeId(owner, "COI");
        owner.client().patch(TYPES + "/" + coi, Map.of("name", "")).andExpect(status().isBadRequest());
        owner.client().patch(TYPES + "/" + coi, Map.of("name", "x".repeat(101))).andExpect(status().isBadRequest());
        owner.client().patch(TYPES + "/" + coi, Map.of("sortOrder", -1)).andExpect(status().isBadRequest());
        owner.client().patch(TYPES + "/" + coi, Map.of("sortOrder", 100001)).andExpect(status().isBadRequest());
        owner.client().patch(TYPES + "/" + coi, Map.of("active", "maybe")).andExpect(status().isBadRequest());
        owner.client().patch(TYPES + "/" + coi, Map.of("name", "Certificate of Insurance", "active", true))
                .andExpect(status().isOk());
        owner.client().patch(TYPES + "/" + coi, Map.of()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'document_type.updated' and "
                + "entity_id = ?::uuid", Integer.class, coi)).isZero();
    }

    @Test
    void userOfAnotherOrganizationGets404WhenPatchingOurTypeAndNothingChanges() throws Exception {
        String coi = typeId(owner, "COI");
        other.client().patch(TYPES + "/" + coi, Map.of("name", "Stolen", "active", false))
                .andExpect(status().isNotFound());
        other.client().patch(TYPES + "/00000000-0000-0000-0000-000000000000", Map.of("name", "x"))
                .andExpect(status().isNotFound());
        owner.client().patch(TYPES + "/not-a-uuid", Map.of("name", "x")).andExpect(status().isBadRequest());
        JsonNode types = list(owner, "?includeInactive=true");
        assertThat(types).filteredOn(n -> n.get("code").asString().equals("COI"))
                .extracting(n -> n.get("name").asString()).containsExactly("Certificate of Insurance");
    }

    // ---- integration with vendors and documents ----

    @Test
    void aCustomTypeCanBeRequiredByVendorsAndUsedForUploads() throws Exception {
        JsonNode custom = createType(owner, "Elevator Permit", true);
        String id = custom.get("id").asString();
        String vendor = createVendor(admin, "Custom");
        admin.client().put(VENDORS + "/" + vendor + "/requirements", Map.of("documentTypeIds", List.of(id)))
                .andExpect(status().isOk());
        uploadOk(member, vendor, file("permit.pdf", PDF), fields(id, "2026-01-01", "2027-01-01"));
        JsonNode requirement = vendor(viewer, vendor).get("requirements").get(0);
        assertThat(requirement.get("code").asString()).isEqualTo("CUSTOM_ELEVATOR_PERMIT");
        assertThat(requirement.get("currentDocument").get("originalFilename").asString()).isEqualTo("permit.pdf");
        // requiredByDefault applies to NEW vendors of this organization only
        owner.client().patch(TYPES + "/" + id, Map.of("requiredByDefault", true)).andExpect(status().isOk());
        JsonNode next = vendor(owner, createVendor(owner, "Next"));
        assertThat(next.get("requirements")).extracting(n -> n.get("code").asString())
                .contains("CUSTOM_ELEVATOR_PERMIT");
    }
}
