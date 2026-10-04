package com.vendorflow.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Billing switched on but no Stripe keys: honest 503s, never a fake URL, and the REAL gateway is in use. */
@TestPropertySource(properties = {
        "vendorflow.billing.enabled=true",
        "vendorflow.billing.stripe.secret-key=",
        "vendorflow.billing.stripe.webhook-secret=",
        "vendorflow.billing.stripe.price-id="
})
class BillingNotConfiguredTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void checkoutAndPortalAnswer503BillingNotConfigured() throws Exception {
        Account owner = new TestAccounts(mvc, json, jdbc).signup("No Keys Org");
        owner.client().post("/api/v1/billing/checkout-session", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Billing not configured"))
                .andExpect(jsonPath("$.requestId").exists());
        owner.client().post("/api/v1/billing/portal-session", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Billing not configured"));
        assertThat(jdbc.queryForObject(
                "select stripe_customer_id from subscription where organization_id = ?::uuid", String.class,
                owner.organizationId())).isNull();
        // reading the subscription still works
        owner.client().get("/api/v1/billing/subscription").andExpect(status().isOk());
    }

    @Test
    void authorizationIsCheckedBeforeConfiguration() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        Account owner = accounts.signup("No Keys Roles");
        Account admin = accounts.memberOf(owner.organizationId(), "ADMIN", "Ann Admin");
        admin.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isForbidden());
    }

    @Test
    void webhookAnswers503WithoutASigningSecret() throws Exception {
        mvc.perform(post("/api/v1/webhooks/stripe").contentType("application/json").content("{}")
                        .header("Stripe-Signature", "t=1,v1=abc"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Billing not configured"));
    }
}
