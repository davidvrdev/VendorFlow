package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.domain.Document;
import com.vendorflow.document.infrastructure.DocumentRepository;
import com.vendorflow.document.infrastructure.storage.FileScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Two failure hooks of the upload pipeline, in their own application context (a rejecting scanner replaces the
 * no-op one and AuditService is a spy): scanner rejection -> 422 and nothing stored; a failing database transaction
 * -> the already stored object is deleted again.
 */
class DocumentUploadHooksTest extends DocumentTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class RejectingScannerConfig {

        @Bean
        @Primary
        FileScanner rejectingScanner() {
            return (content, mime) -> {
                try {
                    String text = new String(content.readAllBytes(), StandardCharsets.ISO_8859_1);
                    return text.contains("EICAR-TEST") ? FileScanner.Result.rejected("test signature")
                            : FileScanner.Result.ok();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
    }

    @MockitoSpyBean DocumentRepository documentRepository;

    List<Path> storedFiles() throws IOException {
        Path orgDir = STORAGE_ROOT.resolve("org").resolve(orgId);
        if (!Files.exists(orgDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(orgDir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void scannerRejectionIs422AndNothingIsStoredOrRecorded() throws Exception {
        String vendor = createVendor(member, "Scan");
        String w9 = typeId(member, "W9");
        byte[] infected = concat(PDF, "EICAR-TEST".getBytes());
        upload(member, vendor, file("bad.pdf", infected), fields(w9, null, null))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title").value("File rejected"))
                .andExpect(jsonPath("$.requestId").exists());
        assertThat(storedFiles()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
        // a clean file still goes through the same scanner
        upload(member, vendor, file("good.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void objectIsDeletedAgainWhenTheDatabaseTransactionFails() throws Exception {
        String vendor = createVendor(member, "Rollback");
        String w9 = typeId(member, "W9");
        doThrow(new IllegalStateException("simulated failure after the object was stored")).when(documentRepository)
                .saveAndFlush(any(Document.class));

        String body = upload(member, vendor, file("a.pdf", PDF), fields(w9, null, null))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("simulated").doesNotContain("IllegalState");
        assertThat(storedFiles()).as("the object stored before the transaction was removed").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).as("the transaction rolled back").isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'document.superseded' "
                + "and metadata->>'vendorId' = ?", Integer.class, vendor)).isZero();
    }

    @Test
    void aFailedReplacementUploadLeavesTheCurrentDocumentUntouched() throws Exception {
        String vendor = createVendor(member, "Keep current");
        String w9 = typeId(member, "W9");
        String first = uploadOk(member, vendor, file("first.pdf", PDF), fields(w9, null, null)).get("id").asString();
        doThrow(new IllegalStateException("boom")).when(documentRepository).saveAndFlush(any(Document.class));

        upload(member, vendor, file("second.pdf", PDF), fields(w9, null, null))
                .andExpect(status().isInternalServerError());

        assertThat(getDoc(member, first).get("state").asString()).isEqualTo("CURRENT");
        assertThat(storedFiles()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isEqualTo(1);
    }
}
