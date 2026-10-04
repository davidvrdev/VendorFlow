package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ChasingOptOutRateLimitTest extends ChasingTestBase {

    @Test
    void optOutIsLimitedPerIp() throws Exception {
        ApiClient c = new ApiClient(mvc, json).remoteAddr("203.0.113.201");
        for (int i = 0; i < 20; i++) {
            int s = c.perform(HttpMethod.GET, "/api/v1/portal/chasing/opt-out", null, false).andReturn().getResponse()
                    .getStatus();
            assertThat(s).isEqualTo(404);
        }
        c.perform(HttpMethod.POST, "/api/v1/portal/chasing/opt-out", null, false)
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}
