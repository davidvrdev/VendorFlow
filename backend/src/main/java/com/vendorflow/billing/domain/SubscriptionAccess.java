package com.vendorflow.billing.domain;

import java.time.Instant;

/**
 * THE rule for "may this organization write?" (Phase 8 contract). Pure: no clock, no database, so it is unit-tested
 * exhaustively and every caller (read-only guard, GET /billing/subscription, scheduled jobs) gets the same answer.
 *
 * <ul>
 *   <li>ACTIVE: writable.</li>
 *   <li>PAST_DUE: writable (grace period while Stripe retries the payment).</li>
 *   <li>TRIALING: writable only while {@code trialEndsAt} is in the future; a missing date fails closed.</li>
 *   <li>CANCELED, UNPAID, INCOMPLETE: read-only.</li>
 * </ul>
 */
public final class SubscriptionAccess {

    private SubscriptionAccess() {
    }

    public static boolean isReadOnly(SubscriptionStatus status, Instant trialEndsAt, Instant now) {
        return switch (status) {
            case ACTIVE, PAST_DUE -> false;
            case TRIALING -> trialEndsAt == null || !trialEndsAt.isAfter(now);
            case CANCELED, UNPAID, INCOMPLETE -> true;
        };
    }
}
