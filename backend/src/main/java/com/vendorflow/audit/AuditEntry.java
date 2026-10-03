package com.vendorflow.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read model of one audit event for display: the actor is resolved to a name (null if unknown/system). */
public record AuditEntry(UUID id, String action, String actorFullName, Instant occurredAt,
        Map<String, Object> metadata) {
}
