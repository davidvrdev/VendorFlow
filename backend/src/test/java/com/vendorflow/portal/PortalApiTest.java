package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.EmailTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** The public portal: view, upload through the shared pipeline, no-oracle 404, caps, isolation. */
class PortalApiTest extends PortalTestBase {

    @Autowired OutboxDispatcher dispatcher;

    String vendor;
    String coi;
    String w9;
    JsonNode created;
    String token;
    String linkId;

    void givenLinkFor(String... typeCodes) throws Exception {
        vendor = createVendor(member, "Acme Roofing", "vendor@example.com");
        coi = typeId(member, "COI");
        w9 = typeId(member, "W9");
        List<String> ids = new ArrayList<>();
        for (String code : typeCodes) {
            ids.add(code.equals("COI") ? coi : w9);
        }
        created = createLink(member, vendor, ids);
        token = tokenOf(created);
        linkId = created.get("link").get("id").asString();
    }

    // ---- view ----

    @Test
    void viewExposesOnlyOrganizationNameVendorNameAndRequestedTypes() throws Exception {
        givenLinkFor("COI", "W9");
        String body = portalGet(token).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.vendorName").value(org.hamcrest.Matchers.startsWith("Acme Roofing")))
                .andExpect(jsonPath("$.organizationName").value(org.hamcrest.Matchers.startsWith("Portal Org")))
                .andExpect(jsonPath("$.remainingUploads").value(20))
                .andExpect(jsonPath("$.acceptingUploads").value(true))
                .andExpect(jsonPath("$.documentTypes.length()").value(2))
                .andExpect(jsonPath("$.documentTypes[0].name").value("Certificate of Insurance"))
                .andExpect(jsonPath("$.documentTypes[0].status").value("MISSING"))
                .andExpect(jsonPath("$.documentTypes[1].name").value("W-9"))
                .andReturn().getResponse().getContentAsString();
        JsonNode info = json.readTree(body);
        assertThat(fieldNames(info)).containsExactly("acceptingUploads", "documentTypes", "expiresAt",
                "organizationName", "remainingUploads", "vendorName");
        assertThat(fieldNames(info.get("documentTypes").get(0))).containsExactly("expirationDate", "hasExpiration",
                "id", "name", "status");
        // no internal ids of the tenant leak
        assertThat(body).doesNotContain(orgId).doesNotContain(vendor).doesNotContain(linkId)
                .doesNotContain("member").doesNotContain("@example.com");
    }

    @Test
    void viewOnlyListsTheLinksTypesNotTheOtherRequirements() throws Exception {
        givenLinkFor("W9");
        portalGet(token).andExpect(status().isOk()).andExpect(jsonPath("$.documentTypes.length()").value(1))
                .andExpect(jsonPath("$.documentTypes[0].name").value("W-9"));
    }

    @Test
    void viewReflectsStatusAfterAnUpload() throws Exception {
        givenLinkFor("COI", "W9");
        portalUpload(token, file("coi.pdf", PDF), fields(coi, "2026-01-01", "2099-01-01"))
                .andExpect(status().isCreated());
        JsonNode info = json.readTree(portalGet(token).andReturn().getResponse().getContentAsString());
        assertThat(info.get("remainingUploads").asInt()).isEqualTo(19);
        assertThat(info.get("documentTypes").get(0).get("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(info.get("documentTypes").get(1).get("status").asString()).isEqualTo("MISSING");
    }

    // ---- upload happy path ----

    @Test
    void uploadCreatesAPendingPortalDocumentAuditsItAndNotifiesOwnersAndAdminsOnce() throws Exception {
        givenLinkFor("COI", "W9");
        JsonNode receipt = json.readTree(portalUpload(token, file("coi.pdf", PDF),
                fields(coi, "2026-01-01", "2099-01-01")).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.documentType.name").value("Certificate of Insurance"))
                .andExpect(jsonPath("$.originalFilename").value("coi.pdf"))
                .andExpect(jsonPath("$.remainingUploads").value(19))
                .andReturn().getResponse().getContentAsString());
        assertThat(fieldNames(receipt)).containsExactly("documentType", "originalFilename", "remainingUploads",
                "status", "uploadedAt");

        Map<String, Object> row = jdbc.queryForMap("select id::text, state, review_status, source, "
                + "upload_link_id::text, uploaded_by_user_id, mime_type from document where vendor_id = ?::uuid",
                vendor);
        assertThat(row).containsEntry("state", "CURRENT").containsEntry("review_status", "PENDING")
                .containsEntry("source", "PORTAL").containsEntry("upload_link_id", linkId)
                .containsEntry("uploaded_by_user_id", null).containsEntry("mime_type", "application/pdf");
        assertThat(useCount(linkId)).isEqualTo(1);
        assertThat(count("select count(*) from vendor_upload_link where id = ?::uuid and last_used_at is not null",
                linkId)).isEqualTo(1);

        Map<String, Object> audit = jdbc.queryForMap("select organization_id::text as org, actor_user_id, "
                + "metadata->>'source' as source, metadata->>'linkId' as link, metadata::text as meta from audit_event "
                + "where action = 'document.uploaded' and entity_id = ?::uuid", row.get("id"));
        assertThat(audit).containsEntry("org", orgId).containsEntry("actor_user_id", null)
                .containsEntry("source", "PORTAL").containsEntry("link", linkId);
        assertThat(audit.get("meta").toString()).doesNotContain(token);

        // staff see it as a pending portal upload, uploader null
        JsonNode staffView = json.readTree(viewer.client().get("/api/v1/vendors/" + vendor + "/documents")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(staffView).hasSize(1);
        assertThat(staffView.get(0).get("source").asString()).isEqualTo("PORTAL");
        assertThat(staffView.get(0).get("reviewStatus").asString()).isEqualTo("PENDING");
        assertThat(staffView.get(0).hasNonNull("uploadedBy")).isFalse();

        // one e-mail to each verified OWNER/ADMIN, none to the member/viewer; a 2nd file the same day adds none
        portalUpload(token, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(EmailTokens.count(dispatcher, emailSender, owner.email(), NotificationKind.PORTAL_UPLOAD))
                .isEqualTo(1);
        assertThat(EmailTokens.count(dispatcher, emailSender, admin.email(), NotificationKind.PORTAL_UPLOAD))
                .isEqualTo(1);
        assertThat(EmailTokens.count(dispatcher, emailSender, member.email(), NotificationKind.PORTAL_UPLOAD))
                .isZero();
        assertThat(EmailTokens.count(dispatcher, emailSender, viewer.email(), NotificationKind.PORTAL_UPLOAD))
                .isZero();
        String mail = EmailTokens.latest(dispatcher, emailSender, owner.email(), NotificationKind.PORTAL_UPLOAD)
                .textBody();
        assertThat(mail).contains("Acme Roofing").contains("Certificate of Insurance").contains("/vendors/" + vendor)
                .doesNotContain(token).doesNotContain("coi.pdf");
    }

    @Test
    void aVendorCanNeverApprove() throws Exception {
        givenLinkFor("W9");
        portalUpload(token, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        String docId = jdbc.queryForObject("select id::text from document where vendor_id = ?::uuid", String.class,
                vendor);
        // there is no portal route for review; staff routes need a session (the vendor has none)
        vendorBrowser().perform(HttpMethod.POST, "/api/v1/documents/" + docId + "/review", Map.of("status", "APPROVED"),
                false).andExpect(status().isForbidden()); // CSRF rejects it before authentication even matters
        new ApiClient(mvc, json).primeCsrf().post("/api/v1/documents/" + docId + "/review",
                Map.of("status", "APPROVED")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select review_status from document where id = ?::uuid", String.class, docId))
                .isEqualTo("PENDING");
    }

    @Test
    void aNewUploadSupersedesTheCurrentDocumentOfThatType() throws Exception {
        givenLinkFor("W9");
        portalUpload(token, file("a.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        portalUpload(token, file("b.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(count("select count(*) from document where vendor_id = ?::uuid and state = 'CURRENT'", vendor))
                .isEqualTo(1);
        assertThat(count("select count(*) from document where vendor_id = ?::uuid and state = 'SUPERSEDED'", vendor))
                .isEqualTo(1);
        assertThat(useCount(linkId)).isEqualTo(2);
    }

    // ---- no oracle ----

    @Test
    void unknownMalformedExpiredRevokedAndInactiveVendorLinksAnswerTheSameNotFound() throws Exception {
        givenLinkFor("W9");
        String unknown = created.get("url").asString().replaceAll(".*#token=", "");
        unknown = unknown.substring(0, 42) + (unknown.charAt(42) == 'A' ? 'B' : 'A'); // same shape, not a real token

        String revokedToken = tokenOf(createLink(member, vendor, List.of(w9)));
        String revokedId = jdbc.queryForObject("select id::text from vendor_upload_link where token_hash = ?",
                String.class, hashOf(revokedToken));
        member.client().post(linksPath(vendor) + "/" + revokedId + "/revoke", null).andExpect(status().isOk());

        String inactiveVendor = createVendor(member, "Inactive", null);
        String inactiveToken = tokenOf(createLink(member, inactiveVendor, List.of(w9)));
        admin.client().post("/api/v1/vendors/" + inactiveVendor + "/deactivate", null).andExpect(status().isOk());

        String expiredToken = tokenOf(createLink(member, vendor, List.of(w9), "expiresInDays", 1));

        List<String> bodies = new ArrayList<>();
        for (String t : List.of(unknown, "short", "!!!not-a-token!!!", revokedToken, inactiveToken)) {
            bodies.add(maskVolatile(portalGet(t).andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andReturn().getResponse().getContentAsString()));
        }
        clock.advance(Duration.ofDays(2)); // the 14-day link of givenLinkFor is still valid; only expiredToken died
        bodies.add(maskVolatile(portalGet(expiredToken).andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString()));
        portalGet(token).andExpect(status().isOk());

        assertThat(bodies).hasSize(6).doesNotContain("").containsOnly(bodies.get(0));
        assertThat(bodies.get(0)).contains("portal-link-invalid").contains("This link is invalid or has expired.");

        // uploads answer the same way, and store nothing
        for (String t : List.of(unknown, revokedToken, inactiveToken, expiredToken)) {
            portalUpload(t, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/portal-link-invalid"));
        }
        assertThat(documentRows(vendor)).isZero();
        assertThat(documentRows(inactiveVendor)).isZero();
    }

    @Test
    void aMissingOrMalformedHeaderIsTheSameNotFoundAndTheTokenIsNotAcceptedInThePathOrQuery() throws Exception {
        givenLinkFor("W9");
        String unknown = maskVolatile(portalGet("x".repeat(43)).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString());
        assertThat(maskVolatile(portalGet(null).andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString())).isEqualTo(unknown);
        assertThat(maskVolatile(portalGet(token.substring(0, 42) + "!").andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString())).isEqualTo(unknown);
        // a query parameter or a path segment is never a credential
        vendorBrowser().perform(HttpMethod.GET, PORTAL + "?token=" + token, null, false).andExpect(status().isNotFound());
        vendorBrowser().perform(HttpMethod.GET, PORTAL + "/" + token, null, false).andExpect(status().isUnauthorized());
        portalGet(token).andExpect(status().isOk());
    }

    @Test
    void aRevokedLinkStopsWorkingImmediately() throws Exception {
        givenLinkFor("W9");
        portalUpload(token, file("a.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        member.client().post(linksPath(vendor) + "/" + linkId + "/revoke", null).andExpect(status().isOk());
        portalUpload(token, file("b.pdf", PDF), fields(w9, null, null)).andExpect(status().isNotFound());
        portalGet(token).andExpect(status().isNotFound());
        assertThat(documentRows(vendor)).isEqualTo(1);
        assertThat(useCount(linkId)).isEqualTo(1);
    }

    // ---- type set and pipeline ----

    @Test
    void aTypeOutsideTheLinksSetIs400EvenWhenItIsARequirementOfTheVendor() throws Exception {
        givenLinkFor("COI");
        portalUpload(token, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        // a type of another organization and a random id look exactly the same
        portalUpload(token, file("w9.pdf", PDF), fields(typeId(other, "COI"), null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        portalUpload(token, file("w9.pdf", PDF), fields(UUID.randomUUID().toString(), null, null))
                .andExpect(status().isBadRequest());
        portalUpload(token, file("w9.pdf", PDF), fields(null, null, null)).andExpect(status().isBadRequest());
        assertThat(documentRows(vendor)).isZero();
        assertThat(useCount(linkId)).isZero();
    }

    @Test
    void aTypeThatIsNoLongerARequirementOrIsDeactivatedCannotBeUploaded() throws Exception {
        givenLinkFor("COI", "W9");
        // staff drop W9 from the vendor's requirements: the link still lists it, the portal must not accept it
        admin.client().put("/api/v1/vendors/" + vendor + "/requirements", Map.of("documentTypeIds", List.of(coi)))
                .andExpect(status().isOk());
        portalUpload(token, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isBadRequest());
        portalGet(token).andExpect(jsonPath("$.documentTypes.length()").value(1));
        portalUpload(token, file("coi.pdf", PDF), fields(coi, null, "2099-01-01")).andExpect(status().isCreated());
    }

    @Test
    void theSharedPipelineValidationAppliesAndAFailedUploadDoesNotConsumeBudget() throws Exception {
        givenLinkFor("COI", "W9");
        // missing file
        portalUpload(token, null, fields(w9, null, null)).andExpect(status().isBadRequest());
        // extension not allowed
        portalUpload(token, file("x.exe", PDF), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        // magic bytes do not match the extension
        portalUpload(token, file("x.pdf", "not a pdf at all".getBytes()), fields(w9, null, null))
                .andExpect(status().isUnsupportedMediaType());
        // client Content-Type is never consulted: a PDF named .png is rejected
        portalUpload(token, file("x.png", PDF), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        // type with expiration requires the date
        portalUpload(token, file("coi.pdf", PDF), fields(coi, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        // invalid / inverted dates
        portalUpload(token, file("coi.pdf", PDF), fields(coi, "2027-01-01", "2026-01-01"))
                .andExpect(status().isBadRequest());
        // too large (limit 15 MB)
        portalUpload(token, file("big.pdf", pdfOfSize(15 * 1024 * 1024 + 1)), fields(w9, null, null))
                .andExpect(status().isPayloadTooLarge());
        assertThat(documentRows(vendor)).isZero();
        assertThat(useCount(linkId)).isZero();
        assertThat(count("select count(*) from audit_event where action = 'document.uploaded' and organization_id = ?::uuid",
                orgId)).isZero();
        // still fully usable
        portalUpload(token, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(useCount(linkId)).isEqualTo(1);
    }

    @Test
    void theOrganizationStorageQuotaApplies() throws Exception {
        givenLinkFor("W9");
        int mb = 1024 * 1024; // test profile quota: 40 MB
        portalUpload(token, file("a.pdf", pdfOfSize(15 * mb)), fields(w9, null, null)).andExpect(status().isCreated());
        portalUpload(token, file("b.pdf", pdfOfSize(15 * mb)), fields(w9, null, null)).andExpect(status().isCreated());
        portalUpload(token, file("c.pdf", pdfOfSize(15 * mb)), fields(w9, null, null))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/storage-quota-exceeded")));
        assertThat(documentRows(vendor)).isEqualTo(2);
        assertThat(useCount(linkId)).isEqualTo(2);
    }

    // ---- upload budget ----

    @Test
    void theUploadBudgetIsEnforcedAndShownAsExhausted() throws Exception {
        vendor = createVendor(member, "Acme", null);
        w9 = typeId(member, "W9");
        created = createLink(member, vendor, List.of(w9), "maxUploads", 2);
        token = tokenOf(created);
        linkId = created.get("link").get("id").asString();
        portalUpload(token, file("a.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingUploads").value(1));
        portalUpload(token, file("b.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingUploads").value(0));
        portalUpload(token, file("c.pdf", PDF), fields(w9, null, null)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/portal-upload-limit"));
        portalGet(token).andExpect(status().isOk()).andExpect(jsonPath("$.remainingUploads").value(0))
                .andExpect(jsonPath("$.acceptingUploads").value(false));
        viewer.client().get(linksPath(vendor)).andExpect(jsonPath("$.items[0].status").value("EXHAUSTED"))
                .andExpect(jsonPath("$.items[0].useCount").value(2));
        assertThat(documentRows(vendor)).isEqualTo(2);
    }

    @Test
    void concurrentUploadsCannotExceedTheBudget() throws Exception {
        vendor = createVendor(member, "Acme", null);
        w9 = typeId(member, "W9");
        created = createLink(member, vendor, List.of(w9), "maxUploads", 1);
        token = tokenOf(created);
        linkId = created.get("link").get("id").asString();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = "f" + i + ".pdf";
            results.add(pool.submit(() -> {
                go.await();
                return portalUpload(token, file(name, PDF), fields(w9, null, null)).andReturn().getResponse()
                        .getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(threads - 1);
        assertThat(useCount(linkId)).isEqualTo(1);
        assertThat(count("select count(*) from document where vendor_id = ?::uuid and state = 'CURRENT'", vendor))
                .isEqualTo(1);
        assertThat(documentRows(vendor)).isEqualTo(1); // the losers rolled back entirely (no superseded leftovers)
    }

    // ---- no session, no CSRF, tenant isolation ----

    @Test
    void theSessionOfAStaffBrowserIsIgnoredAndNoCsrfTokenIsNeeded() throws Exception {
        givenLinkFor("W9");
        // "other" is logged in to a DIFFERENT organization and sends NO CSRF header: the link decides the tenant
        ApiClient staffBrowser = other.client();
        staffBrowser.header("X-Portal-Token", token).postMultipartWithoutCsrf(PORTAL_DOCS, file("w9.pdf", PDF), fields(w9, null, null))
                .andExpect(status().isCreated());
        assertThat(count("select count(*) from document where vendor_id = ?::uuid and organization_id = ?::uuid",
                vendor, orgId)).isEqualTo(1);
        assertThat(count("select count(*) from document where organization_id = ?::uuid", other.organizationId()))
                .isZero();
        // and the staff session is not extended into portal data: the audit row is the link's, with no actor
        assertThat(count("select count(*) from audit_event where action = 'document.uploaded' and organization_id = ?::uuid "
                + "and actor_user_id is null", orgId)).isEqualTo(1);
        staffBrowser.header("X-Portal-Token", token).get(PORTAL).andExpect(status().isOk());
    }

    @Test
    void aLinkOfOrganizationACannotTouchOrganizationB() throws Exception {
        givenLinkFor("W9");
        String foreignVendor = createVendor(other, "Foreign", null);
        String foreignW9 = typeId(other, "W9");
        // org B's type id through org A's link
        portalUpload(token, file("w9.pdf", PDF), fields(foreignW9, null, null)).andExpect(status().isBadRequest());
        // a vendor id / org id supplied by the caller is simply not an input
        portalUpload(token, file("w9.pdf", PDF), Map.of("documentTypeId", w9, "vendorId", foreignVendor,
                "organizationId", other.organizationId())).andExpect(status().isCreated());
        assertThat(documentRows(foreignVendor)).isZero();
        assertThat(count("select count(*) from document where organization_id = ?::uuid", other.organizationId()))
                .isZero();
        assertThat(documentRows(vendor)).isEqualTo(1);
        // and org B's own link only ever shows org B
        String foreignToken = tokenOf(createLink(other, foreignVendor, List.of(foreignW9)));
        String foreignInfo = portalGet(foreignToken).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(foreignInfo).contains("Other Portal Org").doesNotContain("Portal Org " + orgId)
                .doesNotContain("Acme Roofing");
    }

    @Test
    void portalMethodsAndShapesOutsideTheContractAreNotPubliclyReachable() throws Exception {
        givenLinkFor("W9");
        ApiClient anon = new ApiClient(mvc, json).primeCsrf().header("X-Portal-Token", token);
        // not on the permitAll list -> 401 (never reaches a handler that could confirm the token)
        anon.perform(HttpMethod.DELETE, PORTAL, null, true).andExpect(status().isUnauthorized());
        anon.perform(HttpMethod.PUT, PORTAL, Map.of(), true).andExpect(status().isUnauthorized());
        anon.perform(HttpMethod.GET, PORTAL_DOCS, null, true)
                .andExpect(status().isUnauthorized());
        anon.perform(HttpMethod.GET, PORTAL + "/anything/else", null, true)
                .andExpect(status().isUnauthorized());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static String hashOf(String token) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** requestId and instance legitimately differ per request/token; everything else must be identical. */
    private String maskVolatile(String body) {
        JsonNode node = json.readTree(body);
        return ((tools.jackson.databind.node.ObjectNode) node).without(Set.of("requestId", "instance")).toString();
    }
}
