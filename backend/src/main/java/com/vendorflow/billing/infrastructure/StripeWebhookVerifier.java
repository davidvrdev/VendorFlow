package com.vendorflow.billing.infrastructure;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.vendorflow.billing.application.BillingProperties;
import com.vendorflow.billing.application.InvalidWebhookException;
import com.vendorflow.billing.application.WebhookEvent;
import com.vendorflow.billing.application.WebhookVerifier;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Signature verification with the SDK's {@code Webhook.constructEvent} (HMAC-SHA256 over "timestamp.payload",
 * constant-time compare, timestamp tolerance). Then reduces the event to {@link WebhookEvent}; the payload is not
 * kept or logged anywhere.
 */
@Component
public class StripeWebhookVerifier implements WebhookVerifier {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StripeWebhookVerifier.class);

    private final BillingProperties properties;

    public StripeWebhookVerifier(BillingProperties properties) {
        this.properties = properties;
    }

    @Override
    public WebhookEvent verify(String payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Stripe webhook rejected: reason=malformed");
            throw new InvalidWebhookException();
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, properties.getStripe().getWebhookSecret(),
                    properties.getWebhookToleranceSeconds());
        } catch (Exception e) {
            // Same answer for the caller in every case; the log gets the reason CLASS only (never body/header/secret).
            log.warn("Stripe webhook rejected: reason={}", reason(e));
            throw new InvalidWebhookException();
        }
        if (event.getId() == null || event.getType() == null || event.getCreated() == null) {
            log.warn("Stripe webhook rejected: reason=malformed");
            throw new InvalidWebhookException();
        }
        return reduce(event);
    }

    private static String reason(Exception e) {
        if (e instanceof com.stripe.exception.SignatureVerificationException) {
            String m = e.getMessage();
            return m != null && m.toLowerCase(java.util.Locale.ROOT).contains("tolerance") ? "stale-timestamp"
                    : "signature-mismatch";
        }
        return "malformed";
    }

    private static WebhookEvent reduce(Event event) {
        StripeObject object = dataObject(event);
        String customer = null;
        String subscription = null;
        String clientReference = null;
        if (object instanceof Subscription s) {
            customer = s.getCustomer();
            subscription = s.getId();
        } else if (object instanceof Session s) {
            customer = s.getCustomer();
            subscription = s.getSubscription();
            clientReference = s.getClientReferenceId();
        } else if (object instanceof Invoice i) {
            customer = i.getCustomer();
            if (i.getParent() != null && i.getParent().getSubscriptionDetails() != null) {
                subscription = i.getParent().getSubscriptionDetails().getSubscription();
            }
        }
        return new WebhookEvent(event.getId(), event.getType(), Instant.ofEpochSecond(event.getCreated()), customer,
                subscription, clientReference);
    }

    private static StripeObject dataObject(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        // getObject() is empty when the event's API version differs from the SDK's; the fields we read exist in both.
        return deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (Exception e) {
                return null;
            }
        });
    }
}
