package com.vendorflow.e2e;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** In every profile except e2e the test mailbox must not exist and must not be reachable anonymously. */
class MailboxNotAvailableTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anonymousRequestIsRejectedAsUnauthenticated() throws Exception {
        new ApiClient(mvc, json).get("/api/test/mailbox?to=someone@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    void evenALoggedInUserFindsNoSuchEndpoint() throws Exception {
        new TestAccounts(mvc, json, jdbc).signup("No Mailbox Org").client()
                .get("/api/test/mailbox?to=someone@example.com").andExpect(status().isNotFound());
    }
}
