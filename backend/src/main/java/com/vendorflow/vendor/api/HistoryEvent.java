package com.vendorflow.vendor.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API shape "HistoryEvent". {@code changes} is {field: {before, after}} or null when the event carries no field diff.
 * {@code detail} (additive, Phase 3) is a short human-readable subject for document events
 * ("Certificate of Insurance - coi.pdf"); null for vendor events.
 */
public record HistoryEvent(UUID id, String action, Actor actor, Instant occurredAt,
        Map<String, Change> changes, String detail) {

    public record Actor(String fullName) {
    }

    public record Change(Object before, Object after) {
    }
}
