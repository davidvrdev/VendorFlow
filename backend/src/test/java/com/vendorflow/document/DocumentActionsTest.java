package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.infrastructure.storage.ObjectStorage;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.TestAccounts.Account;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** get / list / PATCH dates / review / archive / download, their roles and tenant isolation, vendor detail, history. */
class DocumentActionsTest extends DocumentTestBase {

    @Autowired ObjectStorage storage;
    @Autowired EntityManagerFactory emf;

    String vendor;
    String coiType;
    String w9Type;

    String newCoi() throws Exception {
        vendor = createVendor(member, "Actions");
        coiType = typeId(member, "COI");
        w9Type = typeId(member, "W9");
        return uploadPdf(member, vendor, coiType, "coi.pdf").get("id").asString();
    }

    String path(String id, String action) {
        return DOCUMENTS + "/" + id + (action == null ? "" : "/" + action);
    }

    static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- get / list ----

    @Test
    void everyRoleCanGetAndListAndMalformedIdIs400() throws Exception {
        String id = newCoi();
        for (Account who : List.of(owner, admin, member, viewer)) {
            who.client().get(path(id, null)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.vendorId").value(vendor));
            who.client().get(VENDORS + "/" + vendor + "/documents").andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
        member.client().get(path("not-a-uuid", null)).andExpect(status().isBadRequest());
        member.client().get(path("00000000-0000-0000-0000-000000000000", null)).andExpect(status().isNotFound());
        accountsAnonymous().get(path(id, null)).andExpect(status().isUnauthorized());
    }

    ApiClient accountsAnonymous() throws Exception {
        return accounts.newClient();
    }

    @Test
    void listIsOrderedByTypeSortOrderThenNewestFirstAndHistoryAddsOldVersions() throws Exception {
        newCoi();
        uploadOk(member, vendor, file("w9.pdf", PDF), fields(w9Type, null, null));
        uploadPdf(member, vendor, coiType, "coi-2.pdf");
        uploadOk(member, vendor, file("contract.pdf", PDF), fields(typeId(member, "CONTRACT"), null, null));

        JsonNode current = json.readTree(member.client().get(VENDORS + "/" + vendor + "/documents").andReturn()
                .getResponse().getContentAsString());
        assertThat(current).extracting(n -> n.get("documentType").get("code").asString())
                .containsExactly("COI", "W9", "CONTRACT");
        JsonNode all = json.readTree(member.client().get(VENDORS + "/" + vendor + "/documents?includeHistory=true")
                .andReturn().getResponse().getContentAsString());
        assertThat(all).extracting(n -> n.get("originalFilename").asString())
                .containsExactly("coi-2.pdf", "coi.pdf", "w9.pdf", "contract.pdf");
        assertThat(all).extracting(n -> n.get("state").asString())
                .containsExactly("CURRENT", "SUPERSEDED", "CURRENT", "CURRENT");
    }

    // ---- PATCH dates ----

    @Test
    void patchChangesDatesAndAuditsBeforeAndAfter() throws Exception {
        String id = newCoi();
        member.client().patch(path(id, null), body("issueDate", "2026-02-01", "expirationDate", "2027-02-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issueDate").value("2026-02-01"))
                .andExpect(jsonPath("$.expirationDate").value("2027-02-01"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"));
        assertThat(jdbc.queryForObject("select metadata->'changes'->'expirationDate'->>'before' from audit_event "
                + "where action = 'document.dates_changed' and entity_id = ?::uuid", String.class, id))
                .isEqualTo("2027-01-01");
        assertThat(jdbc.queryForObject("select metadata->>'vendorId' from audit_event where action = "
                + "'document.dates_changed' and entity_id = ?::uuid", String.class, id)).isEqualTo(vendor);
    }

    @Test
    void patchDoesNotResetAnApprovedReviewAndOnlyTouchesSentKeys() throws Exception {
        String id = newCoi();
        member.client().post(path(id, "review"), body("decision", "APPROVED")).andExpect(status().isOk());
        member.client().patch(path(id, null), body("issueDate", "2026-03-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issueDate").value("2026-03-01"))
                .andExpect(jsonPath("$.expirationDate").value("2027-01-01"))
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));
    }

    @Test
    void explicitNullClearsIssueDateButNotARequiredExpiration() throws Exception {
        String id = newCoi();
        member.client().patch(path(id, null), body("issueDate", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.issueDate").doesNotExist())
                .andExpect(jsonPath("$.expirationDate").value("2027-01-01"));
        member.client().patch(path(id, null), body("expirationDate", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        assertThat(getDoc(member, id).get("expirationDate").asString()).isEqualTo("2027-01-01");

        // A type without expiration may have its optional expiration cleared.
        String w9 = uploadOk(member, vendor, file("w9.pdf", PDF), fields(w9Type, null, "2030-01-01")).get("id")
                .asString();
        member.client().patch(path(w9, null), body("expirationDate", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationDate").doesNotExist());
    }

    @Test
    void patchValidatesDates() throws Exception {
        String id = newCoi();
        member.client().patch(path(id, null), body("issueDate", "2027-06-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("issueDate")); // after the existing expiration
        member.client().patch(path(id, null), body("expirationDate", "2025-12-31")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("issueDate")); // before the existing issue date
        member.client().patch(path(id, null), body("expirationDate", "2026-02-30")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        member.client().patch(path(id, null), body("issueDate", 20260101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("issueDate"));
        member.client().patch(path(id, null), body("expirationDate", "2101-01-01")).andExpect(status().isBadRequest());
        JsonNode doc = getDoc(member, id);
        assertThat(doc.get("issueDate").asString()).isEqualTo("2026-01-01");
        assertThat(doc.get("expirationDate").asString()).isEqualTo("2027-01-01");
    }

    @Test
    void emptyPatchOrUnchangedValuesChangeNothingAndWriteNoAudit() throws Exception {
        String id = newCoi();
        member.client().patch(path(id, null), body()).andExpect(status().isOk());
        member.client().patch(path(id, null), body("issueDate", "2026-01-01", "expirationDate", "2027-01-01"))
                .andExpect(status().isOk());
        member.client().patch(path(id, null), body("owner", "x", "state", "ARCHIVED", "vendorId", UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CURRENT"));
        assertThat(auditCount("document.dates_changed", id)).isZero();
    }

    @Test
    void patchOfNonCurrentDocumentIs409() throws Exception {
        String old = newCoi();
        uploadPdf(member, vendor, coiType, "newer.pdf"); // supersedes `old`
        member.client().patch(path(old, null), body("issueDate", "2026-02-01")).andExpect(status().isConflict());
        String archived = uploadOk(member, vendor, file("w9.pdf", PDF), fields(w9Type, null, null)).get("id")
                .asString();
        admin.client().post(path(archived, "archive"), null).andExpect(status().isOk());
        member.client().patch(path(archived, null), body("issueDate", "2026-02-01")).andExpect(status().isConflict());
    }

    @Test
    void viewerCannotPatchButMemberAndAboveCan() throws Exception {
        String id = newCoi();
        viewer.client().patch(path(id, null), body("issueDate", "2026-02-01")).andExpect(status().isForbidden());
        assertThat(getDoc(owner, id).get("issueDate").asString()).isEqualTo("2026-01-01");
        for (Account who : List.of(member, admin, owner)) {
            who.client().patch(path(id, null), body("issueDate", "2026-02-01")).andExpect(status().isOk());
        }
    }

    // ---- review ----

    @Test
    void approveRecordsReviewerAndTimeAndAuditsIt() throws Exception {
        String id = newCoi();
        member.client().post(path(id, "review"), body("decision", "APPROVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy.fullName").value("Mia Member"))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$.reviewNote").doesNotExist());
        assertThat(auditCount("document.reviewed", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata->>'decision' from audit_event where action = "
                + "'document.reviewed' and entity_id = ?::uuid", String.class, id)).isEqualTo("APPROVED");
    }

    @Test
    void rejectionNeedsANoteOfAtMost1000PlainTextCharacters() throws Exception {
        String id = newCoi();
        member.client().post(path(id, "review"), body("decision", "REJECTED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        member.client().post(path(id, "review"), body("decision", "REJECTED", "note", "   "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));
        member.client().post(path(id, "review"), body("decision", "REJECTED", "note", "x".repeat(1001)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));
        member.client().post(path(id, "review"), body("decision", "REJECTED", "note", "bad‮note"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));
        assertThat(getDoc(member, id).get("reviewStatus").asString()).isEqualTo("PENDING");

        member.client().post(path(id, "review"), body("decision", "REJECTED", "note", "Expired policy\nPlease resend"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("Expired policy\nPlease resend"));
        member.client().post(path(id, "review"), body("decision", "REJECTED", "note", "x".repeat(1000)))
                .andExpect(status().isOk());
    }

    @Test
    void decisionMustBeApprovedOrRejected() throws Exception {
        String id = newCoi();
        for (Object bad : new Object[] {"PENDING", "approved", "MAYBE", null, 5}) {
            member.client().post(path(id, "review"), body("decision", bad)).andExpect(status().isBadRequest());
        }
        member.client().post(path(id, "review"), body()).andExpect(status().isBadRequest());
        assertThat(getDoc(member, id).get("reviewStatus").asString()).isEqualTo("PENDING");
    }

    @Test
    void aReviewCanBeRedoneAndApprovalMayCarryANote() throws Exception {
        String id = newCoi();
        member.client().post(path(id, "review"), body("decision", "REJECTED", "note", "blurry")).andExpect(status().isOk());
        member.client().post(path(id, "review"), body("decision", "APPROVED", "note", "ok now"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNote").value("ok now"));
        member.client().post(path(id, "review"), body("decision", "APPROVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewNote").doesNotExist());
    }

    @Test
    void onlyCurrentDocumentsCanBeReviewed() throws Exception {
        String old = newCoi();
        uploadPdf(member, vendor, coiType, "newer.pdf");
        member.client().post(path(old, "review"), body("decision", "APPROVED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Document is not current"));
        String archived = uploadOk(member, vendor, file("w9.pdf", PDF), fields(w9Type, null, null)).get("id").asString();
        admin.client().post(path(archived, "archive"), null).andExpect(status().isOk());
        member.client().post(path(archived, "review"), body("decision", "APPROVED")).andExpect(status().isConflict());
        assertThat(auditCount("document.reviewed", old)).isZero();
    }

    @Test
    void viewerCannotReview() throws Exception {
        String id = newCoi();
        viewer.client().post(path(id, "review"), body("decision", "APPROVED")).andExpect(status().isForbidden());
        assertThat(getDoc(owner, id).get("reviewStatus").asString()).isEqualTo("PENDING");
    }

    // ---- archive ----

    @Test
    void adminAndOwnerCanArchiveMemberAndViewerCannot() throws Exception {
        String id = newCoi();
        member.client().post(path(id, "archive"), null).andExpect(status().isForbidden());
        viewer.client().post(path(id, "archive"), null).andExpect(status().isForbidden());
        assertThat(getDoc(owner, id).get("state").asString()).isEqualTo("CURRENT");
        admin.client().post(path(id, "archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ARCHIVED"));
        // idempotent: second call is a 200 with no second audit row
        owner.client().post(path(id, "archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ARCHIVED"));
        assertThat(auditCount("document.archived", id)).isEqualTo(1);
        // the archived current document disappears from the default listing but stays in the history
        member.client().get(VENDORS + "/" + vendor + "/documents").andExpect(jsonPath("$.length()").value(0));
        member.client().get(VENDORS + "/" + vendor + "/documents?includeHistory=true")
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void supersededDocumentsCanBeArchived() throws Exception {
        String old = newCoi();
        String current = uploadPdf(member, vendor, coiType, "newer.pdf").get("id").asString();
        admin.client().post(path(old, "archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ARCHIVED"));
        assertThat(getDoc(owner, current).get("state").asString()).isEqualTo("CURRENT");
    }

    // ---- download ----

    @Test
    void downloadStreamsTheExactBytesWithTheContractHeadersAndAuditsIt() throws Exception {
        vendor = createVendor(member, "Download");
        byte[] content = pdfOfSize(300_000);
        String id = uploadOk(member, vendor, file("Seguro día \"x\".pdf", content),
                fields(typeId(member, "W9"), null, null)).get("id").asString();

        MockHttpServletResponse response = viewer.client().get(path(id, "download")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().longValue("Content-Length", content.length))
                .andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(content);
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"Seguro d_a _x_.pdf\"; "
                + "filename*=UTF-8''Seguro%20d%C3%ADa%20%22x%22.pdf");

        assertThat(auditCount("document.downloaded", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata->>'vendorId' from audit_event where action = "
                + "'document.downloaded' and entity_id = ?::uuid", String.class, id)).isEqualTo(vendor);
        assertThat(jdbc.queryForObject("select actor_user_id::text from audit_event where action = "
                + "'document.downloaded' and entity_id = ?::uuid", String.class, id)).isEqualTo(viewer.userId());
    }

    @Test
    void imagesAreServedAsAttachmentsWithTheirDetectedType() throws Exception {
        vendor = createVendor(member, "Images");
        String id = uploadOk(member, vendor, file("scan.png", PNG), fields(typeId(member, "OTHER"), null, null))
                .get("id").asString();
        MockHttpServletResponse response = owner.client().get(path(id, "download")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(response.getContentAsByteArray()).isEqualTo(PNG);
    }

    @Test
    void supersededAndArchivedDocumentsStayDownloadable() throws Exception {
        String old = newCoi();
        uploadPdf(member, vendor, coiType, "newer.pdf");
        admin.client().post(path(old, "archive"), null).andExpect(status().isOk());
        viewer.client().get(path(old, "download")).andExpect(status().isOk());
    }

    @Test
    void missingStoredObjectIsA404ProblemAndNotAudited() throws Exception {
        String id = newCoi();
        storage.delete("org/" + orgId + "/doc/" + id);
        String body = member.client().get(path(id, "download")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.requestId").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("storage").doesNotContain("sha256").doesNotContain("Exception")
                .doesNotContain("org/");
        assertThat(auditCount("document.downloaded", id)).isZero();
    }

    // ---- tenant isolation: another organization's user (an OWNER there) gets 404 everywhere ----

    @Test
    void userOfAnotherOrganizationGets404OnEveryDocumentEndpointAndNothingChanges() throws Exception {
        String id = newCoi();
        ApiClient them = other.client();
        them.get(path(id, null)).andExpect(status().isNotFound());
        them.patch(path(id, null), body("issueDate", "2026-02-01")).andExpect(status().isNotFound());
        them.post(path(id, "review"), body("decision", "REJECTED", "note", "no")).andExpect(status().isNotFound());
        them.post(path(id, "archive"), null).andExpect(status().isNotFound());
        them.get(path(id, "download")).andExpect(status().isNotFound());
        JsonNode doc = getDoc(owner, id);
        assertThat(doc.get("state").asString()).isEqualTo("CURRENT");
        assertThat(doc.get("reviewStatus").asString()).isEqualTo("PENDING");
        assertThat(doc.get("issueDate").asString()).isEqualTo("2026-01-01");
        assertThat(auditCount("document.downloaded", id)).isZero();
        // same body shape as a nonexistent id
        String missing = them.get(path("00000000-0000-0000-0000-000000000000", null)).andReturn().getResponse()
                .getContentAsString();
        String foreign = them.get(path(id, null)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(foreign).get("title")).isEqualTo(json.readTree(missing).get("title"));
    }

    // ---- vendor detail + history ----

    @Test
    void vendorDetailShowsTheCurrentDocumentPerRequirementAndOtherDocuments() throws Exception {
        vendor = createVendor(member, "Detail"); // default requirements: COI, WORKERS_COMP, W9
        coiType = typeId(member, "COI");
        String coi = uploadPdf(member, vendor, coiType, "coi-1.pdf").get("id").asString();
        String coi2 = uploadPdf(member, vendor, coiType, "coi-2.pdf").get("id").asString();
        String contract = uploadOk(member, vendor, file("contract.pdf", PDF), fields(typeId(member, "CONTRACT"), null,
                null)).get("id").asString(); // not a requirement of this vendor

        JsonNode detail = vendor(viewer, vendor);
        JsonNode reqs = detail.get("requirements");
        assertThat(reqs).extracting(n -> n.get("code").asString()).containsExactly("COI", "WORKERS_COMP", "W9");
        assertThat(reqs.get(0).get("currentDocument").get("id").asString()).isEqualTo(coi2);
        assertThat(reqs.get(0).get("currentDocument").get("state").asString()).isEqualTo("CURRENT");
        assertThat(reqs.get(1).get("currentDocument").isNull()).isTrue();
        assertThat(reqs.get(2).get("currentDocument").isNull()).isTrue();
        assertThat(detail.get("otherDocuments")).extracting(n -> n.get("id").asString()).containsExactly(contract);
        assertThat(coi).isNotEqualTo(coi2);

        // a vendor without documents has an empty list
        JsonNode empty = vendor(viewer, createVendor(member, "Empty"));
        assertThat(empty.get("otherDocuments").isArray()).isTrue();
        assertThat(empty.get("otherDocuments")).isEmpty();
        assertThat(empty.get("requirements").get(0).get("currentDocument").isNull()).isTrue();
    }

    @Test
    void archivedRequirementDocumentLeavesTheRequirementEmptyAgain() throws Exception {
        vendor = createVendor(member, "Archive detail");
        String id = uploadPdf(member, vendor, typeId(member, "COI"), "c.pdf").get("id").asString();
        admin.client().post(path(id, "archive"), null).andExpect(status().isOk());
        assertThat(vendor(viewer, vendor).get("requirements").get(0).get("currentDocument").isNull()).isTrue();
    }

    @Test
    void vendorDetailLoadsDocumentsWithABoundedNumberOfStatements() throws Exception {
        vendor = createVendor(member, "Count");
        List<String> codes = List.of("COI", "WORKERS_COMP", "W9");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        uploadPdf(member, vendor, typeId(member, "COI"), "a.pdf");
        long few = statementsForDetail(stats);

        for (String code : codes) {
            uploadOk(member, vendor, file(code + ".pdf", PDF), fields(typeId(member, code), "2026-01-01", "2027-01-01"));
        }
        for (String code : List.of("CONTRACT", "OTHER", "BUSINESS_LICENSE", "GENERAL_LIABILITY")) {
            uploadOk(member, vendor, file(code + ".pdf", PDF), fields(typeId(member, code), "2026-01-01", "2027-01-01"));
        }
        long many = statementsForDetail(stats);

        assertThat(vendor(viewer, vendor).get("otherDocuments")).hasSize(4);
        assertThat(many).as("statements with 8 documents vs 1").isEqualTo(few);
        assertThat(many).isLessThanOrEqualTo(10);
    }

    private long statementsForDetail(Statistics stats) throws Exception {
        stats.clear();
        viewer.client().get(VENDORS + "/" + vendor).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    @Test
    void documentListLoadsInABoundedNumberOfStatements() throws Exception {
        vendor = createVendor(member, "Count list");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        String coi = typeId(member, "COI");
        uploadPdf(member, vendor, coi, "a.pdf");
        stats.clear();
        viewer.client().get(VENDORS + "/" + vendor + "/documents?includeHistory=true").andExpect(status().isOk());
        long few = stats.getPrepareStatementCount();
        for (int i = 0; i < 8; i++) {
            uploadPdf(member, vendor, coi, "v" + i + ".pdf");
        }
        stats.clear();
        viewer.client().get(VENDORS + "/" + vendor + "/documents?includeHistory=true")
                .andExpect(jsonPath("$.length()").value(9));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(few);
    }

    @Test
    void vendorHistoryIncludesItsDocumentEventsNewestFirstAndNoOthers() throws Exception {
        String id = newCoi(); // uploaded
        String thisVendor = vendor;
        member.client().post(path(id, "review"), body("decision", "APPROVED")).andExpect(status().isOk());
        member.client().patch(path(id, null), body("expirationDate", "2028-01-01")).andExpect(status().isOk());
        uploadPdf(member, thisVendor, coiType, "next.pdf"); // uploaded + superseded
        viewer.client().get(path(id, "download")).andExpect(status().isOk());
        admin.client().post(path(id, "archive"), null).andExpect(status().isOk());

        // a second vendor with its own document must not leak into the first vendor's history
        String otherVendor = createVendor(member, "Other vendor");
        uploadPdf(member, otherVendor, coiType, "elsewhere.pdf");

        JsonNode page = json.readTree(viewer.client().get(VENDORS + "/" + thisVendor + "/history?size=50")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> actions = page.get("items").valueStream().map(n -> n.get("action").asString()).toList();
        // uploaded + superseded of one upload share a transaction (same instant): their relative order is not defined
        assertThat(actions.subList(0, 2)).containsExactly("document.archived", "document.downloaded");
        assertThat(actions.subList(2, 4)).containsExactlyInAnyOrder("document.superseded", "document.uploaded");
        assertThat(actions.subList(4, 8)).containsExactly(
                "document.dates_changed", "document.reviewed", "document.uploaded",
                "vendor.created");
        assertThat(page.get("totalItems").asInt()).isEqualTo(8);
        JsonNode dates = page.get("items").valueStream().filter(n -> n.get("action").asString()
                .equals("document.dates_changed")).findFirst().orElseThrow();
        assertThat(dates.get("changes").get("expirationDate").get("before").asString()).isEqualTo("2027-01-01");
        assertThat(dates.get("changes").get("expirationDate").get("after").asString()).isEqualTo("2028-01-01");
        assertThat(dates.get("detail").asString()).isEqualTo("Certificate of Insurance - coi.pdf");
        assertThat(dates.get("actor").get("fullName").asString()).isEqualTo("Mia Member");
        JsonNode created = page.get("items").valueStream().filter(n -> n.get("action").asString()
                .equals("vendor.created")).findFirst().orElseThrow();
        assertThat(created.get("detail").isNull()).isTrue();
        JsonNode reviewed = page.get("items").valueStream().filter(n -> n.get("action").asString()
                .equals("document.reviewed")).findFirst().orElseThrow();
        assertThat(reviewed.get("changes").get("reviewStatus").get("after").asString()).isEqualTo("APPROVED");

        // paging still works over the combined timeline
        JsonNode first = json.readTree(viewer.client().get(VENDORS + "/" + thisVendor + "/history?size=3&page=0")
                .andReturn().getResponse().getContentAsString());
        JsonNode last = json.readTree(viewer.client().get(VENDORS + "/" + thisVendor + "/history?size=3&page=2")
                .andReturn().getResponse().getContentAsString());
        assertThat(first.get("items")).hasSize(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(last.get("items")).hasSize(2);
        // another organization cannot read it
        other.client().get(VENDORS + "/" + thisVendor + "/history").andExpect(status().isNotFound());
    }

    @Test
    void vendorHistoryIsOneQueryPlusCountRegardlessOfEvents() throws Exception {
        String id = newCoi();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        viewer.client().get(VENDORS + "/" + vendor + "/history").andExpect(status().isOk());
        long few = stats.getPrepareStatementCount();
        for (int i = 0; i < 5; i++) {
            member.client().post(path(id, "review"), body("decision", "APPROVED")).andExpect(status().isOk());
        }
        stats.clear();
        viewer.client().get(VENDORS + "/" + vendor + "/history").andExpect(status().isOk());
        assertThat(stats.getPrepareStatementCount()).isEqualTo(few);
    }

    @Test
    void historyQueryCanUseTheVendorIdIndex() {
        // Document events are looked up by metadata.vendorId; V5 indexes exactly that expression. Seq scans are
        // disabled so the planner must pick the index if it is usable at all (the table is tiny in tests).
        String plan = jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("set enable_seqscan = off");
                try (var rs = st.executeQuery("explain select id from audit_event where organization_id = '"
                        + UUID.randomUUID() + "' and entity_type = 'document' and "
                        + "jsonb_extract_path_text(metadata, 'vendorId') = '" + UUID.randomUUID() + "'")) {
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) {
                        sb.append(rs.getString(1)).append('\n');
                    }
                    return sb.toString();
                } finally {
                    st.execute("reset enable_seqscan");
                }
            }
        });
        assertThat(plan).contains("audit_event_document_vendor_idx");
    }
}
