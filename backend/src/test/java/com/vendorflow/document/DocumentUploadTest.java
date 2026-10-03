package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.infrastructure.storage.ObjectStorage;
import com.vendorflow.support.ApiClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** Upload pipeline: validation order and statuses, spoofing, limits, storage, supersede, concurrency, authz, tenants. */
class DocumentUploadTest extends DocumentTestBase {

    static final long MAX = 15L * 1024 * 1024;

    @Autowired ObjectStorage storage;

    // ---- happy paths ----

    @Test
    void uploadsPdfPngAndJpegWithTheDetectedMimeType() throws Exception {
        String vendor = createVendor(member, "Happy");
        String w9 = typeId(member, "W9");
        String contract = typeId(member, "CONTRACT");
        String other = typeId(member, "OTHER");

        upload(member, vendor, file("w9.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.state").value("CURRENT"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.originalFilename").value("w9.pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.vendorId").value(vendor))
                .andExpect(jsonPath("$.documentType.code").value("W9"))
                .andExpect(jsonPath("$.documentType.hasExpiration").value(false))
                .andExpect(jsonPath("$.issueDate").doesNotExist())
                .andExpect(jsonPath("$.expirationDate").doesNotExist())
                .andExpect(jsonPath("$.uploadedBy.fullName").value("Mia Member"))
                .andExpect(jsonPath("$.uploadedAt").exists())
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.reviewNote").doesNotExist())
                // internals never leave the server
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.sha256").doesNotExist());
        upload(member, vendor, file("scan.png", PNG), fields(contract, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/png"));
        upload(member, vendor, file("photo.JPG", JPEG), fields(other, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.originalFilename").value("photo.JPG"));
        upload(member, vendor, file("photo2.jpeg", JPEG), fields(typeId(member, "COI"), "2026-01-01", "2027-01-01"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.issueDate").value("2026-01-01"))
                .andExpect(jsonPath("$.expirationDate").value("2027-01-01"));
    }

    @Test
    void ownerAndAdminCanUploadToo() throws Exception {
        String vendor = createVendor(owner, "Roles");
        String w9 = typeId(owner, "W9");
        upload(owner, vendor, file("a.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        upload(admin, vendor, file("b.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
    }

    @Test
    void clientContentTypeIsIgnoredAndTheStoredMimeTypeIsDetected() throws Exception {
        String vendor = createVendor(member, "Mime");
        String w9 = typeId(member, "W9");
        // Claims to be HTML; the bytes are a PDF.
        upload(member, vendor, new MockMultipartFile("file", "a.pdf", "text/html", PDF), fields(w9, null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mimeType").value("application/pdf"));
        // Claims to be a PDF; the bytes are a PNG and the extension says png.
        upload(member, vendor, new MockMultipartFile("file", "b.png", "application/pdf", PNG),
                fields(typeId(member, "CONTRACT"), null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mimeType").value("image/png"));
        // A correct Content-Type does not rescue wrong bytes.
        upload(member, vendor, new MockMultipartFile("file", "c.pdf", "application/pdf", PNG), fields(w9, null, null))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void sha256AndSizeAreComputedFromTheStreamedBytesAndTheObjectIsStoredUnderAServerKey() throws Exception {
        String vendor = createVendor(member, "Hash");
        byte[] content = pdfOfSize(200_000);
        JsonNode doc = uploadOk(member, vendor, file("../../etc/passwd.pdf", content),
                fields(typeId(member, "W9"), null, null));
        String id = doc.get("id").asString();

        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Map<String, Object> row = jdbc.queryForMap("select sha256, size_bytes, storage_key, original_filename, "
                + "mime_type from document where id = ?::uuid", id);
        assertThat(row.get("sha256")).isEqualTo(expectedSha);
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(content.length);
        assertThat(doc.get("sizeBytes").asLong()).isEqualTo(content.length);

        // Server-generated key: org/{orgId}/doc/{documentId}; no part of the user-supplied name is in it.
        String key = (String) row.get("storage_key");
        assertThat(key).isEqualTo("org/" + orgId + "/doc/" + id);
        assertThat(key).doesNotContain("passwd").doesNotContain("..").doesNotContain("pdf");
        // The display name was sanitized (last path segment only).
        assertThat(row.get("original_filename")).isEqualTo("passwd.pdf");
        assertThat(doc.get("originalFilename").asString()).isEqualTo("passwd.pdf");

        try (InputStream in = storage.get(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void hostileFileNamesAreSanitizedButUploadStillWorks() throws Exception {
        String vendor = createVendor(member, "Names");
        String w9 = typeId(member, "W9");
        upload(member, vendor, file("C:\\x\\y.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("y.pdf"));
        upload(member, vendor, file("\u202Egnp.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("gnp.pdf"));
        upload(member, vendor, file("a".repeat(600) + ".pdf", PDF), fields(w9, null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("a".repeat(251) + ".pdf"));
    }

    // ---- validation: status codes and order ----

    @Test
    void missingOrEmptyFileIsA400OnFieldFile() throws Exception {
        String vendor = createVendor(member, "NoFile");
        String w9 = typeId(member, "W9");
        upload(member, vendor, null, fields(w9, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        upload(member, vendor, file("empty.pdf", new byte[0]), fields(w9, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("file"));
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }

    @Test
    void nonMultipartRequestIsRejected() throws Exception {
        String vendor = createVendor(member, "Json");
        member.client().post(VENDORS + "/" + vendor + "/documents", Map.of("documentTypeId", typeId(member, "W9")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void fileJustUnderTheLimitIsAcceptedAndJustOverIs413() throws Exception {
        String vendor = createVendor(member, "Big");
        String w9 = typeId(member, "W9");
        upload(member, vendor, file("max.pdf", pdfOfSize((int) MAX)), fields(w9, null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sizeBytes").value(MAX));
        upload(member, vendor, file("over.pdf", pdfOfSize((int) MAX + 1)), fields(w9, null, null))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.title").value("File too large"))
                .andExpect(jsonPath("$.requestId").exists());
        // The rejected upload stored nothing and changed nothing.
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isEqualTo(1);
    }

    @Test
    void extensionMustBePdfPngJpgOrJpeg() throws Exception {
        String vendor = createVendor(member, "Ext");
        String w9 = typeId(member, "W9");
        for (String name : List.of("run.exe", "page.html", "image.svg", "doc.docx", "noextension", "trailing.",
                "invoice.pdf.exe", "archive.zip", "x.PDF.txt", "", "dir/")) {
            upload(member, vendor, file(name, PDF), fields(w9, null, null))
                    .andExpect(status().isUnsupportedMediaType());
        }
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }

    @Test
    void magicBytesMustMatchTheExtensionFamily() throws Exception {
        String vendor = createVendor(member, "Magic");
        String w9 = typeId(member, "W9");
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
        byte[] tinyPdf = "%PDF".getBytes(); // shorter than the signature
        upload(member, vendor, file("png-as.pdf", PNG), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("pdf-as.png", PDF), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("pdf-as.jpg", PDF), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("jpg-as.pdf", JPEG), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("html.pdf", html), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("html.png", html), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("html.jpg", html), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("exe.pdf", exe), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        upload(member, vendor, file("short.pdf", tinyPdf), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
        // Leading bytes before the signature (e.g. a BOM or whitespace) are not tolerated.
        upload(member, vendor, file("lead.pdf", concat(" ".getBytes(), PDF)), fields(w9, null, null))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }

    @Test
    void validationOrderIsFileThenSizeThenExtensionThenMagicThenTypeThenDates() throws Exception {
        String vendor = createVendor(member, "Order");
        String bogusType = "00000000-0000-0000-0000-000000000000";
        // size beats extension
        upload(member, vendor, file("big.exe", pdfOfSize((int) MAX + 1)), fields(bogusType, "nope", null))
                .andExpect(status().isPayloadTooLarge());
        // extension beats magic bytes, type and dates
        upload(member, vendor, file("x.exe", "not a pdf".getBytes()), fields(bogusType, "nope", null))
                .andExpect(status().isUnsupportedMediaType());
        // magic bytes beat type and dates
        upload(member, vendor, file("x.pdf", "not a pdf".getBytes()), fields(bogusType, "nope", null))
                .andExpect(status().isUnsupportedMediaType());
        // type beats dates
        upload(member, vendor, file("x.pdf", PDF), fields(bogusType, "nope", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        // dates last
        upload(member, vendor, file("x.pdf", PDF), fields(typeId(member, "W9"), "nope", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("issueDate"));
    }

    @Test
    void documentTypeMustBeAnActiveTypeOfTheOrganization() throws Exception {
        String vendor = createVendor(member, "Types");
        String ownTypeInactive = typeId(member, "CONTRACT");
        owner.client().patch("/api/v1/document-types/" + ownTypeInactive, Map.of("active", false))
                .andExpect(status().isOk());
        String foreignType = typeId(other, "W9");

        List<String> badTypes = new ArrayList<>(List.of("not-a-uuid", "00000000-0000-0000-0000-000000000000",
                foreignType, ownTypeInactive));
        for (String type : badTypes) {
            upload(member, vendor, file("x.pdf", PDF), fields(type, null, null)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        }
        // missing documentTypeId
        upload(member, vendor, file("x.pdf", PDF), fields(null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }

    @Test
    void datesAreValidatedStrictly() throws Exception {
        String vendor = createVendor(member, "Dates");
        String coi = typeId(member, "COI");
        String w9 = typeId(member, "W9");
        MockMultipartFile pdf = file("c.pdf", PDF);

        // COI has an expiration: it is required
        upload(member, vendor, pdf, fields(coi, "2026-01-01", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        upload(member, vendor, pdf, fields(coi, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        // bad formats and impossible dates
        for (String bad : List.of("01/02/2026", "2026-13-01", "2026-02-30", "2026-1-1", "20260101", "tomorrow",
                "2026-01-01T00:00:00Z")) {
            upload(member, vendor, pdf, fields(coi, "2026-01-01", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
            upload(member, vendor, pdf, fields(w9, bad, null)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("issueDate"));
        }
        // range
        upload(member, vendor, pdf, fields(coi, null, "1989-12-31")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        upload(member, vendor, pdf, fields(coi, null, "2101-01-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));
        // ordering
        upload(member, vendor, pdf, fields(coi, "2027-01-02", "2027-01-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("issueDate"));
        // boundaries and equality are fine
        upload(member, vendor, pdf, fields(coi, "1990-01-01", "2100-12-31")).andExpect(status().isCreated());
        upload(member, vendor, pdf, fields(coi, "2027-01-01", "2027-01-01")).andExpect(status().isCreated());
        // blank values mean "absent"
        upload(member, vendor, pdf, fields(w9, "", "")).andExpect(status().isCreated());
    }

    // ---- supersede ----

    @Test
    void newUploadSupersedesTheCurrentDocumentOfTheSameRequirement() throws Exception {
        String vendor = createVendor(member, "Supersede");
        String coi = typeId(member, "COI");
        JsonNode first = uploadPdf(member, vendor, coi, "first.pdf");
        JsonNode second = uploadPdf(member, vendor, coi, "second.pdf");

        assertThat(getDoc(member, first.get("id").asString()).get("state").asString()).isEqualTo("SUPERSEDED");
        assertThat(getDoc(member, second.get("id").asString()).get("state").asString()).isEqualTo("CURRENT");
        assertThat(jdbc.queryForObject("select superseded_by_document_id::text from document where id = ?::uuid",
                String.class, first.get("id").asString())).isEqualTo(second.get("id").asString());

        member.client().get(VENDORS + "/" + vendor + "/documents").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(second.get("id").asString()));
        member.client().get(VENDORS + "/" + vendor + "/documents?includeHistory=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.get("id").asString()))
                .andExpect(jsonPath("$[1].id").value(first.get("id").asString()));

        // nothing is overwritten: both objects are still in storage; both uploads and the supersede are audited
        assertThat(storage.exists("org/" + orgId + "/doc/" + first.get("id").asString())).isTrue();
        assertThat(storage.exists("org/" + orgId + "/doc/" + second.get("id").asString())).isTrue();
        assertThat(auditCount("document.uploaded", first.get("id").asString())).isEqualTo(1);
        assertThat(auditCount("document.uploaded", second.get("id").asString())).isEqualTo(1);
        assertThat(auditCount("document.superseded", first.get("id").asString())).isEqualTo(1);
        assertThat(auditCount("document.superseded", second.get("id").asString())).isZero();
        // vendorId is in the metadata (it drives the vendor history)
        assertThat(jdbc.queryForObject("select metadata->>'vendorId' from audit_event where action = "
                + "'document.superseded' and entity_id = ?::uuid", String.class, first.get("id").asString()))
                .isEqualTo(vendor);
    }

    @Test
    void differentTypesAndDifferentVendorsDoNotSupersedeEachOther() throws Exception {
        String v1 = createVendor(member, "V1");
        String v2 = createVendor(member, "V2");
        String w9 = typeId(member, "W9");
        String contract = typeId(member, "CONTRACT");
        JsonNode a = uploadOk(member, v1, file("a.pdf", PDF), fields(w9, null, null));
        JsonNode b = uploadOk(member, v1, file("b.pdf", PDF), fields(contract, null, null));
        JsonNode c = uploadOk(member, v2, file("c.pdf", PDF), fields(w9, null, null));
        for (JsonNode doc : List.of(a, b, c)) {
            assertThat(getDoc(member, doc.get("id").asString()).get("state").asString()).isEqualTo("CURRENT");
        }
    }

    @Test
    void archivedCurrentDocumentIsNotPromotedBackAndANewUploadBecomesCurrent() throws Exception {
        String vendor = createVendor(admin, "Archived");
        String w9 = typeId(admin, "W9");
        JsonNode first = uploadOk(admin, vendor, file("a.pdf", PDF), fields(w9, null, null));
        admin.client().post(DOCUMENTS + "/" + first.get("id").asString() + "/archive", null)
                .andExpect(status().isOk());
        JsonNode second = uploadOk(admin, vendor, file("b.pdf", PDF), fields(w9, null, null));
        assertThat(getDoc(admin, first.get("id").asString()).get("state").asString()).isEqualTo("ARCHIVED");
        assertThat(getDoc(admin, second.get("id").asString()).get("state").asString()).isEqualTo("CURRENT");
        assertThat(auditCount("document.superseded", first.get("id").asString())).isZero();
    }

    @Test
    void concurrentUploadsForTheSameRequirementLeaveExactlyOneCurrentDocument() throws Exception {
        String vendor = createVendor(member, "Race");
        String coi = typeId(member, "COI");
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                ApiClient client = member.client().copy();
                int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return client.postMultipart(VENDORS + "/" + vendor + "/documents",
                            file("race-" + index + ".pdf", PDF), fields(coi, "2026-01-01", "2027-01-01"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid and state = 'CURRENT'",
                Integer.class, vendor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid and state = "
                + "'SUPERSEDED'", Integer.class, vendor)).isEqualTo(n - 1);
        // every object stays in storage and every row has its object
        List<String> keys = jdbc.queryForList("select storage_key from document where vendor_id = ?::uuid",
                String.class, vendor);
        assertThat(keys).hasSize(n).allMatch(storage::exists);
    }

    // ---- authorization and tenants ----

    @Test
    void viewerCannotUploadAndNothingIsStored() throws Exception {
        String vendor = createVendor(member, "Viewer");
        upload(viewer, vendor, file("a.pdf", PDF), fields(typeId(viewer, "W9"), null, null))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }

    @Test
    void unauthenticatedUploadIs401() throws Exception {
        String vendor = createVendor(member, "Anon");
        ApiClient anonymous = accounts.newClient();
        anonymous.postMultipart(VENDORS + "/" + vendor + "/documents", file("a.pdf", PDF),
                fields(typeId(member, "W9"), null, null)).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadWithoutCsrfHeaderIsRejected() throws Exception {
        String vendor = createVendor(member, "Csrf");
        ApiClient noCsrf = member.client().copy();
        noCsrf.removeCookie(ApiClient.CSRF_COOKIE);
        noCsrf.postMultipart(VENDORS + "/" + vendor + "/documents", file("a.pdf", PDF),
                fields(typeId(member, "W9"), null, null)).andExpect(status().isForbidden());
    }

    @Test
    void userOfAnotherOrganizationGets404WhenUploadingToOrAListingOurVendor() throws Exception {
        String vendor = createVendor(member, "Tenant");
        String theirType = typeId(other, "W9");
        upload(other, vendor, file("a.pdf", PDF), fields(theirType, null, null)).andExpect(status().isNotFound());
        other.client().get(VENDORS + "/" + vendor + "/documents").andExpect(status().isNotFound());
        other.client().get(VENDORS + "/" + vendor + "/documents?includeHistory=true").andExpect(status().isNotFound());
        // nonexistent vendor: identical
        upload(other, "00000000-0000-0000-0000-000000000000", file("a.pdf", PDF), fields(theirType, null, null))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }

    @Test
    void ownVendorWithAnotherOrganizationsDocumentTypeIsA400() throws Exception {
        String theirVendor = createVendor(other, "Theirs");
        String myVendor = createVendor(member, "Mine");
        String theirType = typeId(other, "W9");
        upload(member, myVendor, file("a.pdf", PDF), fields(theirType, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        assertThat(theirVendor).isNotBlank();
    }

    @Test
    void uploadsAreRateLimitedPerIpAt30PerMinute() throws Exception {
        String vendor = createVendor(member, "Limit");
        ApiClient client = member.client().remoteAddr("203.0.113.77");
        // Cheap requests (no file -> 400) still count against the budget.
        for (int i = 0; i < 30; i++) {
            client.postMultipart(VENDORS + "/" + vendor + "/documents", null, Map.of())
                    .andExpect(status().isBadRequest());
        }
        client.postMultipart(VENDORS + "/" + vendor + "/documents", null, Map.of())
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        // The variable path segment does not escape the rule: another vendor id shares the same budget...
        client.postMultipart(VENDORS + "/00000000-0000-0000-0000-000000000000/documents", null, Map.of())
                .andExpect(status().isTooManyRequests());
        // ...a different IP (and user: the member also has a per-user budget) has its own budget, and other endpoints are not limited by this rule.
        admin.client().remoteAddr("203.0.113.78").postMultipart(VENDORS + "/" + vendor + "/documents", null, Map.of())
                .andExpect(status().isBadRequest());
        client.get(VENDORS + "/" + vendor + "/documents").andExpect(status().isOk());
    }
}
