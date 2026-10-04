package com.vendorflow.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.ApiClient;
import com.vendorflow.support.TestAccounts.Account;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BillingApiTest extends BillingTestBase {

    // ---- GET /billing/subscription ----

    @Test
    void newOrganizationStartsA14DayTrialAndIsWritable() throws Exception {
        Instant before = Instant.now();
        Account owner = accounts.signup("Trial Org");
        owner.client().get("/api/v1/billing/subscription")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIALING"))
                .andExpect(jsonPath("$.plan").value("standard"))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.currentPeriodEnd").doesNotExist());
        Instant trialEnd = jdbc.queryForObject(
                "select trial_ends_at from subscription where organization_id = ?::uuid", Instant.class,
                owner.organizationId());
        assertThat(Duration.between(before, trialEnd)).isBetween(Duration.ofDays(14).minusMinutes(1),
                Duration.ofDays(14).plusMinutes(1));
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid and action = 'billing.trial.started'""",
                Integer.class, owner.organizationId())).isEqualTo(1);
    }

    @Test
    void trialExpiryIsDerivedFromTheClock() throws Exception {
        Account owner = accounts.signup("Expiring Trial");
        owner.client().get("/api/v1/billing/subscription").andExpect(jsonPath("$.readOnly").value(false));

        // the original session is past its 7-day absolute lifetime by now: sign in again at the advanced time
        clock.advance(Duration.ofDays(13).plusHours(23));
        ApiClient later = accounts.login(owner.email(), owner.password());
        later.get("/api/v1/billing/subscription").andExpect(jsonPath("$.readOnly").value(false));
        later.patch("/api/v1/organization", Map.of("name", "Still Writable")).andExpect(status().isOk());

        clock.advance(Duration.ofHours(2));
        later.get("/api/v1/billing/subscription")
                .andExpect(jsonPath("$.status").value("TRIALING"))
                .andExpect(jsonPath("$.readOnly").value(true));
        later.patch("/api/v1/organization", Map.of("name", "Too Late"))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void everyRoleCanReadButOnlyOwnerCanManage() throws Exception {
        Account owner = accounts.signup("Roles Billing");
        Account admin = accounts.memberOf(owner.organizationId(), "ADMIN", "Ann Admin");
        Account viewer = accounts.memberOf(owner.organizationId(), "VIEWER", "Vic Viewer");
        admin.client().get("/api/v1/billing/subscription").andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false));
        viewer.client().get("/api/v1/billing/subscription").andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false));
        owner.client().get("/api/v1/billing/subscription").andExpect(jsonPath("$.canManage").value(true));
    }

    @Test
    void subscriptionIsPerOrganizationAndRequiresLogin() throws Exception {
        Account a = accounts.signup("Org A");
        Account b = accounts.signup("Org B");
        setStatus(a.organizationId(), "ACTIVE");
        a.client().get("/api/v1/billing/subscription").andExpect(jsonPath("$.status").value("ACTIVE"));
        b.client().get("/api/v1/billing/subscription").andExpect(jsonPath("$.status").value("TRIALING"));
        new ApiClient(mvc, json).get("/api/v1/billing/subscription").andExpect(status().isUnauthorized());
    }

    // ---- POST /billing/checkout-session and /portal-session ----

    @Test
    void ownerGetsCheckoutUrlAndTheCustomerIsCreatedOnceAndStored() throws Exception {
        Account owner = accounts.signup("Checkout Org");
        owner.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://checkout.stripe.test/")));
        String customer = jdbc.queryForObject(
                "select stripe_customer_id from subscription where organization_id = ?::uuid", String.class,
                owner.organizationId());
        assertThat(customer).startsWith("cus_fake_");
        assertThat(gateway.customersCreated).hasSize(1);
        assertThat(gateway.checkoutCustomers).containsExactly(customer);
        // success/cancel URLs come from APP_BASE_URL, the price from configuration
        assertThat(gateway.checkoutUrlsRequested.get(0)).isEqualTo("http://localhost:3000/settings/billing"
                + "?checkout=success|http://localhost:3000/settings/billing?checkout=canceled|price_test_standard");

        // second call and the portal reuse the stored customer
        owner.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isOk());
        owner.client().post("/api/v1/billing/portal-session", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://portal.stripe.test/p/" + customer));
        assertThat(gateway.customersCreated).hasSize(1);
        assertThat(gateway.portalReturnUrls).containsExactly("http://localhost:3000/settings/billing");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where organization_id = ?::uuid and action in
                ('billing.checkout.started', 'billing.portal.opened', 'billing.customer.created')""", Integer.class,
                owner.organizationId())).isEqualTo(4);
    }

    @Test
    void adminIsTheLowestDeniedRoleForCheckoutAndPortal() throws Exception {
        Account owner = accounts.signup("Denied Org");
        Account admin = accounts.memberOf(owner.organizationId(), "ADMIN", "Ann Admin");
        Account member = accounts.memberOf(owner.organizationId(), "MEMBER", "Max Member");
        Account viewer = accounts.memberOf(owner.organizationId(), "VIEWER", "Vic Viewer");
        for (Account denied : new Account[] {admin, member, viewer}) {
            denied.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isForbidden());
            denied.client().post("/api/v1/billing/portal-session", null).andExpect(status().isForbidden());
        }
        assertThat(gateway.customersCreated).isEmpty();
        assertThat(jdbc.queryForObject(
                "select stripe_customer_id from subscription where organization_id = ?::uuid", String.class,
                owner.organizationId())).isNull();
        new ApiClient(mvc, json).primeCsrf().post("/api/v1/billing/checkout-session", null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutOfOneOrganizationNeverTouchesAnother() throws Exception {
        Account a = accounts.signup("Tenant A");
        Account b = accounts.signup("Tenant B");
        // an organization id smuggled in the body or query string is ignored: the tenant comes from the session
        a.client().perform(org.springframework.http.HttpMethod.POST,
                "/api/v1/billing/checkout-session?organizationId=" + b.organizationId(),
                Map.of("organizationId", b.organizationId()), true).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "select stripe_customer_id from subscription where organization_id = ?::uuid", String.class,
                a.organizationId())).isNotNull();
        assertThat(jdbc.queryForObject(
                "select stripe_customer_id from subscription where organization_id = ?::uuid", String.class,
                b.organizationId())).isNull();
        assertThat(gateway.customersCreated).hasSize(1).allMatch(s -> s.startsWith(a.organizationId()));
    }

    @Test
    void alreadySubscribedOrganizationMustUseThePortal() throws Exception {
        Account owner = accounts.signup("Paying Org");
        jdbc.update("""
                update subscription set status = 'ACTIVE', stripe_customer_id = 'cus_paying',
                stripe_subscription_id = 'sub_paying' where organization_id = ?::uuid""", owner.organizationId());
        owner.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Already subscribed"));
        owner.client().post("/api/v1/billing/portal-session", null).andExpect(status().isOk());
    }

    @Test
    void providerFailureIs502WithoutDetails() throws Exception {
        Account owner = accounts.signup("Provider Down");
        gateway.failEverything = true;
        owner.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Billing provider unavailable"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("scripted"))));
    }

    @Test
    void checkoutWorksWhileReadOnlyBecauseItIsTheWayOut() throws Exception {
        Account owner = accounts.signup("Lapsed Org");
        setStatus(owner.organizationId(), "CANCELED");
        owner.client().post("/api/v1/billing/checkout-session", null).andExpect(status().isOk());
        owner.client().post("/api/v1/billing/portal-session", null).andExpect(status().isOk());
    }
}
