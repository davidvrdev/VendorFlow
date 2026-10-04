package com.vendorflow.billing.application;

import java.time.Instant;

/**
 * The few fields of a signature-verified Stripe event that we use. Anything else in the payload is ignored on
 * purpose: state is always re-read from Stripe by subscription id, never trusted from the event body.
 */
public record WebhookEvent(String id, String type, Instant created, String customerId, String subscriptionId,
        String clientReferenceId) {
}
