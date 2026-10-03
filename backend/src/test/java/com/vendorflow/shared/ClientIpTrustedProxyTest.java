package com.vendorflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.support.RealServerTest;
import org.junit.jupiter.api.Test;

/**
 * H1 (real Tomcat): the peer is 127.0.0.1, a trusted proxy under the default {@code internal-proxies}, so Tomcat's
 * RemoteIpValve walks X-Forwarded-For right-to-left and the client IP is the entry appended by OUR proxy, never the
 * client-controlled leftmost one.
 */
class ClientIpTrustedProxyTest extends RealServerTest {

    @Test
    void rotatingTheSpoofedLeftmostForwardedForEntryDoesNotResetTheLimit() {
        String real = "198.51.100.77"; // what our trusted proxy appended
        for (int i = 0; i < 10; i++) { // login budget = 10 per IP per minute
            assertThat(loginStatus("203.0.113." + (i + 1) + ", " + real)).as("request %d", i + 1).isEqualTo(403);
        }
        // New spoofed leftmost value each time, same real client: still limited. With ForwardedHeaderFilter
        // ("framework") the leftmost value was used and every request would have had a fresh budget.
        assertThat(loginStatus("203.0.113.200, " + real)).isEqualTo(429);
        assertThat(loginStatus("203.0.113.201, " + real)).isEqualTo(429);
        assertThat(loginStatus("203.0.113.9, " + real + ", 10.9.9.9")).as("trusted hops on the right are skipped").isEqualTo(429);

        // A different real client (rightmost) has its own budget.
        assertThat(loginStatus("203.0.113.200, 198.51.100.78")).isEqualTo(403);
    }

    @Test
    void withoutForwardedForTheTcpPeerIsTheKey() {
        for (int i = 0; i < 10; i++) {
            assertThat(loginStatus(null)).isEqualTo(403);
        }
        assertThat(loginStatus(null)).isEqualTo(429);
    }
}
