package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.RealServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * H1 (real Tomcat): when the immediate peer is NOT a trusted proxy (here only 192.0.2.1 is, the test connects from
 * 127.0.0.1), X-Forwarded-For is ignored entirely, so a direct attacker cannot pick their own rate-limit key.
 * Also proves {@code server.tomcat.remoteip.internal-proxies} (TRUSTED_PROXIES_REGEX) is honoured.
 */
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=192[.]0[.]2[.]1")
class ClientIpUntrustedPeerTest extends RealServerTest {

    @Test
    void forwardedForFromAnUntrustedPeerIsIgnored() {
        for (int i = 0; i < 10; i++) {
            assertThat(loginStatus("203.0.113." + (i + 1))).as("request %d", i + 1).isEqualTo(403);
        }
        assertThat(loginStatus("203.0.113.250")).isEqualTo(429);
        assertThat(loginStatus("203.0.113.251, 198.51.100.1")).isEqualTo(429);
    }
}
