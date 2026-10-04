package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.RealServerTest;
import com.vendorflow.support.TestAccounts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M3 over real HTTP (real Tomcat and real multipart parsing, which MockMvc skips): an oversized or bad-token body is
 * answered from its headers; nothing is parsed or stored.
 */
class PortalUploadGuardRealServerTest extends RealServerTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    RestClient client() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
    }

    ResponseEntity<String> upload(String token, String typeId, int size) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("documentTypeId", typeId);
        parts.add("file", new ByteArrayResource(PortalTestBase.pdfOfSize(size)) {
            @Override
            public String getFilename() {
                return "big.pdf";
            }
        });
        var request = client().post().uri("/api/v1/portal/link/documents").contentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            request = request.header("X-Portal-Token", token);
        }
        return request.body(parts).retrieve().toEntity(String.class);
    }

    @Test
    void badTokenWithALargeBodyIs404AndAnOversizedBodyOfAValidLinkIs413WithNothingStored() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        TestAccounts.Account owner = accounts.signup("Guard Org " + UUID.randomUUID());
        String vendor = json.readTree(owner.client().post("/api/v1/vendors",
                Map.of("companyName", "Guard Co " + UUID.randomUUID())).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        String w9 = null;
        for (JsonNode t : json.readTree(owner.client().get("/api/v1/document-types").andReturn().getResponse()
                .getContentAsString())) {
            if (t.get("code").asString().equals("W9")) {
                w9 = t.get("id").asString();
            }
        }
        JsonNode created = json.readTree(owner.client().post("/api/v1/vendors/" + vendor + "/upload-links",
                Map.of("documentTypeIds", List.of(w9))).andReturn().getResponse().getContentAsString());
        String token = created.get("url").asString().replaceAll(".*#token=", "");
        long linkUse = jdbc.queryForObject("select use_count from vendor_upload_link where vendor_id = ?::uuid",
                Long.class, vendor);

        // bad token, 5 MB body: 404 from the headers alone
        ResponseEntity<String> bad = upload("x".repeat(43), w9, 5 * 1024 * 1024);
        assertThat(bad.getStatusCode().value()).isEqualTo(404);
        assertThat(bad.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(json.readTree(bad.getBody()).get("type").asString())
                .isEqualTo("https://vendorflow.app/problems/portal-link-invalid");
        // no header at all: the same answer
        assertThat(upload(null, w9, 1024 * 1024).getStatusCode().value()).isEqualTo(404);

        // valid token, body above the 17 MB request limit: 413 problem+json, decided from Content-Length
        ResponseEntity<String> huge = upload(token, w9, 18 * 1024 * 1024);
        assertThat(huge.getStatusCode().value()).isEqualTo(413);
        assertThat(huge.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(json.readTree(huge.getBody()).get("title").asString()).isEqualTo("File too large");

        assertThat(jdbc.queryForObject("select count(*) from document where vendor_id = ?::uuid", Integer.class,
                vendor)).isZero();
        assertThat(jdbc.queryForObject("select use_count from vendor_upload_link where vendor_id = ?::uuid",
                Long.class, vendor)).isEqualTo(linkUse);

        // and a normal upload through the real server still works
        ResponseEntity<String> ok = upload(token, w9, 100 * 1024);
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(201);
    }
}
