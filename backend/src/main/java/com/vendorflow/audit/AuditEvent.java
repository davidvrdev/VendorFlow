package com.vendorflow.audit;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit row. No setters: once built it is only ever inserted. */
@Entity
@Table(name = "audit_event")
public class AuditEvent extends UuidEntity {

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> metadata;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @Column(name = "ip_hash", updatable = false)
    private String ipHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    AuditEvent(UUID organizationId, UUID actorUserId, String action, String entityType, UUID entityId,
            Map<String, Object> metadata, String requestId, String ipHash, Instant createdAt) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.metadata = metadata;
        this.requestId = requestId;
        this.ipHash = ipHash;
        this.createdAt = createdAt;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIpHash() {
        return ipHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
