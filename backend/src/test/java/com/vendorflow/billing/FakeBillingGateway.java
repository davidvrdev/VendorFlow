package com.vendorflow.billing;

import com.vendorflow.billing.application.BillingGateway;
import com.vendorflow.billing.application.BillingGatewayException;
import com.vendorflow.billing.application.RemoteSubscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Scripted stand-in for the Stripe API: records calls and serves the subscription state a test sets up. */
public class FakeBillingGateway implements BillingGateway {

    public final List<String> customersCreated = new ArrayList<>();
    public final List<String> checkoutCustomers = new ArrayList<>();
    public final List<String> checkoutUrlsRequested = new ArrayList<>();
    public final List<String> portalReturnUrls = new ArrayList<>();
    public final AtomicInteger retrievals = new AtomicInteger();
    public final Map<String, RemoteSubscription> subscriptions = new ConcurrentHashMap<>();
    public volatile boolean failEverything;
    public volatile boolean failRetrievals;

    public void reset() {
        customersCreated.clear();
        checkoutCustomers.clear();
        checkoutUrlsRequested.clear();
        portalReturnUrls.clear();
        retrievals.set(0);
        subscriptions.clear();
        failEverything = false;
        failRetrievals = false;
    }

    @Override
    public String createCustomer(UUID organizationId, String organizationName) {
        failIfScripted();
        String id = "cus_fake_" + UUID.randomUUID().toString().substring(0, 8);
        customersCreated.add(organizationId + "=" + id);
        return id;
    }

    @Override
    public String createCheckoutSession(String customerId, UUID organizationId, String priceId, String successUrl,
            String cancelUrl) {
        failIfScripted();
        checkoutCustomers.add(customerId);
        checkoutUrlsRequested.add(successUrl + "|" + cancelUrl + "|" + priceId);
        return "https://checkout.stripe.test/c/" + customerId;
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        failIfScripted();
        portalReturnUrls.add(returnUrl);
        return "https://portal.stripe.test/p/" + customerId;
    }

    @Override
    public Optional<RemoteSubscription> retrieveSubscription(String subscriptionId) {
        retrievals.incrementAndGet();
        if (failEverything || failRetrievals) {
            throw new BillingGatewayException("scripted failure", null);
        }
        return Optional.ofNullable(subscriptions.get(subscriptionId));
    }

    private void failIfScripted() {
        if (failEverything) {
            throw new BillingGatewayException("scripted failure", null);
        }
    }
}
