package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M1 (ADR-0011): a vendor must never displace an APPROVED document. A portal upload over an approved, unexpired
 * CURRENT document is stored as a CANDIDATE that does not count for compliance until staff approve it.
 */
class PortalCandidateTest extends PortalTestBase {

    String vendor;
    String w9;
    String coi;
    String token;

    void givenLink() throws Exception {
        vendor = createVendor(member, "Candidate Co", null);
        w9 = typeId(member, "W9");
        coi = typeId(member, "COI");
        token = tokenOf(createLink(member, vendor, List.of(w9, coi)));
    }

    /** Staff upload (+ optional decision by the owner); returns the document id. */
    String staffDocument(String typeId, String issue, String expiration, String decision) throws Exception {
        String id = json.readTree(member.client().postMultipart("/api/v1/vendors/" + vendor + "/documents",
                file("staff.pdf", PDF), fields(typeId, issue, expiration)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        if (decision != null) {
            Map<String, Object> body = decision.equals("REJECTED") ? Map.of("decision", decision, "note", "bad scan")
                    : Map.of("decision", decision);
            owner.client().post("/api/v1/documents/" + id + "/review", body).andExpect(status().isOk());
        }
        return id;
    }

    String state(String id) {
        return jdbc.queryForObject("select state from document where id = ?::uuid", String.class, id);
    }

    String portalDocument(String filename) {
        return jdbc.queryForObject("select id::text from document where vendor_id = ?::uuid and original_filename = ?",
                String.class, vendor, filename);
    }

    JsonNode vendorDetail() throws Exception {
        return json.readTree(viewer.client().get("/api/v1/vendors/" + vendor).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    JsonNode requirement(JsonNode detail, String typeId) {
        for (JsonNode r : detail.get("requirements")) {
            if (r.get("documentTypeId").asString().equals(typeId)) {
                return r;
            }
        }
        throw new AssertionError("no requirement " + typeId);
    }

    @Test
    void portalUploadOverAnApprovedDocumentIsACandidateAndTheApprovedOneKeepsCounting() throws Exception {
        givenLink();
        String approved = staffDocument(w9, null, null, "APPROVED");
        portalUpload(token, file("vendor.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        String candidate = portalDocument("vendor.pdf");
        assertThat(state(approved)).isEqualTo("CURRENT");
        assertThat(state(candidate)).isEqualTo("CANDIDATE");

        // compliance: still the approved document (OK), not REVIEW_REQUIRED
        JsonNode req = requirement(vendorDetail(), w9);
        assertThat(req.get("status").asString()).isEqualTo("OK");
        assertThat(req.get("currentDocument").get("id").asString()).isEqualTo(approved);
        assertThat(req.get("currentDocument").get("reviewStatus").asString()).isEqualTo("APPROVED");
        // ...and the candidate is visible next to it as pending review, via portal
        assertThat(req.get("pendingReplacement").get("id").asString()).isEqualTo(candidate);
        assertThat(req.get("pendingReplacement").get("state").asString()).isEqualTo("CANDIDATE");
        assertThat(req.get("pendingReplacement").get("reviewStatus").asString()).isEqualTo("PENDING");
        assertThat(req.get("pendingReplacement").get("source").asString()).isEqualTo("PORTAL");

        // the vendor sees the approved status too
        JsonNode info = json.readTree(portalGet(token).andReturn().getResponse().getContentAsString());
        for (JsonNode t : info.get("documentTypes")) {
            if (t.get("id").asString().equals(w9)) {
                assertThat(t.get("status").asString()).isEqualTo("OK");
            }
        }

        // document list: CURRENT + CANDIDATE by default
        JsonNode list = json.readTree(viewer.client().get("/api/v1/vendors/" + vendor + "/documents")
                .andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(2);
        assertThat(list.findValuesAsString("state")).containsExactlyInAnyOrder("CURRENT", "CANDIDATE");
        // the dashboard SQL reads state = 'CURRENT' only, so the candidate cannot count
        assertThat(count("select count(*) from document where vendor_id = ?::uuid and state = 'CURRENT'", vendor))
                .isEqualTo(1);
    }

    @Test
    void approvingTheCandidateSupersedesTheOldDocumentAtomicallyAndAuditsItWithTheSource() throws Exception {
        givenLink();
        String approved = staffDocument(w9, null, null, "APPROVED");
        portalUpload(token, file("vendor.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        String candidate = portalDocument("vendor.pdf");

        owner.client().post("/api/v1/documents/" + candidate + "/review", Map.of("decision", "APPROVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CURRENT"))
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));

        assertThat(state(candidate)).isEqualTo("CURRENT");
        assertThat(state(approved)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select superseded_by_document_id::text from document where id = ?::uuid",
                String.class, approved)).isEqualTo(candidate);
        Map<String, Object> audit = jdbc.queryForMap("select metadata->>'source' as source, "
                + "metadata->>'supersededBy' as by from audit_event where action = 'document.superseded' "
                + "and entity_id = ?::uuid", approved);
        assertThat(audit).containsEntry("source", "PORTAL").containsEntry("by", candidate);
        JsonNode req = requirement(vendorDetail(), w9);
        assertThat(req.get("currentDocument").get("id").asString()).isEqualTo(candidate);
        assertThat(req.get("pendingReplacement").isNull()).isTrue();
        assertThat(req.get("status").asString()).isEqualTo("OK");
    }

    @Test
    void rejectingTheCandidateKeepsTheOldDocumentCurrent() throws Exception {
        givenLink();
        String approved = staffDocument(w9, null, null, "APPROVED");
        portalUpload(token, file("vendor.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        String candidate = portalDocument("vendor.pdf");

        owner.client().post("/api/v1/documents/" + candidate + "/review", Map.of("decision", "REJECTED"))
                .andExpect(status().isBadRequest()); // a note is required
        owner.client().post("/api/v1/documents/" + candidate + "/review",
                Map.of("decision", "REJECTED", "note", "Wrong company")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));

        assertThat(state(approved)).isEqualTo("CURRENT");
        assertThat(state(candidate)).isEqualTo("ARCHIVED");
        assertThat(requirement(vendorDetail(), w9).get("status").asString()).isEqualTo("OK");
        // already decided: a second review is a conflict, not a silent change
        owner.client().post("/api/v1/documents/" + candidate + "/review", Map.of("decision", "APPROVED"))
                .andExpect(status().isConflict());
        assertThat(state(approved)).isEqualTo("CURRENT");
    }

    @Test
    void aNewerPortalUploadReplacesThePendingCandidateAndKeepsAtMostOne() throws Exception {
        givenLink();
        String approved = staffDocument(w9, null, null, "APPROVED");
        portalUpload(token, file("first.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        portalUpload(token, file("second.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());

        assertThat(state(approved)).isEqualTo("CURRENT");
        assertThat(state(portalDocument("first.pdf"))).isEqualTo("SUPERSEDED");
        assertThat(state(portalDocument("second.pdf"))).isEqualTo("CANDIDATE");
        // the stale candidate can no longer be approved
        owner.client().post("/api/v1/documents/" + portalDocument("first.pdf") + "/review",
                Map.of("decision", "APPROVED")).andExpect(status().isConflict());
    }

    @Test
    void whenTheCurrentDocumentIsNotApprovedThePortalUploadSupersedesItAsBefore() throws Exception {
        givenLink();
        String pending = staffDocument(w9, null, null, null);
        String rejected = staffDocument(coi, "2026-01-01", "2099-01-01", "REJECTED");
        portalUpload(token, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        portalUpload(token, file("coi.pdf", PDF), fields(coi, "2026-01-01", "2099-01-01"))
                .andExpect(status().isCreated());
        assertThat(state(pending)).isEqualTo("SUPERSEDED");
        assertThat(state(rejected)).isEqualTo("SUPERSEDED");
        assertThat(state(portalDocument("w9.pdf"))).isEqualTo("CURRENT");
        assertThat(state(portalDocument("coi.pdf"))).isEqualTo("CURRENT");
        Map<String, Object> audit = jdbc.queryForMap("select metadata->>'source' as source from audit_event "
                + "where action = 'document.superseded' and entity_id = ?::uuid", pending);
        assertThat(audit).containsEntry("source", "PORTAL");
    }

    @Test
    void anApprovedButExpiredDocumentIsNotProtected() throws Exception {
        givenLink();
        String expired = staffDocument(coi, "2020-01-01", "2021-01-01", "APPROVED");
        portalUpload(token, file("renewal.pdf", PDF), fields(coi, "2026-01-01", "2099-01-01"))
                .andExpect(status().isCreated());
        assertThat(state(expired)).isEqualTo("SUPERSEDED");
        assertThat(state(portalDocument("renewal.pdf"))).isEqualTo("CURRENT");
    }

    @Test
    void aStaffUploadSupersedesAPendingCandidateToo() throws Exception {
        givenLink();
        String approved = staffDocument(w9, null, null, "APPROVED");
        portalUpload(token, file("vendor.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        String staff = staffDocument(w9, null, null, null);
        assertThat(state(approved)).isEqualTo("SUPERSEDED");
        assertThat(state(portalDocument("vendor.pdf"))).isEqualTo("SUPERSEDED");
        assertThat(state(staff)).isEqualTo("CURRENT");
    }

    @Test
    void reviewingACandidateNeedsTheReviewRoleAndStaysInsideTheOrganization() throws Exception {
        givenLink();
        staffDocument(w9, null, null, "APPROVED");
        portalUpload(token, file("vendor.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        String candidate = portalDocument("vendor.pdf");
        viewer.client().post("/api/v1/documents/" + candidate + "/review", Map.of("decision", "APPROVED"))
                .andExpect(status().isForbidden());
        other.client().post("/api/v1/documents/" + candidate + "/review", Map.of("decision", "APPROVED"))
                .andExpect(status().isNotFound());
        assertThat(state(candidate)).isEqualTo("CANDIDATE");
    }
}
