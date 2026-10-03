package com.vendorflow.organization.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An emailed offer to join an organization. Only the SHA-256 of the token is stored. */
@Entity
@Table(name = "invitation")
public class Invitation extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "invited_by_user_id", updatable = false)
    private UUID invitedByUserId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invitation() {
    }

    public Invitation(UUID organizationId, String email, Role role, String tokenHash, UUID invitedByUserId,
            Instant expiresAt, Instant now) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedByUserId = invitedByUserId;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public UUID getInvitedByUserId() {
        return invitedByUserId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Usable for accepting: neither accepted nor revoked, and not past its expiry. */
    public boolean isPending(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    /** Neither accepted nor revoked (it may be expired); such a row still occupies the unique "pending" slot. */
    public boolean isOpen() {
        return acceptedAt == null && revokedAt == null;
    }

    public void markAccepted(Instant now) {
        this.acceptedAt = now;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
