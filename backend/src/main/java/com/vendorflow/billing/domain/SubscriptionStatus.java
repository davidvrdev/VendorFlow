package com.vendorflow.billing.domain;

import java.util.Locale;
import java.util.Optional;

/** Subscription state of an organization (the V8 CHECK constraint lists the same names). */
public enum SubscriptionStatus {
    TRIALING, ACTIVE, PAST_DUE, CANCELED, INCOMPLETE, UNPAID;

    /**
     * Maps a Stripe subscription status string. {@code incomplete_expired} is a dead subscription (CANCELED) and
     * {@code paused} cannot pay (UNPAID, read-only). An unknown value maps to empty: callers then leave the row alone
     * instead of guessing (fail safe against a new Stripe status).
     */
    public static Optional<SubscriptionStatus> fromStripe(String stripeStatus) {
        if (stripeStatus == null) {
            return Optional.empty();
        }
        return switch (stripeStatus.toLowerCase(Locale.ROOT)) {
            case "trialing" -> Optional.of(TRIALING);
            case "active" -> Optional.of(ACTIVE);
            case "past_due" -> Optional.of(PAST_DUE);
            case "canceled", "incomplete_expired" -> Optional.of(CANCELED);
            case "unpaid", "paused" -> Optional.of(UNPAID);
            case "incomplete" -> Optional.of(INCOMPLETE);
            default -> Optional.empty();
        };
    }

    /** True for states that end a subscription (an old subscription reaching one must not override a newer one). */
    public boolean isTerminal() {
        return this == CANCELED || this == UNPAID;
    }
}
