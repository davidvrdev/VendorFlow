package com.vendorflow.billing.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The only door to the Stripe API (ARCHITECTURE.md "ports"). The real implementation lives in infrastructure; tests
 * replace it with a fake. Implementations throw {@link BillingGatewayException} on any provider failure.
 */
public interface BillingGateway {

    /** Idempotent per organization (the implementation passes an idempotency key), returns the Stripe customer id. */
    String createCustomer(UUID organizationId, String organizationName);

    /** @return the hosted Checkout URL */
    String createCheckoutSession(String customerId, UUID organizationId, String priceId, String successUrl,
            String cancelUrl);

    /** @return the hosted Customer Portal URL */
    String createPortalSession(String customerId, String returnUrl);

    /** Current state of a subscription; empty if Stripe does not know it. */
    Optional<RemoteSubscription> retrieveSubscription(String subscriptionId);
}
