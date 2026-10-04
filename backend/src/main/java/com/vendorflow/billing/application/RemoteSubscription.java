package com.vendorflow.billing.application;

import com.vendorflow.billing.domain.SubscriptionStatus;
import java.time.Instant;

/** Provider-neutral snapshot of a Stripe subscription. {@code status} is null for a status we do not know. */
public record RemoteSubscription(String id, String customerId, SubscriptionStatus status, Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd) {
}
