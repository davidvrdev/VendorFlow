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

/** Outside the e2e profile the chasing-run endpoint does not exist (the test profile is not e2e). */
class ChasingRunNotAvailableTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void noBeanAndALoggedInOwnerGets404() throws Exception {
        assertThat(context.getBeansOfType(ChasingRunController.class)).isEmpty();
        new TestAccounts(mvc, json, jdbc).signup("No Chase Run Org").client().post("/api/test/chasing/run", Map.of())
                .andExpect(status().isNotFound());
    }
}
