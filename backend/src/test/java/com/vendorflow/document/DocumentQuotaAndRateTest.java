package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** M1 storage quota (40 MB in the test profile), L2 per-user upload limit, L3 HEAD on download. */
class DocumentQuotaAndRateTest extends DocumentTestBase {

    static final int MB = 1024 * 1024;

    /** Declares a tiny size but carries a big body: only the streamed byte count can catch it. */
    static final class LyingFile extends MockMultipartFile {
        LyingFile(String name, byte[] content) {
            super("file", name, "application/octet-stream", content);
        }

        @Override
        public long getSize() {
            return 1;
        }
    }

    List<Path> filesOfOrg() throws IOException {
        Path dir = STORAGE_ROOT.resolve("org").resolve(orgId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    int documentRows() {
        return jdbc.queryForObject("select count(*) from document where organization_id = ?::uuid", Integer.class,
                orgId);
    }

    @Test
    void quotaCountsEveryStateAndRejectsWith413AndNothingStored() throws Exception {
        String vendor = createVendor(member, "Quota");
        String w9 = typeId(member, "W9");
        // Same type twice: the first becomes SUPERSEDED but still occupies storage (15 + 15 = 30 of 40 MB).
        uploadOk(member, vendor, file("a.pdf", pdfOfSize(15 * MB)), fields(w9, null, null));
        uploadOk(member, vendor, file("b.pdf", pdfOfSize(15 * MB)), fields(w9, null, null));
        assertThat(filesOfOrg()).hasSize(2);

        // declared-size pre-check: 30 + 15 > 40
        upload(member, vendor, file("c.pdf", pdfOfSize(15 * MB)), fields(w9, null, null))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.title").value("Storage quota exceeded"))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/storage-quota-exceeded")));
        assertThat(documentRows()).isEqualTo(2);
        assertThat(filesOfOrg()).hasSize(2); // no object, no .upload-*.tmp left behind

        // A file that still fits is accepted (30 + 9 <= 40); another organization is unaffected.
        uploadOk(member, vendor, file("d.pdf", pdfOfSize(9 * MB)), fields(w9, null, null));
        String otherVendor = createVendor(other, "Other");
        uploadOk(other, otherVendor, file("o.pdf", pdfOfSize(15 * MB)), fields(typeId(other, "W9"), null, null));
    }

    @Test
    void quotaIsRecheckedWithTheStreamedByteCountWhenTheDeclaredSizeLies() throws Exception {
        String vendor = createVendor(member, "Lying");
        String w9 = typeId(member, "W9");
        uploadOk(member, vendor, file("a.pdf", pdfOfSize(15 * MB)), fields(w9, null, null));
        uploadOk(member, vendor, file("b.pdf", pdfOfSize(15 * MB)), fields(w9, null, null));
        upload(member, vendor, new LyingFile("c.pdf", pdfOfSize(14 * MB)), fields(w9, null, null))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.title").value("Storage quota exceeded"));
        assertThat(documentRows()).isEqualTo(2);
        assertThat(filesOfOrg()).hasSize(2); // the stored object was deleted again
    }

    @Test
    void uploadBudgetIsPerUserNotPerIp() throws Exception {
        String vendor = createVendor(member, "Users");
        String path = VENDORS + "/" + vendor + "/documents";
        // Two users behind the SAME IP each spend 15 requests: the user budgets are independent.
        ApiClient m = member.client().remoteAddr("198.51.100.1");
        ApiClient a = admin.client().remoteAddr("198.51.100.1");
        for (int i = 0; i < 15; i++) {
            m.postMultipart(path, null, Map.of()).andExpect(status().isBadRequest());
            a.postMultipart(path, null, Map.of()).andExpect(status().isBadRequest());
        }
        // Member rotates IPs (each IP budget is nearly empty) and still hits the USER limit at request 31.
        for (int i = 0; i < 15; i++) {
            member.client().remoteAddr("198.51.100." + (10 + i)).postMultipart(path, null, Map.of())
                    .andExpect(status().isBadRequest());
        }
        member.client().remoteAddr("198.51.100.99").postMultipart(path, null, Map.of())
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many requests"));
        // Admin has their own budget (used 15).
        admin.client().remoteAddr("198.51.100.120").postMultipart(path, null, Map.of())
                .andExpect(status().isBadRequest());
    }

    @Test
    void headOnDownloadIs405AndWritesNoAuditRow() throws Exception {
        String vendor = createVendor(member, "Head");
        JsonNode doc = uploadPdf(member, vendor, typeId(member, "COI"), "h.pdf");
        String id = doc.get("id").asString();
        int before = jdbc.queryForObject("select count(*) from audit_event where action = 'document.downloaded'",
                Integer.class);
        viewer.client().perform(HttpMethod.HEAD, DOCUMENTS + "/" + id + "/download", null, true)
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET"));
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'document.downloaded'",
                Integer.class)).isEqualTo(before);
        // GET still works and audits exactly once
        viewer.client().get(DOCUMENTS + "/" + id + "/download").andExpect(status().isOk());
        assertThat(auditCount("document.downloaded", id)).isEqualTo(1);
    }
}
