package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.RealServerTest;
import com.vendorflow.support.TestAccounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L1: the CSRF token is read ONLY from the X-XSRF-TOKEN header. Real Tomcat, because MockMvc does not parse multipart
 * with the container. Reliable assertion: the multipart spool directory ({@code spring.servlet.multipart.location}) is
 * pointed at a private directory watched with a WatchService. A CONTROL upload (valid header) must create spool files
 * there (proving the watcher sees Tomcat parsing), while a token-less upload, even one carrying the token as the
 * {@code _csrf} form parameter, must create none and returns 403 (the body was never parsed).
 */
class CsrfHeaderOnlyRealServerTest extends RealServerTest {

    static final Path SPOOL = createSpool();

    static Path createSpool() {
        try {
            return Files.createTempDirectory("vendorflow-multipart-spool");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void spool(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", SPOOL::toString);
    }

    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, String> cookies = new HashMap<>();

    RestClient client() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
    }

    void absorb(ResponseEntity<?> response) {
        for (String header : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
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
        var spec = client().method(HttpMethod.valueOf(method)).uri(path).header(HttpHeaders.COOKIE, cookieHeader())
                .header("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""));
        ResponseEntity<String> response = (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
                .retrieve().toEntity(String.class);
        absorb(response);
        return response;
    }

    ResponseEntity<String> multipart(String vendorId, String typeId, boolean header, boolean tokenAsParameter) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("documentTypeId", typeId);
        if (tokenAsParameter) {
            parts.add("_csrf", cookies.get("XSRF-TOKEN"));
        }
        parts.add("file", new ByteArrayResource(DocumentTestBase.pdfOfSize(3 * 1024 * 1024)) {
            @Override
            public String getFilename() {
                return "x.pdf";
            }
        });
        var spec = client().post().uri("/api/v1/vendors/" + vendorId + "/documents")
                .header(HttpHeaders.COOKIE, cookieHeader());
        if (header) {
            spec = spec.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        return spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(String.class);
    }

    /** Number of spool entries created while {@code action} ran (events are polled shortly after it returns). */
    int spoolEntriesCreatedDuring(Runnable action) throws Exception {
        try (WatchService watcher = SPOOL.getFileSystem().newWatchService()) {
            SPOOL.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            action.run();
            int created = 0;
            WatchKey key = watcher.poll(1, TimeUnit.SECONDS);
            while (key != null) {
                created += key.pollEvents().size();
                key.reset();
                key = watcher.poll(300, TimeUnit.MILLISECONDS);
            }
            return created;
        }
    }

    @Test
    void multipartWithoutTheHeaderIs403BeforeTomcatParsesTheBody() throws Exception {
        absorb(client().get().uri("/api/v1/auth/csrf").retrieve().toBodilessEntity());
        sendJson("POST", "/api/v1/auth/signup", TestAccounts.signupBody(TestAccounts.uniqueEmail(),
                TestAccounts.PASSWORD, "Csrf User", "Csrf Org " + UUID.randomUUID()));
        String vendorId = json.readTree(sendJson("POST", "/api/v1/vendors", Map.of("companyName", "Csrf "
                + UUID.randomUUID())).getBody()).get("id").asString();
        String w9 = null;
        for (JsonNode t : json.readTree(sendJson("GET", "/api/v1/document-types", null).getBody())) {
            if (t.get("code").asString().equals("W9")) {
                w9 = t.get("id").asString();
            }
        }
        String typeId = w9;

        // control: with the header the body IS parsed (spool file appears) and the upload succeeds
        AtomicReference<ResponseEntity<String>> ok = new AtomicReference<>();
        int controlEntries = spoolEntriesCreatedDuring(() -> ok.set(multipart(vendorId, typeId, true, false)));
        assertThat(ok.get().getStatusCode().value()).as(ok.get().getBody()).isEqualTo(201);
        assertThat(controlEntries).as("watcher must observe Tomcat spooling the valid upload").isPositive();

        // no header: 403 and nothing spooled, also when the token is offered as the _csrf form parameter
        for (boolean asParameter : new boolean[] {false, true}) {
            AtomicReference<ResponseEntity<String>> denied = new AtomicReference<>();
            int entries = spoolEntriesCreatedDuring(() -> denied.set(multipart(vendorId, typeId, false, asParameter)));
            assertThat(denied.get().getStatusCode().value()).isEqualTo(403);
            assertThat(entries).as("no multipart spool file for a token-less request (asParameter=%s)", asParameter)
                    .isZero();
        }
        assertThat(json.readTree(sendJson("GET", "/api/v1/vendors/" + vendorId + "/documents", null).getBody()))
                .hasSize(1);
    }
}
