package com.vendorflow.billing.infrastructure;

import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.billingportal.SessionCreateParams.Builder;
import com.vendorflow.billing.application.BillingGateway;
import com.vendorflow.billing.application.BillingGatewayException;
import com.vendorflow.billing.application.BillingNotConfiguredException;
import com.vendorflow.billing.application.BillingProperties;
import com.vendorflow.billing.application.RemoteSubscription;
import com.vendorflow.billing.domain.SubscriptionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stripe API adapter (stripe-java, ADR in DECISIONS.md). The client is built lazily from the configured secret key so
 * the application starts without keys (local/test); every call then fails with "Billing not configured" instead of
 * pretending to work. Stripe exception messages can include request details, so they are wrapped and never surfaced.
 */
@Component
public class StripeBillingGateway implements BillingGateway {

    private final BillingProperties properties;
    private volatile StripeClient client;

    public StripeBillingGateway(BillingProperties properties) {
        this.properties = properties;
    }

    private StripeClient client() {
        StripeClient local = client;
        if (local == null) {
            if (!properties.apiConfigured()) {
                throw new BillingNotConfiguredException();
            }
            local = StripeClient.builder().setApiKey(properties.getStripe().getSecretKey())
                    .setMaxNetworkRetries(1).setConnectTimeout(5_000).setReadTimeout(15_000).build();
            client = local;
        }
        return local;
    }

    @Override
    public String createCustomer(UUID organizationId, String organizationName) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setName(organizationName)
                    .putMetadata("organization_id", organizationId.toString())
                    .build();
            // Same key for the same organization: a retry after a timeout returns the SAME customer.
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey("vendorflow-customer-" + organizationId + "-" + paramsHash(organizationName)).build();
            return client().v1().customers().create(params, options).getId();
        } catch (StripeException e) {
            throw new BillingGatewayException("Stripe customer creation failed", e);
        }
    }

    /** The key must change with the parameters: Stripe rejects a reused key sent with different parameters. */
    private static String paramsHash(String name) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String createCheckoutSession(String customerId, UUID organizationId, String priceId, String successUrl,
            String cancelUrl) {
        try {
            com.stripe.param.checkout.SessionCreateParams params = com.stripe.param.checkout.SessionCreateParams
                    .builder()
                    .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setClientReferenceId(organizationId.toString())
                    .putMetadata("organization_id", organizationId.toString())
                    .setSubscriptionData(com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder()
                            .putMetadata("organization_id", organizationId.toString()).build())
                    .addLineItem(com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                            .setPrice(priceId).setQuantity(1L).build())
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .build();
            String url = client().v1().checkout().sessions().create(params).getUrl();
            if (url == null || url.isBlank()) {
                throw new BillingGatewayException("Stripe returned no checkout URL", null);
            }
            return url;
        } catch (StripeException e) {
            throw new BillingGatewayException("Stripe checkout session failed", e);
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        try {
            Builder params = com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(customerId).setReturnUrl(returnUrl);
            return client().v1().billingPortal().sessions().create(params.build()).getUrl();
        } catch (StripeException e) {
            throw new BillingGatewayException("Stripe portal session failed", e);
        }
    }

    @Override
    public Optional<RemoteSubscription> retrieveSubscription(String subscriptionId) {
        try {
            return Optional.of(toRemote(client().v1().subscriptions().retrieve(subscriptionId)));
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return Optional.empty();
            }
            throw new BillingGatewayException("Stripe subscription retrieval failed", e);
        } catch (StripeException e) {
            throw new BillingGatewayException("Stripe subscription retrieval failed", e);
        }
    }

    /** Package-visible for the mapper unit test. */
    static RemoteSubscription toRemote(Subscription s) {
        // With current API versions the billing period lives on the subscription items, not on the subscription.
        Instant periodEnd = null;
        if (s.getItems() != null && s.getItems().getData() != null) {
            periodEnd = s.getItems().getData().stream().map(SubscriptionItem::getCurrentPeriodEnd)
                    .filter(java.util.Objects::nonNull).min(Long::compare).map(Instant::ofEpochSecond).orElse(null);
        }
        return new RemoteSubscription(s.getId(), s.getCustomer(), SubscriptionStatus.fromStripe(s.getStatus())
                .orElse(null), periodEnd, Boolean.TRUE.equals(s.getCancelAtPeriodEnd()));
    }
}
