package com.vendorflow.billing.api;

import java.time.Instant;

/** Response of GET /api/v1/billing/subscription. */
public record SubscriptionView(String status, String plan, Instant trialEndsAt, Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd, boolean readOnly, boolean canManage) {
}
