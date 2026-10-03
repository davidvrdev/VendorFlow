package com.vendorflow.e2e;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Mailbox hardening: even under profile e2e the mailbox only answers callers whose address is loopback. */
@ActiveProfiles({"test", "e2e"})
class MailboxLoopbackTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void nonLoopbackCallerGets404() throws Exception {
        for (String remote : new String[] {"203.0.113.9", "10.0.0.5", "192.168.1.20", "2001:db8::1"}) {
            new ApiClient(mvc, json).remoteAddr(remote).get("/api/test/mailbox?to=a@example.com")
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void loopbackCallersAreServed() throws Exception {
        for (String remote : new String[] {"127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1"}) {
            new ApiClient(mvc, json).remoteAddr(remote).get("/api/test/mailbox?to=a@example.com")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }
    }
}
