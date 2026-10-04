package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * M3: the guard filter answers BEFORE the multipart body is parsed. A "probe" filter registered after everything else
 * (so it sits just before the DispatcherServlet, where multipart resolution happens) counts the requests that get that
 * far: a bad token must never reach it, hence no temp file, no storage object and no document can result.
 */
@Import(PortalUploadGuardTest.Probe.class)
class PortalUploadGuardTest extends PortalTestBase {

    static final AtomicInteger REACHED_DISPATCH = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {

        @Bean
        FilterRegistrationBean<Filter> portalUploadProbe() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                REACHED_DISPATCH.incrementAndGet();
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/api/v1/portal/link/documents");
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }

    @Autowired com.vendorflow.shared.ratelimit.RateLimitProperties properties;

    @BeforeEach
    void resetProbe() {
        REACHED_DISPATCH.set(0);
    }

    private static long storedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(STORAGE_ROOT)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    /** About 2 MB of valid PDF: large enough that parsing it would visibly cost something. */
    private static MockMultipartFile bigPdf() {
        return file("big.pdf", pdfOfSize(2 * 1024 * 1024));
    }

    @Test
    void badTokensAreRejectedBeforeAnythingIsParsedStoredOrInserted() throws Exception {
        String vendor = createVendor(member, "Guarded", null);
        String w9 = typeId(member, "W9");
        JsonNode created = createLink(member, vendor, List.of(w9));
        String revoked = tokenOf(created);
        member.client().post("/api/v1/vendors/" + vendor + "/upload-links/" + created.get("link").get("id").asString()
                + "/revoke", java.util.Map.of()).andExpect(status().isOk());
        long filesBefore = storedFiles();

        for (String bad : new String[] {null, "", "short", "x".repeat(42), "x".repeat(44), "x".repeat(42) + "!",
                "x".repeat(43), revoked}) {
            String body = portalUpload(bad, bigPdf(), fields(w9, null, null)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://vendorflow.app/problems/portal-link-invalid"))
                    .andExpect(jsonPath("$.title").value("Link not valid"))
                    .andExpect(jsonPath("$.detail").value("This link is invalid or has expired."))
                    .andExpect(jsonPath("$.requestId").exists()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(bad == null || bad.isEmpty() ? "\u0000" : bad);
        }
        assertThat(REACHED_DISPATCH.get()).as("no bad-token request may get as far as multipart resolution").isZero();
        assertThat(storedFiles()).isEqualTo(filesBefore);
        assertThat(documentRows(vendor)).isZero();
        assertThat(useCount(created.get("link").get("id").asString())).isZero();
    }

    @Test
    void theGuardAnswerIsIdenticalToTheServiceAnswerForAnInvalidLink() throws Exception {
        String viaGuard = portalUpload("x".repeat(43), bigPdf(), fields(null, null, null)).andReturn().getResponse()
                .getContentAsString();
        String viaService = portalGet("x".repeat(43)).andReturn().getResponse().getContentAsString();
        JsonNode a = json.readTree(viaGuard);
        JsonNode b = json.readTree(viaService);
        for (String field : new String[] {"type", "title", "status", "detail"}) {
            assertThat(a.get(field)).as(field).isEqualTo(b.get(field));
        }
    }

    @Test
    void aValidLinkReachesTheUploadPipelineAsBefore() throws Exception {
        String vendor = createVendor(member, "Guarded", null);
        String w9 = typeId(member, "W9");
        String token = tokenOf(createLink(member, vendor, List.of(w9)));
        portalUpload(token, bigPdf(), fields(w9, null, null)).andExpect(status().isCreated());
        assertThat(REACHED_DISPATCH.get()).isEqualTo(1);
        assertThat(documentRows(vendor)).isEqualTo(1);
    }
}
