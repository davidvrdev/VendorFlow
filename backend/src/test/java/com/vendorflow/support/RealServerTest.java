package com.vendorflow.support;

import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Base for the few tests that need REAL embedded Tomcat (random port, real HTTP): MockMvc never runs Tomcat valves,
 * so RemoteIpValve (trusted-proxy / X-Forwarded-For handling) can only be tested this way.
 *
 * <p>Each distinct configuration starts its own context and server (a few seconds), so keep these to a minimum.
 * On Windows/JDK 25, embedded Tomcat needs a usable {@code jdk.net.unixdomain.tmpdir}; the pom profile
 * {@code windows-uds-tmpdir} sets it for surefire automatically
 * (docs/DEV_SETUP.md); Linux CI needs nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class RealServerTest extends IntegrationTest {

    @LocalServerPort
    protected int port;

    /**
     * POSTs a wrong-password login from the test JVM (peer address 127.0.0.1) with the given X-Forwarded-For (may be
     * null) and returns the HTTP status. The rate limiter runs before CSRF, so: 403 (CSRF) while within budget, 429
     * once the per-IP budget for the key it derived is exhausted. We deliberately do not need a session.
     */
    protected int loginStatus(String forwardedFor) {
        RestClient.RequestBodySpec request = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build()
                .post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        return request.body(Map.of("email", "x@example.com", "password", "Wrong-Password-123"))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }
}
