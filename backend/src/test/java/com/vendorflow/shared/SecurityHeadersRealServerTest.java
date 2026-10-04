package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.RealServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Headers only a real Tomcat can show: no Server / X-Powered-By, and HSTS driven by X-Forwarded-Proto. */
class SecurityHeadersRealServerTest extends RealServerTest {

    private ResponseEntity<Void> get(String path, String forwardedProto) {
        RestClient.RequestHeadersSpec<?> spec = RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build()
                .get().uri(path);
        if (forwardedProto != null) {
            spec = spec.header("X-Forwarded-Proto", forwardedProto);
        }
        return spec.retrieve().toBodilessEntity();
    }

    @Test
    void noServerOrPoweredByHeaderOnSuccessOrErrorResponses() {
        for (String path : new String[] {"/actuator/health", "/api/v1/me", "/api/v1/nope", "/api/v1/auth/csrf"}) {
            HttpHeaders headers = get(path, null).getHeaders();
            assertThat(headers.containsHeader("Server")).as(path).isFalse();
            assertThat(headers.containsHeader("X-Powered-By")).as(path).isFalse();
            assertThat(headers.getFirst("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
        }
    }

    @Test
    void csrfCookieOverRealTomcatIsSameSiteLaxAndReadableByScript() {
        String cookie = get("/api/v1/auth/csrf", "https").getHeaders().get("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        System.out.println("CSRF Set-Cookie over real Tomcat: " + cookie);
        assertThat(cookie).contains("SameSite=Lax").contains("Secure").doesNotContain("HttpOnly");
    }

    @Test
    void hstsFollowsTheForwardedProtocolFromATrustedProxyOnly() {
        // The test JVM connects from 127.0.0.1, a trusted proxy (RemoteIpValve), so X-Forwarded-Proto is honoured.
        assertThat(get("/api/v1/auth/csrf", "https").getHeaders().getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000 ; includeSubDomains");
        assertThat(get("/api/v1/auth/csrf", null).getHeaders().getFirst("Strict-Transport-Security")).isNull();
    }
}
