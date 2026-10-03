package com.vendorflow.vendor.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API shape "HistoryEvent". {@code changes} is {field: {before, after}} or null when the event carries no field diff. */
public record HistoryEvent(UUID id, String action, Actor actor, Instant occurredAt,
        Map<String, Change> changes) {

    public record Actor(String fullName) {
    }

    public record Change(Object before, Object after) {
    }
}
