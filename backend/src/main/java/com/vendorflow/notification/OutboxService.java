package com.vendorflow.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Producer side of the outbox. Joins the caller's transaction (MANDATORY), so an email exists if and only if the
 * business change that caused it committed.
 *
 * <p>Idempotency: a native {@code INSERT ... ON CONFLICT (idempotency_key) DO NOTHING}. A duplicate is a silent
 * no-op and, unlike catching a unique-violation exception, does not abort the surrounding Postgres transaction.
 */
@Service
public class OutboxService {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxService(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * @param organizationId null for account emails (verification, reset)
     * @param payload minimal template data; may hold a raw token, which the dispatcher scrubs after delivery
     * @return true if a row was created, false if the idempotency key already existed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(NotificationKind kind, UUID organizationId, String recipientEmail, String idempotencyKey,
            Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int inserted = jdbc.sql("""
                INSERT INTO notification (id, organization_id, kind, recipient_email, idempotency_key, payload,
                                          status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (:id, CAST(:org AS uuid), :kind, :recipient, :key, CAST(:payload AS jsonb),
                        'PENDING', 0, :now, :now, :now)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("org", organizationId == null ? null : organizationId.toString())
                .param("kind", kind.name())
                .param("recipient", recipientEmail)
                .param("key", idempotencyKey)
                .param("payload", jsonMapper.writeValueAsString(payload))
                .param("now", now)
                .update();
        return inserted == 1;
    }
}
