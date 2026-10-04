package com.vendorflow.chasing.api;

import java.time.Instant;

/** API shape "VendorChasingState" (docs/API.md Phase 15). */
public record VendorChasingStateView(boolean paused, String pausedReason, Instant lastChasedAt, int attempts,
        int maxAttempts, Instant nextChaseAt, Status status) {

    public enum Status {
        IDLE, ACTIVE, EXHAUSTED, PAUSED, NO_EMAIL
    }
}
