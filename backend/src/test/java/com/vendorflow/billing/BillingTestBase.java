package com.vendorflow.billing;

import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Billing enabled with test keys and a fake Stripe API. All subclasses share one Spring context. */
@Import(BillingTestConfig.class)
@TestPropertySource(properties = {
        "vendorflow.billing.enabled=true",
        "vendorflow.billing.stripe.secret-key=sk_test_not_a_real_key",
        "vendorflow.billing.stripe.webhook-secret=" + BillingTestBase.WEBHOOK_SECRET,
        "vendorflow.billing.stripe.price-id=price_test_standard"
})
public abstract class BillingTestBase extends IntegrationTest {

    public static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests";

    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected FakeBillingGateway gateway;

    protected TestAccounts accounts;

    @BeforeEach
    void setUpBilling() {
        gateway.reset();
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    protected void setStatus(String organizationId, String status) {
        jdbc.update("update subscription set status = ? where organization_id = ?::uuid", status, organizationId);
    }

    protected String statusOf(String organizationId) {
        return jdbc.queryForObject("select status from subscription where organization_id = ?::uuid", String.class,
                organizationId);
    }
}
