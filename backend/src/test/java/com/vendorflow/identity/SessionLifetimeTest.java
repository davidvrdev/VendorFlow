package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** L4: sessions have an absolute lifetime (default 7 days since authentication) on top of the sliding idle timeout. */
class SessionLifetimeTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    @Test
    void sessionIsValidWithinSevenDaysAndRejectedAfterwardsEvenWhenUsedContinuously() throws Exception {
        Account account = accounts.signup("Lifetime Org");
        ApiClient browser = account.client();
        browser.get("/api/v1/me").andExpect(status().isOk());

        // Active every day (the sliding idle timeout never fires), but the absolute cap still applies.
        for (int day = 1; day <= 6; day++) {
            clock.advance(Duration.ofDays(1));
            browser.get("/api/v1/me").andExpect(status().isOk());
        }
        clock.advance(Duration.ofDays(1).plusMinutes(1)); // now 7 days + 1 minute after signup
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
        // The server-side session is gone: replaying the same cookie keeps failing.
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void loginStartsANewAbsoluteWindowAndWorksWithAnExpiredCookieStillPresent() throws Exception {
        Account account = accounts.signup("Relogin Org");
        ApiClient browser = account.client();
        clock.advance(Duration.ofDays(8));
        // Public endpoints keep working for a browser that still holds the expired cookie.
        browser.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isOk());
        browser.get("/api/v1/me").andExpect(status().isOk());
        clock.advance(Duration.ofDays(6));
        browser.get("/api/v1/me").andExpect(status().isOk());
        clock.advance(Duration.ofDays(2));
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedSessionWithoutTimestampFailsClosed() throws Exception {
        Account account = accounts.signup("No Timestamp Org");
        account.client().get("/api/v1/me").andExpect(status().isOk());
        int removed = jdbc.update("delete from spring_session_attributes where attribute_name = 'VF_AUTHENTICATED_AT'"
                + " and session_primary_id in (select primary_id from spring_session where principal_name = ?)",
                account.userId());
        assertThat(removed).isEqualTo(1);
        account.client().get("/api/v1/me").andExpect(status().isUnauthorized());
    }
}
