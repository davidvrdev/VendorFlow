package com.vendorflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Outside the e2e profile the reminder-run endpoint does not exist (the test profile is not e2e). */
class ReminderRunNotAvailableTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void noBeanAndALoggedInOwnerGets404() throws Exception {
        assertThat(context.getBeansOfType(ReminderRunController.class)).isEmpty();
        new TestAccounts(mvc, json, jdbc).signup("No Run Org").client().post("/api/test/reminders/run", Map.of())
                .andExpect(status().isNotFound());
    }
}
