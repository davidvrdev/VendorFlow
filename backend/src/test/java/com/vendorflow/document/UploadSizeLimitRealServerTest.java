package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.RealServerTest;
import com.vendorflow.support.TestAccounts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Tomcat, real multipart parsing (MockMvc skips both): the limits of application.yml and the 413 responses.
 * app.documents.max-size = 15 MB; the container limit is 16 MB per file, so a file just over 15 MB must be refused by
 * OUR check and a file far over by the container's, both as the same problem+json 413.
 */
class UploadSizeLimitRealServerTest extends RealServerTest {

    static final long MAX = 15L * 1024 * 1024;

    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, String> cookies = new HashMap<>();

    RestClient client() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
    }

    void absorb(ResponseEntity<?> response) {
        List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        for (String header : setCookies) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
    }

    String cookieHeader() {
        List<String> parts = new ArrayList<>();
        cookies.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join("; ", parts);
    }

    ResponseEntity<String> sendJson(String method, String path, Object body) {
        var spec = client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.COOKIE, cookieHeader()).header("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""));
        ResponseEntity<String> response = (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
                .retrieve().toEntity(String.class);
        absorb(response);
        return response;
    }

    ResponseEntity<String> uploadBytes(String vendorId, String typeId, String filename, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("documentTypeId", typeId);
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        ResponseEntity<String> response = client().post().uri("/api/v1/vendors/" + vendorId + "/documents")
                .header(HttpHeaders.COOKIE, cookieHeader())
                .header("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(String.class);
        absorb(response);
        return response;
    }

    @Test
    void sizeLimitsOverRealHttp() throws Exception {
        absorb(client().get().uri("/api/v1/auth/csrf").retrieve().toBodilessEntity());
        sendJson("POST", "/api/v1/auth/signup", TestAccounts.signupBody(TestAccounts.uniqueEmail(),
                TestAccounts.PASSWORD, "Real Server", "Real Org " + UUID.randomUUID()));
        JsonNode vendor = json.readTree(sendJson("POST", "/api/v1/vendors", Map.of("companyName", "Big files "
                + UUID.randomUUID())).getBody());
        String vendorId = vendor.get("id").asString();
        String w9 = null;
        for (JsonNode t : json.readTree(sendJson("GET", "/api/v1/document-types", null).getBody())) {
            if (t.get("code").asString().equals("W9")) {
                w9 = t.get("id").asString();
            }
        }
        assertThat(w9).isNotNull();

        // exactly the maximum: accepted (streamed through the real multipart parser)
        ResponseEntity<String> ok = uploadBytes(vendorId, w9, "max.pdf", DocumentTestBase.pdfOfSize((int) MAX));
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(201);
        assertThat(json.readTree(ok.getBody()).get("sizeBytes").asLong()).isEqualTo(MAX);

        // one byte over: our own check (below the container limit) -> 413 with our message
        ResponseEntity<String> justOver = uploadBytes(vendorId, w9, "over.pdf", DocumentTestBase.pdfOfSize((int) MAX + 1));
        assertThat(justOver.getStatusCode().value()).isEqualTo(413);
        assertThat(justOver.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(json.readTree(justOver.getBody()).get("detail").asString()).contains("15 MB");

        // far over: stopped by the container multipart limit -> the same 413 problem shape
        ResponseEntity<String> huge = uploadBytes(vendorId, w9, "huge.pdf", DocumentTestBase.pdfOfSize(20 * 1024 * 1024));
        assertThat(huge.getStatusCode().value()).isEqualTo(413);
        assertThat(huge.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode problem = json.readTree(huge.getBody());
        assertThat(problem.get("title").asString()).isEqualTo("File too large");
        assertThat(problem.get("status").asInt()).isEqualTo(413);
        assertThat(problem.has("requestId")).isTrue();
        assertThat(huge.getBody()).doesNotContain("Exception").doesNotContain("at org.");

        // only the 15 MB document exists
        assertThat(json.readTree(sendJson("GET", "/api/v1/vendors/" + vendorId + "/documents", null).getBody()))
                .hasSize(1);
    }
}
