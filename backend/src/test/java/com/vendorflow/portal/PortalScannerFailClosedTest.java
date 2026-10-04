package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.document.infrastructure.storage.ClamAvFileScanner;
import com.vendorflow.document.infrastructure.storage.FileScanner;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Real ClamAvFileScanner with nothing listening: a vendor upload must be refused (503), nothing stored or consumed. */
class PortalScannerFailClosedTest extends PortalTestBase {

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
    void scannerDownMeansNoPortalUploadAndNoLeakedDetail() throws Exception {
        String vendor = createVendor(member, "ScanDown", null);
        String w9 = typeId(member, "W9");
        var created = createLink(member, vendor, List.of(w9));
        String body = portalUpload(tokenOf(created), file("a.pdf", PDF), fields(w9, null, null))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("File scanning unavailable"))
                .andExpect(jsonPath("$.requestId").exists()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("127.0.0.1").doesNotContain("Connect");
        assertThat(documentRows(vendor)).isZero();
        assertThat(useCount(created.get("link").get("id").asString())).isZero();
    }
}
