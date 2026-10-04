package com.vendorflow.e2e;

import com.vendorflow.billing.application.BillingGateway;
import com.vendorflow.billing.application.RemoteSubscription;
import com.vendorflow.billing.domain.SubscriptionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Profile {@code e2e} only (ProfileGuard forbids e2e + prod): stands in for the Stripe API so the full-stack E2E can
 * deliver SIGNED webhook events (real verifier, fake secret from application-e2e.yml) without any Stripe account.
 * It never calls the network. The "remote" state is encoded in the subscription id the test puts in the event:
 * {@code sub_e2e_<status>_<customerId>} (the stored id is the canonical {@code sub_e2e_<customerId>}) with status active | past_due | canceled. Checkout/portal are not simulated
 * (no Stripe API key is configured in e2e, so those endpoints still answer 503 before reaching this class).
 */
@Component
@Primary
@Profile("e2e")
public class E2eBillingGateway implements BillingGateway {

    private static final String PREFIX = "sub_e2e_";

    @Override
    public String createCustomer(UUID organizationId, String organizationName) {
        return "cus_e2e_" + organizationId.toString().substring(0, 8);
    }

    @Override
    public String createCheckoutSession(String customerId, UUID organizationId, String priceId, String successUrl,
            String cancelUrl) {
        return successUrl;
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        return returnUrl;
    }

    @Override
    public Optional<RemoteSubscription> retrieveSubscription(String subscriptionId) {
        if (subscriptionId == null || !subscriptionId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = subscriptionId.substring(PREFIX.length());
        int sep = rest.indexOf('_', rest.startsWith("past_due_") ? "past_due_".length() : 0);
        if (sep < 0) {
            return Optional.empty();
        }
        String customer = rest.substring(sep + 1);
        SubscriptionStatus status = switch (rest.substring(0, sep)) {
            case "active" -> SubscriptionStatus.ACTIVE;
            case "past_due" -> SubscriptionStatus.PAST_DUE;
            case "canceled" -> SubscriptionStatus.CANCELED;
            default -> null;
        };
        if (status == null) {
            return Optional.empty();
        }
        // Canonical id without the status segment: successive events of one subscription keep the same stored id (like Stripe).
        return Optional.of(new RemoteSubscription(PREFIX + customer, customer, status,
                Instant.now().plus(30, ChronoUnit.DAYS), false));
    }
}
