package com.vendorflow.notification;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the transactional email outbox (ADR-0006). Rows are INSERTED by OutboxService (native, idempotent);
 * this entity is what the dispatcher loads, locks and updates.
 */
@Entity
@Table(name = "notification")
public class Notification extends UuidEntity {

    /** Delay before retry N (index = attempts already made - 1). A 6th failed attempt is terminal (DEAD). */
    static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6)};
    static final int MAX_ATTEMPTS = 6;
    /** Payload keys holding secrets; removed once the notification reaches a terminal state. */
    private static final Set<String> SECRET_KEYS = Set.of("token");

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationKind kind;

    @Column(name = "recipient_email", nullable = false, updatable = false)
    private String recipientEmail;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Notification() {
    }

    public NotificationKind getKind() {
        return kind;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void markSent(String providerMessageId, Instant now) {
        this.attempts++;
        this.status = NotificationStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = now;
        this.lastError = null;
        this.updatedAt = now;
        scrubSecrets();
    }

    /** Records a failed attempt: FAILED with backoff, or DEAD after the last allowed attempt. */
    void markFailed(String sanitizedError, Instant now) {
        this.attempts++;
        this.lastError = sanitizedError;
        this.updatedAt = now;
        if (attempts >= MAX_ATTEMPTS) {
            this.status = NotificationStatus.DEAD;
            scrubSecrets();
        } else {
            this.status = NotificationStatus.FAILED;
            this.nextAttemptAt = now.plus(BACKOFF[attempts - 1]);
        }
    }

    /** Permanent failure (provider rejected the request): terminal at once, no retries. */
    void markDead(String sanitizedError, Instant now) {
        this.attempts++;
        this.lastError = sanitizedError;
        this.updatedAt = now;
        this.status = NotificationStatus.DEAD;
        scrubSecrets();
    }

    private void scrubSecrets() {
        Map<String, Object> scrubbed = new LinkedHashMap<>(payload);
        scrubbed.keySet().removeAll(SECRET_KEYS);
        this.payload = scrubbed;
    }
}
