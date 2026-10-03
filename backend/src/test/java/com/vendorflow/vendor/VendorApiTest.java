package com.vendorflow.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CRUD, validation, duplicates, permissions, requirements and history of vendors (list/search: VendorListTest). */
class VendorApiTest extends IntegrationTest {

    static final String VENDORS = "/api/v1/vendors";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;
    Account owner;
    Account admin;
    Account member;
    Account viewer;
    Account other; // owner of an unrelated organization
    String orgId;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new TestAccounts(mvc, json, jdbc);
        owner = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Dora Owner",
                "Vendors Org " + UUID.randomUUID());
        orgId = owner.organizationId();
        admin = accounts.memberOf(orgId, "ADMIN", "Adam Admin");
        member = accounts.memberOf(orgId, "MEMBER", "Mia Member");
        viewer = accounts.memberOf(orgId, "VIEWER", "Vic Viewer");
        other = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD, "Olga Other",
                "Other Org " + UUID.randomUUID());
    }

    // ---- helpers ----

    static Map<String, Object> body(String companyName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("companyName", companyName);
        return m;
    }

    JsonNode create(Account who, String companyName) throws Exception {
        return json.readTree(who.client().post(VENDORS, body(companyName)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    String createId(Account who, String companyName) throws Exception {
        return create(who, companyName).get("id").asString();
    }

    JsonNode read(Account who, String id) throws Exception {
        return json.readTree(who.client().get(VENDORS + "/" + id).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    String typeId(Account who, String code) throws Exception {
        JsonNode types = json.readTree(who.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString());
        for (JsonNode t : types) {
            if (t.get("code").asString().equals(code)) {
                return t.get("id").asString();
            }
        }
        throw new AssertionError("no type " + code);
    }

    int auditCount(String vendorId, String action) {
        return jdbc.queryForObject("select count(*) from audit_event where entity_type = 'vendor' "
                + "and entity_id = ?::uuid and action = ?", Integer.class, vendorId, action);
    }

    List<String> requirementCodes(JsonNode vendor) {
        List<String> codes = new ArrayList<>();
        vendor.get("requirements").forEach(r -> codes.add(r.get("code").asString()));
        return codes;
    }

    // ---- create / read ----

    @Test
    void createReturnsDetailWithDefaultRequirementsAndCreator() throws Exception {
        Map<String, Object> b = body("  Acme Plumbing  ");
        b.put("contactName", "Pat Plumber");
        b.put("email", "pat@acme.example");
        b.put("phone", "+1 (555) 010-2000 ext 4");
        b.put("category", "Plumbing");
        b.put("notes", "Line one\nLine two");
        owner.client().post(VENDORS, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyName").value("Acme Plumbing"))
                .andExpect(jsonPath("$.contactName").value("Pat Plumber"))
                .andExpect(jsonPath("$.email").value("pat@acme.example"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.notes").value("Line one\nLine two"))
                .andExpect(jsonPath("$.createdBy.fullName").value("Dora Owner"))
                .andExpect(jsonPath("$.requirementCount").value(3))
                .andExpect(jsonPath("$.requirements[0].code").value("COI"))
                .andExpect(jsonPath("$.requirements[0].hasExpiration").value(true))
                .andExpect(jsonPath("$.requirements[1].code").value("WORKERS_COMP"))
                .andExpect(jsonPath("$.requirements[2].code").value("W9"))
                .andExpect(jsonPath("$.requirements[2].hasExpiration").value(false))
                .andExpect(jsonPath("$.createdAt").exists()).andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getReturnsTheSameDetailForEveryRole() throws Exception {
        String id = createId(owner, "Readable Co");
        for (Account who : List.of(owner, admin, member, viewer)) {
            who.client().get(VENDORS + "/" + id).andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyName").value("Readable Co"));
        }
    }

    @Test
    void createWritesAnAuditEventWithTheDefaultRequirements() throws Exception {
        String id = createId(member, "Audited Co");
        assertThat(auditCount(id, "vendor.created")).isEqualTo(1);
        String actor = jdbc.queryForObject("select actor_user_id::text from audit_event where entity_id = ?::uuid "
                + "and action = 'vendor.created'", String.class, id);
        assertThat(actor).isEqualTo(member.userId());
    }

    @Test
    void optionalStringsAreStrippedAndEmptyBecomesNull() throws Exception {
        Map<String, Object> b = body("  Trim Me  ");
        b.put("contactName", "   ");
        b.put("email", "");
        b.put("phone", " 555-1234 ");
        b.put("category", "  Roofing ");
        b.put("notes", "\n  ");
        owner.client().post(VENDORS, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyName").value("Trim Me"))
                .andExpect(jsonPath("$.contactName").value((Object) null))
                .andExpect(jsonPath("$.email").value((Object) null))
                .andExpect(jsonPath("$.phone").value("555-1234"))
                .andExpect(jsonPath("$.category").value("Roofing"))
                .andExpect(jsonPath("$.notes").value((Object) null));
    }

    @Test
    void statusAndIdsInTheBodyAreIgnored() throws Exception {
        UUID forced = UUID.randomUUID();
        Map<String, Object> b = body("Mass Assignment Co");
        b.put("id", forced.toString());
        b.put("status", "INACTIVE");
        b.put("organizationId", other.organizationId());
        b.put("createdAt", "2001-01-01T00:00:00Z");
        JsonNode created = json.readTree(owner.client().post(VENDORS, b).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(created.get("id").asString()).isNotEqualTo(forced.toString());
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("createdAt").asString()).doesNotStartWith("2001");
        assertThat(jdbc.queryForObject("select organization_id::text from vendor where id = ?::uuid", String.class,
                created.get("id").asString())).isEqualTo(orgId);

        Map<String, Object> put = body("Mass Assignment Co");
        put.put("status", "INACTIVE");
        owner.client().put(VENDORS + "/" + created.get("id").asString(), put).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---- validation ----

    private void assertInvalid(Map<String, Object> body, String field) throws Exception {
        owner.client().post(VENDORS, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')]").isNotEmpty());
        owner.client().put(VENDORS + "/" + createId(owner, "V " + UUID.randomUUID()), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')]").isNotEmpty());
    }

    @Test
    void companyNameIsRequiredAndLimited() throws Exception {
        assertInvalid(new LinkedHashMap<>(), "companyName");
        assertInvalid(body("   "), "companyName");
        assertInvalid(body("x".repeat(201)), "companyName");
        owner.client().post(VENDORS, body("x".repeat(200))).andExpect(status().isCreated());
    }

    @Test
    void otherFieldLengthsAreLimited() throws Exception {
        Map<String, Object> b = body("Limits Co");
        b.put("contactName", "x".repeat(121));
        assertInvalid(b, "contactName");
        b = body("Limits Co");
        b.put("category", "x".repeat(61));
        assertInvalid(b, "category");
        b = body("Limits Co");
        b.put("notes", "x".repeat(5001));
        assertInvalid(b, "notes");
        b = body("Limits Co");
        b.put("email", "a".repeat(250) + "@b.co");
        assertInvalid(b, "email");
        b = body("Limits Co");
        b.put("phone", "1".repeat(41));
        assertInvalid(b, "phone");
    }

    @Test
    void emailMustLookLikeAnEmail() throws Exception {
        Map<String, Object> b = body("Mail Co");
        b.put("email", "not-an-email");
        assertInvalid(b, "email");
    }

    @Test
    void phoneOnlyAllowsDigitsAndPhoneSymbols() throws Exception {
        for (String bad : List.of("call me", "555-1234; drop table", "<script>", "555‮1234")) {
            Map<String, Object> b = body("Phone Co");
            b.put("phone", bad);
            assertInvalid(b, "phone");
        }
        for (String ok : List.of("555-1234", "+1 (555) 010-2000", "555.123.4567 x12", "555 1234 EXT 9")) {
            Map<String, Object> b = body("Phone Co " + UUID.randomUUID());
            b.put("phone", ok);
            owner.client().post(VENDORS, b).andExpect(status().isCreated()).andExpect(jsonPath("$.phone").value(ok));
        }
    }

    @Test
    void controlAndBidiCharactersAreRejected() throws Exception {
        for (String bad : List.of("Evil\nName", "Evil‮Name", "Zero​width", "Nul\u0000Name", "Tab\tName")) {
            assertInvalid(body(bad), "companyName");
        }
        Map<String, Object> b = body("Ctl Co");
        b.put("contactName", "Bidi‮name");
        assertInvalid(b, "contactName");
        b = body("Ctl Co");
        b.put("category", "Cat\r\negory");
        assertInvalid(b, "category");
        b = body("Ctl Co");
        b.put("notes", "bidi ‮ in notes");
        assertInvalid(b, "notes");
        b = body("Ctl Co");
        b.put("notes", "nul \u0000 in notes");
        assertInvalid(b, "notes");
    }

    @Test
    void malformedIdInThePathIsA400LikeEveryOtherPhase1Path() throws Exception {
        owner.client().get(VENDORS + "/not-a-uuid").andExpect(status().isBadRequest());
    }

    // ---- duplicates ----

    @Test
    void duplicateCompanyNameIsA409WhateverTheCaseOrWhitespace() throws Exception {
        createId(owner, "Dup Plumbing");
        for (String variant : List.of("Dup Plumbing", "dup plumbing", "DUP PLUMBING", "  Dup Plumbing  ")) {
            owner.client().post(VENDORS, body(variant)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Vendor already exists"));
        }
        assertThat(jdbc.queryForObject("select count(*) from vendor where organization_id = ?::uuid "
                + "and lower(company_name) = 'dup plumbing'", Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void sameNameInAnotherOrganizationIsAllowed() throws Exception {
        createId(owner, "Shared Name Inc");
        create(other, "Shared Name Inc");
    }

    @Test
    void renamingToAnExistingNameIs409ButKeepingOrRecasingTheOwnNameIsFine() throws Exception {
        createId(owner, "First Co");
        String second = createId(owner, "Second Co");
        owner.client().put(VENDORS + "/" + second, body("first co")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Vendor already exists"));
        owner.client().put(VENDORS + "/" + second, body("SECOND CO")).andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("SECOND CO"));
        assertThat(read(owner, second).get("companyName").asString()).isEqualTo("SECOND CO");
    }

    @Test
    void concurrentCreatesOfTheSameNameGiveOneCreatedAndOneConflict() throws Exception {
        String name = "Race Co " + UUID.randomUUID();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        List<Account> actors = List.of(owner, admin, member);
        for (int i = 0; i < threads; i++) {
            Account actor = actors.get(i % actors.size());
            results.add(pool.submit(() -> {
                go.await();
                return actor.client().copy().post(VENDORS, body(name)).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) {
                created++;
            } else if (s == 409) {
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
    }

    // ---- update ----

    @Test
    void putReplacesTheEditableFieldsAndRecordsOnlyWhatChanged() throws Exception {
        Map<String, Object> b = body("Edit Co");
        b.put("contactName", "Old Contact");
        b.put("category", "HVAC");
        String id = json.readTree(owner.client().post(VENDORS, b).andReturn().getResponse().getContentAsString())
                .get("id").asString();

        Map<String, Object> put = body("Edit Co");
        put.put("contactName", "New Contact");
        put.put("email", "new@edit.example");
        // category omitted: full replacement => cleared
        owner.client().put(VENDORS + "/" + id, put).andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("New Contact"))
                .andExpect(jsonPath("$.email").value("new@edit.example"))
                .andExpect(jsonPath("$.category").value((Object) null));

        String metadata = jdbc.queryForObject("select metadata::text from audit_event where entity_id = ?::uuid "
                + "and action = 'vendor.updated'", String.class, id);
        JsonNode changes = json.readTree(metadata).get("changes");
        assertThat(changes.propertyNames()).containsExactlyInAnyOrder("contactName", "email", "category");
        assertThat(changes.get("contactName").get("before").asString()).isEqualTo("Old Contact");
        assertThat(changes.get("contactName").get("after").asString()).isEqualTo("New Contact");
        assertThat(changes.get("category").get("before").asString()).isEqualTo("HVAC");
        assertThat(changes.get("category").get("after").isNull()).isTrue();
    }

    @Test
    void aNoOpPutWritesNoAuditEventAndDoesNotTouchUpdatedAt() throws Exception {
        Map<String, Object> b = body("Same Co");
        b.put("category", "Paint");
        JsonNode created = json.readTree(owner.client().post(VENDORS, b).andReturn().getResponse()
                .getContentAsString());
        String id = created.get("id").asString();
        Map<String, Object> same = body(" Same Co ");
        same.put("category", "Paint");
        same.put("notes", "");
        owner.client().put(VENDORS + "/" + id, same).andExpect(status().isOk());
        assertThat(auditCount(id, "vendor.updated")).isZero();
        assertThat(read(owner, id).get("updatedAt").asString()).isEqualTo(created.get("updatedAt").asString());
    }

    // ---- deactivate / reactivate ----

    @Test
    void deactivateAndReactivateAreIdempotentAndAuditedOnlyOnRealChanges() throws Exception {
        String id = createId(owner, "Toggle Co");
        admin.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        admin.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(auditCount(id, "vendor.deactivated")).isEqualTo(1);

        admin.client().post(VENDORS + "/" + id + "/reactivate", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        admin.client().post(VENDORS + "/" + id + "/reactivate", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(auditCount(id, "vendor.reactivated")).isEqualTo(1);
    }

    @Test
    void anInactiveVendorStaysReadableAndEditable() throws Exception {
        String id = createId(owner, "Dormant Co");
        owner.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isOk());
        member.client().get(VENDORS + "/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        member.client().put(VENDORS + "/" + id, body("Dormant Co 2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // ---- requirements ----

    @Test
    void requirementsAreReplacedAndTheChangeIsAudited() throws Exception {
        String id = createId(owner, "Req Co"); // COI, WORKERS_COMP, W9
        String gl = typeId(owner, "GENERAL_LIABILITY");
        String coi = typeId(owner, "COI");
        owner.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of(coi, gl, gl)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requirementCount").value(2))
                .andExpect(jsonPath("$.requirements[0].code").value("COI"))
                .andExpect(jsonPath("$.requirements[1].code").value("GENERAL_LIABILITY"));
        assertThat(requirementCodes(read(viewer, id))).containsExactly("COI", "GENERAL_LIABILITY");

        JsonNode metadata = json.readTree(jdbc.queryForObject("select metadata::text from audit_event "
                + "where entity_id = ?::uuid and action = 'vendor.requirements_changed'", String.class, id));
        assertThat(metadata.get("added").get(0).asString()).isEqualTo("GENERAL_LIABILITY");
        assertThat(metadata.get("added")).hasSize(1);
        assertThat(metadata.get("removed")).hasSize(2);

        // same set again: no-op, no second event
        owner.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of(gl, coi)))
                .andExpect(status().isOk());
        assertThat(auditCount(id, "vendor.requirements_changed")).isEqualTo(1);

        owner.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requirementCount").value(0));
        assertThat(auditCount(id, "vendor.requirements_changed")).isEqualTo(2);
    }

    @Test
    void unknownForeignAndMissingRequirementIdsAreTheSame400() throws Exception {
        String id = createId(owner, "Bad Req Co");
        String foreign = typeId(other, "COI");
        String own = typeId(owner, "COI");
        var unknown = owner.client().put(VENDORS + "/" + id + "/requirements",
                Map.of("documentTypeIds", List.of(own, UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeIds")).andReturn().getResponse()
                .getContentAsString();
        var foreignResponse = owner.client().put(VENDORS + "/" + id + "/requirements",
                Map.of("documentTypeIds", List.of(own, foreign))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeIds")).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readTree(foreignResponse).get("errors")).isEqualTo(json.readTree(unknown).get("errors"));
        // nothing was applied
        assertThat(read(owner, id).get("requirementCount").asInt()).isEqualTo(3);
        owner.client().put(VENDORS + "/" + id + "/requirements", Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void inactiveTypesCannotBeRequired() throws Exception {
        String id = createId(owner, "Inactive Type Co");
        String other1 = typeId(owner, "OTHER");
        jdbc.update("update document_type set active = false where id = ?::uuid", other1);
        owner.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of(other1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("documentTypeIds"));
    }

    // ---- permissions ----

    @Test
    void viewerCannotCreateOrEditButCanRead() throws Exception {
        String id = createId(owner, "Viewer Test Co");
        viewer.client().post(VENDORS, body("Nope")).andExpect(status().isForbidden());
        viewer.client().put(VENDORS + "/" + id, body("Nope")).andExpect(status().isForbidden());
        viewer.client().get(VENDORS).andExpect(status().isOk());
        viewer.client().get(VENDORS + "/categories").andExpect(status().isOk());
        viewer.client().get(VENDORS + "/" + id + "/history").andExpect(status().isOk());
        viewer.client().get("/api/v1/document-types").andExpect(status().isOk());
        assertThat(read(owner, id).get("companyName").asString()).isEqualTo("Viewer Test Co");
    }

    @Test
    void memberCanCreateAndEditButNotArchiveOrManageRequirements() throws Exception {
        String id = createId(member, "Member Made Co");
        member.client().put(VENDORS + "/" + id, body("Member Edited Co")).andExpect(status().isOk());
        member.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isForbidden());
        member.client().post(VENDORS + "/" + id + "/reactivate", null).andExpect(status().isForbidden());
        member.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of()))
                .andExpect(status().isForbidden());
        viewer.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isForbidden());
        viewer.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of()))
                .andExpect(status().isForbidden());
        JsonNode v = read(owner, id);
        assertThat(v.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(v.get("requirementCount").asInt()).isEqualTo(3);
    }

    @Test
    void adminAndOwnerCanDoEverything() throws Exception {
        for (Account who : List.of(admin, owner)) {
            String id = createId(who, "Full Access " + UUID.randomUUID());
            who.client().put(VENDORS + "/" + id, body("Edited " + UUID.randomUUID())).andExpect(status().isOk());
            who.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isOk());
            who.client().post(VENDORS + "/" + id + "/reactivate", null).andExpect(status().isOk());
            who.client().put(VENDORS + "/" + id + "/requirements", Map.of("documentTypeIds", List.of()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        var anonymous = accounts.newClient();
        anonymous.get(VENDORS).andExpect(status().isUnauthorized());
        anonymous.get("/api/v1/document-types").andExpect(status().isUnauthorized());
        anonymous.post(VENDORS, body("X")).andExpect(status().isUnauthorized());
    }

    // ---- tenant isolation ----

    @Test
    void anotherOrganizationGets404EverywhereAndNothingChanges() throws Exception {
        String id = createId(owner, "Tenant A Vendor");
        owner.client().put(VENDORS + "/" + id + "/requirements",
                Map.of("documentTypeIds", List.of(typeId(owner, "COI")))).andExpect(status().isOk());
        String before = owner.client().get(VENDORS + "/" + id).andReturn().getResponse().getContentAsString();
        String foreignType = typeId(other, "W9");

        other.client().get(VENDORS + "/" + id).andExpect(status().isNotFound());
        other.client().put(VENDORS + "/" + id, body("Hijacked")).andExpect(status().isNotFound());
        other.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isNotFound());
        other.client().post(VENDORS + "/" + id + "/reactivate", null).andExpect(status().isNotFound());
        other.client().put(VENDORS + "/" + id + "/requirements",
                Map.of("documentTypeIds", List.of(foreignType))).andExpect(status().isNotFound());
        other.client().get(VENDORS + "/" + id + "/history").andExpect(status().isNotFound());
        // nonexistent id looks identical
        other.client().get(VENDORS + "/" + UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(owner.client().get(VENDORS + "/" + id).andReturn().getResponse().getContentAsString())
                .isEqualTo(before);
        assertThat(auditCount(id, "vendor.updated")).isZero();
        assertThat(auditCount(id, "vendor.deactivated")).isZero();
    }

    @Test
    void anOrganizationCannotAttachAnotherOrganizationsDocumentType() throws Exception {
        String mine = createId(other, "Attacker Vendor");
        String foreignType = typeId(owner, "COI");
        other.client().put(VENDORS + "/" + mine + "/requirements", Map.of("documentTypeIds", List.of(foreignType)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("documentTypeIds"));
        assertThat(jdbc.queryForObject("select count(*) from vendor_requirement where vendor_id = ?::uuid "
                + "and document_type_id = ?::uuid", Integer.class, mine, foreignType)).isZero();
    }

    @Test
    void theDatabaseItselfRejectsAcrossTenantRequirements() {
        String vendorOfOther = UUID.randomUUID().toString();
        jdbc.update("insert into vendor (id, organization_id, company_name, status, created_at, updated_at) "
                + "values (?::uuid, ?::uuid, 'FK Test', 'ACTIVE', now(), now())", vendorOfOther, other.organizationId());
        String typeOfOwner = jdbc.queryForObject("select id::text from document_type where organization_id = ?::uuid "
                + "and code = 'COI'", String.class, orgId);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("insert into vendor_requirement (id, organization_id, vendor_id, document_type_id, "
                        + "created_at) values (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, now())",
                        other.organizationId(), vendorOfOther, typeOfOwner));
    }

    @Test
    void categoriesAreSortedDistinctAndOnlyFromTheOwnOrganization() throws Exception {
        for (String[] c : new String[][] {{"V1", "Roofing"}, {"V2", "plumbing"}, {"V3", "Plumbing"}, {"V4", "  "},
                {"V5", "Electrical"}}) {
            Map<String, Object> b = body(c[0] + " cat");
            b.put("category", c[1]);
            owner.client().post(VENDORS, b).andExpect(status().isCreated());
        }
        Map<String, Object> foreign = body("Foreign cat");
        foreign.put("category", "Secret Category");
        other.client().post(VENDORS, foreign).andExpect(status().isCreated());

        JsonNode categories = json.readTree(viewer.client().get(VENDORS + "/categories").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> list = new ArrayList<>();
        categories.forEach(n -> list.add(n.asString().toLowerCase()));
        assertThat(list).containsExactly("electrical", "plumbing", "roofing");
    }

    // ---- history ----

    @Test
    void historyIsNewestFirstWithActorNamesAndChanges() throws Exception {
        String id = createId(owner, "History Co");
        clock.advance(java.time.Duration.ofMinutes(1));
        Map<String, Object> put = body("History Co");
        put.put("category", "Paint");
        member.client().put(VENDORS + "/" + id, put).andExpect(status().isOk());
        clock.advance(java.time.Duration.ofMinutes(1));
        admin.client().post(VENDORS + "/" + id + "/deactivate", null).andExpect(status().isOk());
        clock.advance(java.time.Duration.ofMinutes(1));
        admin.client().put(VENDORS + "/" + id + "/requirements",
                Map.of("documentTypeIds", List.of(typeId(owner, "GENERAL_LIABILITY")))).andExpect(status().isOk());

        viewer.client().get(VENDORS + "/" + id + "/history").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items[0].action").value("vendor.requirements_changed"))
                .andExpect(jsonPath("$.items[0].actor.fullName").value("Adam Admin"))
                .andExpect(jsonPath("$.items[0].changes.added.after[0]").value("GENERAL_LIABILITY"))
                .andExpect(jsonPath("$.items[0].changes.removed.before.length()").value(3))
                .andExpect(jsonPath("$.items[1].action").value("vendor.deactivated"))
                .andExpect(jsonPath("$.items[1].changes.status.before").value("ACTIVE"))
                .andExpect(jsonPath("$.items[1].changes.status.after").value("INACTIVE"))
                .andExpect(jsonPath("$.items[2].action").value("vendor.updated"))
                .andExpect(jsonPath("$.items[2].actor.fullName").value("Mia Member"))
                .andExpect(jsonPath("$.items[2].changes.category.before").value((Object) null))
                .andExpect(jsonPath("$.items[2].changes.category.after").value("Paint"))
                .andExpect(jsonPath("$.items[3].action").value("vendor.created"))
                .andExpect(jsonPath("$.items[3].actor.fullName").value("Dora Owner"))
                .andExpect(jsonPath("$.items[3].changes").value((Object) null))
                .andExpect(jsonPath("$.items[3].occurredAt").exists());

        viewer.client().get(VENDORS + "/" + id + "/history?size=2&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].action").value("vendor.updated"));
    }

    @Test
    void historyOnlyContainsTheEventsOfThatVendor() throws Exception {
        String a = createId(owner, "Hist A");
        String b = createId(owner, "Hist B");
        owner.client().post(VENDORS + "/" + b + "/deactivate", null).andExpect(status().isOk());
        owner.client().get(VENDORS + "/" + a + "/history").andExpect(jsonPath("$.totalItems").value(1));
    }
}
