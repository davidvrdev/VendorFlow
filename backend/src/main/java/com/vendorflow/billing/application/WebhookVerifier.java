package com.vendorflow.billing.application;

/** Verifies the Stripe-Signature header against the raw body and parses the event. */
public interface WebhookVerifier {

    /** @throws InvalidWebhookException bad/missing signature, stale timestamp or unparsable payload */
    WebhookEvent verify(String payload, String signatureHeader);
}
