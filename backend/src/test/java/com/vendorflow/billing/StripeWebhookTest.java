package com.vendorflow.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stripe.Stripe;
import com.vendorflow.billing.application.RemoteSubscription;
import com.vendorflow.billing.domain.SubscriptionStatus;
import com.vendorflow.support.TestAccounts.Account;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Real signature computation with the test secret against the real verifier; only the Stripe API is faked. */
class StripeWebhookTest extends BillingTestBase {

    Account owner;
    String org;
    String customer;

    @BeforeEach
    void setUp() throws Exception {
        // Stripe ids are unique in the table and these tests use fixed ones: release them from earlier tests.
        jdbc.update("update subscription set stripe_subscription_id = null where stripe_subscription_id in "
                + "('sub_1', 'sub_x', 'sub_new', 'sub_old', 'sub_paid', 'sub_ref', 'sub_evil')");
        owner = accounts.signup("Webhook Org " + UUID.randomUUID());
        org = owner.organizationId();
        customer = "cus_" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("update subscription set stripe_customer_id = ? where organization_id = ?::uuid", customer, org);
    }

    // ---- helpers ----

    static String sign(String payload, String secret, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + hex;
    }

    static String event(String id, String type, long created, String objectJson) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION
                + "\",\"created\":" + created + ",\"livemode\":false,\"pending_webhooks\":1,\"type\":\"" + type
                + "\",\"data\":{\"object\":" + objectJson + "}}";
    }

    static String subscriptionObject(String subId, String customerId, String status) {
        return "{\"id\":\"" + subId + "\",\"object\":\"subscription\",\"customer\":\"" + customerId
                + "\",\"status\":\"" + status + "\"}";
    }

    static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    ResultActions deliver(String payload, String signatureHeader) throws Exception {
        var request = post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signatureHeader != null) {
            request.header("Stripe-Signature", signatureHeader);
        }
        return mvc.perform(request);
    }

    ResultActions deliverSigned(String payload) throws Exception {
        return deliver(payload, sign(payload, WEBHOOK_SECRET, nowSeconds()));
    }

    void stripeSays(String subId, SubscriptionStatus status, boolean cancelAtPeriodEnd) {
        gateway.subscriptions.put(subId, new RemoteSubscription(subId, customer, status,
                Instant.parse("2026-11-01T00:00:00Z"), cancelAtPeriodEnd));
    }

    int eventRows(String eventId, String status) {
        return jdbc.queryForObject("select count(*) from stripe_event where id = ? and status = ?", Integer.class,
                eventId, status);
    }

    int audits(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where organization_id = ?::uuid and action = ?",
                Integer.class, org, action);
    }

    // ---- signature ----

    @Test
    void validSignatureNeedsNoSessionNorCsrfAndUpdatesTheSubscription() throws Exception {
        stripeSays("sub_1", SubscriptionStatus.ACTIVE, true);
        String id = newEventId();
        deliverSigned(event(id, "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_1", customer, "active")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));

        owner.client().get("/api/v1/billing/subscription")
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.currentPeriodEnd").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.readOnly").value(false));
        assertThat(jdbc.queryForObject("select stripe_subscription_id from subscription where organization_id = ?::uuid",
                String.class, org)).isEqualTo("sub_1");
        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
        assertThat(audits("billing.subscription.updated")).isEqualTo(1);
    }

    @Test
    void badSignatureIs400AndChangesNothing() throws Exception {
        stripeSays("sub_1", SubscriptionStatus.CANCELED, false);
        String id = newEventId();
        String payload = event(id, "customer.subscription.deleted", nowSeconds(),
                subscriptionObject("sub_1", customer, "canceled"));
        // signed with the wrong secret
        deliver(payload, sign(payload, "whsec_someone_else", nowSeconds())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid webhook")).andExpect(jsonPath("$.requestId").exists());
        // valid signature of a different body (tampered payload)
        deliver(payload, sign(payload + " ", WEBHOOK_SECRET, nowSeconds())).andExpect(status().isBadRequest());
        // garbage / missing header
        deliver(payload, "t=1,v1=00").andExpect(status().isBadRequest());
        deliver(payload, "nonsense").andExpect(status().isBadRequest());
        deliver(payload, null).andExpect(status().isBadRequest());

        assertThat(statusOf(org)).isEqualTo("TRIALING");
        assertThat(jdbc.queryForObject("select count(*) from stripe_event where id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void staleTimestampIs400() throws Exception {
        stripeSays("sub_1", SubscriptionStatus.ACTIVE, false);
        String payload = event(newEventId(), "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_1", customer, "active"));
        deliver(payload, sign(payload, WEBHOOK_SECRET, nowSeconds() - 3600)).andExpect(status().isBadRequest());
        assertThat(statusOf(org)).isEqualTo("TRIALING");
    }

    @Test
    void oversizedBodyIs413BeforeAnyVerification() throws Exception {
        String big = "x".repeat(300_000);
        deliver(big, sign(big, WEBHOOK_SECRET, nowSeconds())).andExpect(status().isContentTooLarge());
    }

    // ---- idempotency ----

    @Test
    void duplicateDeliveryIsProcessedOnce() throws Exception {
        stripeSays("sub_1", SubscriptionStatus.ACTIVE, false);
        String id = newEventId();
        String payload = event(id, "customer.subscription.created", nowSeconds(),
                subscriptionObject("sub_1", customer, "active"));
        deliverSigned(payload).andExpect(status().isOk());
        deliverSigned(payload).andExpect(status().isOk());
        deliverSigned(payload).andExpect(status().isOk());

        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
        assertThat(audits("billing.subscription.created")).isEqualTo(1);
        assertThat(gateway.retrievals.get()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveriesProcessOnce() throws Exception {
        stripeSays("sub_1", SubscriptionStatus.ACTIVE, false);
        String id = newEventId();
        String payload = event(id, "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_1", customer, "active"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> deliverSigned(payload).andReturn().getResponse().getStatus()));
            }
            for (var f : futures) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
        assertThat(audits("billing.subscription.updated")).isEqualTo(1);
    }

    @Test
    void failedProcessingIs500ThenTheRetryIsProcessed() throws Exception {
        stripeSays("sub_1", SubscriptionStatus.ACTIVE, false);
        gateway.failRetrievals = true;
        String id = newEventId();
        String payload = event(id, "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_1", customer, "active"));
        deliverSigned(payload).andExpect(status().isInternalServerError());
        assertThat(eventRows(id, "FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select error from stripe_event where id = ?", String.class, id))
                .isEqualTo("BillingGatewayException");
        assertThat(statusOf(org)).isEqualTo("TRIALING");

        gateway.failRetrievals = false;
        deliverSigned(payload).andExpect(status().isOk());
        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select error from stripe_event where id = ?", String.class, id)).isNull();
        assertThat(statusOf(org)).isEqualTo("ACTIVE");
    }

    // ---- tenant resolution ----

    @Test
    void unknownCustomerIsAcknowledgedAndIgnored() throws Exception {
        gateway.subscriptions.put("sub_x", new RemoteSubscription("sub_x", "cus_nobody", SubscriptionStatus.ACTIVE,
                null, false));
        String id = newEventId();
        deliverSigned(event(id, "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_x", "cus_nobody", "active"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("TRIALING");
        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from subscription where status = 'ACTIVE' "
                + "and stripe_subscription_id = 'sub_x'", Integer.class)).isZero();
    }

    @Test
    void onlyTheOrganizationOwningTheCustomerChanges() throws Exception {
        Account other = accounts.signup("Bystander");
        stripeSays("sub_1", SubscriptionStatus.ACTIVE, false);
        deliverSigned(event(newEventId(), "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_1", customer, "active"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("ACTIVE");
        assertThat(statusOf(other.organizationId())).isEqualTo("TRIALING");
    }

    @Test
    void subscriptionOfAnotherCustomerIsNotApplied() throws Exception {
        // the event names our customer but Stripe says the subscription belongs to someone else
        gateway.subscriptions.put("sub_evil", new RemoteSubscription("sub_evil", "cus_other",
                SubscriptionStatus.ACTIVE, null, false));
        deliverSigned(event(newEventId(), "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_evil", customer, "active"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("TRIALING");
    }

    @Test
    void clientReferenceIdCannotRepointAnOrganizationThatAlreadyHasACustomer() throws Exception {
        gateway.subscriptions.put("sub_new", new RemoteSubscription("sub_new", "cus_attacker",
                SubscriptionStatus.ACTIVE, null, false));
        String session = "{\"id\":\"cs_1\",\"object\":\"checkout.session\",\"customer\":\"cus_attacker\","
                + "\"subscription\":\"sub_new\",\"client_reference_id\":\"" + org + "\"}";
        deliverSigned(event(newEventId(), "checkout.session.completed", nowSeconds(), session))
                .andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("TRIALING");
        assertThat(jdbc.queryForObject("select stripe_customer_id from subscription where organization_id = ?::uuid",
                String.class, org)).isEqualTo(customer);
    }

    // ---- event types ----

    @Test
    void checkoutCompletedActivatesAndStoresTheSubscriptionId() throws Exception {
        stripeSays("sub_paid", SubscriptionStatus.ACTIVE, false);
        String session = "{\"id\":\"cs_1\",\"object\":\"checkout.session\",\"customer\":\"" + customer
                + "\",\"subscription\":\"sub_paid\",\"client_reference_id\":\"" + org + "\"}";
        deliverSigned(event(newEventId(), "checkout.session.completed", nowSeconds(), session))
                .andExpect(status().isOk());
        owner.client().get("/api/v1/billing/subscription").andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("select stripe_subscription_id from subscription where organization_id = ?::uuid",
                String.class, org)).isEqualTo("sub_paid");
        assertThat(audits("billing.checkout.completed")).isEqualTo(1);
    }

    @Test
    void checkoutCompletedForACustomerNotStoredYetUsesTheClientReference() throws Exception {
        Account fresh = accounts.signup("Reference Org");
        stripeSays("sub_ref", SubscriptionStatus.ACTIVE, false);
        gateway.subscriptions.put("sub_ref", new RemoteSubscription("sub_ref", "cus_ref", SubscriptionStatus.ACTIVE,
                null, false));
        String session = "{\"id\":\"cs_2\",\"object\":\"checkout.session\",\"customer\":\"cus_ref\","
                + "\"subscription\":\"sub_ref\",\"client_reference_id\":\"" + fresh.organizationId() + "\"}";
        deliverSigned(event(newEventId(), "checkout.session.completed", nowSeconds(), session))
                .andExpect(status().isOk());
        assertThat(statusOf(fresh.organizationId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select stripe_customer_id from subscription where organization_id = ?::uuid",
                String.class, fresh.organizationId())).isEqualTo("cus_ref");
    }

    @Test
    void paymentFailedMovesToPastDueWhichStaysWritable() throws Exception {
        setStatus(org, "ACTIVE");
        jdbc.update("update subscription set stripe_subscription_id = 'sub_1' where organization_id = ?::uuid", org);
        stripeSays("sub_1", SubscriptionStatus.PAST_DUE, false);
        String invoice = "{\"id\":\"in_1\",\"object\":\"invoice\",\"customer\":\"" + customer
                + "\",\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{\"subscription\":\"sub_1\"}}}";
        deliverSigned(event(newEventId(), "invoice.payment_failed", nowSeconds(), invoice))
                .andExpect(status().isOk());
        owner.client().get("/api/v1/billing/subscription")
                .andExpect(jsonPath("$.status").value("PAST_DUE")).andExpect(jsonPath("$.readOnly").value(false));
        owner.client().patch("/api/v1/organization", java.util.Map.of("name", "Still Writable"))
                .andExpect(status().isOk());
        assertThat(audits("billing.payment.failed")).isEqualTo(1);
    }

    @Test
    void subscriptionDeletedMakesTheOrganizationReadOnly() throws Exception {
        setStatus(org, "ACTIVE");
        stripeSays("sub_1", SubscriptionStatus.CANCELED, false);
        deliverSigned(event(newEventId(), "customer.subscription.deleted", nowSeconds(),
                subscriptionObject("sub_1", customer, "canceled"))).andExpect(status().isOk());
        owner.client().get("/api/v1/billing/subscription")
                .andExpect(jsonPath("$.status").value("CANCELED")).andExpect(jsonPath("$.readOnly").value(true));
        owner.client().patch("/api/v1/organization", java.util.Map.of("name", "Nope"))
                .andExpect(status().isPaymentRequired());
        assertThat(audits("billing.subscription.deleted")).isEqualTo(1);
    }

    @Test
    void unhandledEventTypesAreAcknowledgedAndRecorded() throws Exception {
        String id = newEventId();
        deliverSigned(event(id, "customer.created", nowSeconds(), "{\"id\":\"" + customer
                + "\",\"object\":\"customer\"}")).andExpect(status().isOk());
        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
        assertThat(statusOf(org)).isEqualTo("TRIALING");
    }

    // ---- ordering ----

    @Test
    void outOfOrderDeliveryConvergesOnStripesCurrentState() throws Exception {
        // Stripe's truth is now CANCELED. The "deleted" event arrives first, then a stale "created" and "updated"
        // (older ids, older timestamps). Re-reading the subscription means the stale ones cannot revive it.
        stripeSays("sub_1", SubscriptionStatus.CANCELED, false);
        long now = nowSeconds();
        deliverSigned(event(newEventId(), "customer.subscription.deleted", now,
                subscriptionObject("sub_1", customer, "canceled"))).andExpect(status().isOk());
        deliverSigned(event(newEventId(), "customer.subscription.updated", now - 120,
                subscriptionObject("sub_1", customer, "active"))).andExpect(status().isOk());
        deliverSigned(event(newEventId(), "customer.subscription.created", now - 300,
                subscriptionObject("sub_1", customer, "incomplete"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("CANCELED");
    }

    @Test
    void lateEventOfAnOldEndedSubscriptionDoesNotUndoANewerOne() throws Exception {
        jdbc.update("update subscription set status = 'ACTIVE', stripe_subscription_id = 'sub_new' "
                + "where organization_id = ?::uuid", org);
        stripeSays("sub_old", SubscriptionStatus.CANCELED, false);
        deliverSigned(event(newEventId(), "customer.subscription.deleted", nowSeconds(),
                subscriptionObject("sub_old", customer, "canceled"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select stripe_subscription_id from subscription where organization_id = ?::uuid",
                String.class, org)).isEqualTo("sub_new");
    }

    @Test
    void aNewActiveSubscriptionReplacesAnEndedOne() throws Exception {
        jdbc.update("update subscription set status = 'CANCELED', stripe_subscription_id = 'sub_old' "
                + "where organization_id = ?::uuid", org);
        stripeSays("sub_new", SubscriptionStatus.ACTIVE, false);
        deliverSigned(event(newEventId(), "customer.subscription.created", nowSeconds(),
                subscriptionObject("sub_new", customer, "active"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select stripe_subscription_id from subscription where organization_id = ?::uuid",
                String.class, org)).isEqualTo("sub_new");
    }

    @Test
    void unknownStripeStatusLeavesTheRowAlone() throws Exception {
        gateway.subscriptions.put("sub_1", new RemoteSubscription("sub_1", customer, null, null, false));
        deliverSigned(event(newEventId(), "customer.subscription.updated", nowSeconds(),
                subscriptionObject("sub_1", customer, "brand_new_status"))).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("TRIALING");
    }

    @Test
    void checkoutForACustomerBoundToAnotherOrganizationIsAcknowledgedAndIgnored() throws Exception {
        Account other = accounts.signup("Claims Another Customer");
        stripeSays("sub_ref", SubscriptionStatus.ACTIVE, false);
        String session = "{\"id\":\"cs_9\",\"object\":\"checkout.session\",\"customer\":\"" + customer
                + "\",\"subscription\":\"sub_ref\",\"client_reference_id\":\"" + other.organizationId() + "\"}";
        String id = newEventId();
        deliverSigned(event(id, "checkout.session.completed", nowSeconds(), session)).andExpect(status().isOk());
        assertThat(statusOf(org)).isEqualTo("TRIALING");
        assertThat(statusOf(other.organizationId())).isEqualTo("TRIALING");
        assertThat(eventRows(id, "PROCESSED")).isEqualTo(1);
    }
}
