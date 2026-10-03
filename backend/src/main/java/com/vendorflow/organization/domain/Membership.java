package com.vendorflow.organization.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A user's role in an organization. Ids only (no associations): keeps queries explicit and tenant-scoped. */
@Entity
@Table(name = "membership")
public class Membership extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Membership() {
    }

    public Membership(UUID organizationId, UUID userId, Role role, Instant now) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void changeRole(Role role, Instant now) {
        this.role = role;
        this.updatedAt = now;
    }
}
