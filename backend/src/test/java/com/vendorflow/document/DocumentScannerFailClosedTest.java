package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.infrastructure.storage.ClamAvFileScanner;
import com.vendorflow.document.infrastructure.storage.FileScanner;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Real ClamAvFileScanner wired into the upload pipeline with nothing listening: the upload must be refused (503). */
class DocumentScannerFailClosedTest extends DocumentTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class UnreachableClamAv {

        @Bean
        @Primary
        FileScanner unreachableClamAv() throws Exception {
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            return new ClamAvFileScanner("127.0.0.1", port, 500, 500, 4096, 15L << 20);
        }
    }

    @Test
    void scannerDownMeansNoUploadAndNoLeakedDetail() throws Exception {
        String vendor = createVendor(member, "ScanDown");
        String w9 = typeId(member, "W9");
        String body = upload(member, vendor, file("a.pdf", PDF), fields(w9, null, null))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("File scanning unavailable"))
                .andExpect(jsonPath("$.requestId").exists()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("127.0.0.1").doesNotContain("Connect");
        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
    }
}
