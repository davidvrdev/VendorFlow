package com.vendorflow.portal.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A capability link a vendor uses to upload documents without an account (ADR-0011). Only the SHA-256 of the token
 * is stored. {@code useCount}, {@code usedBytes} and {@code lastUsedAt} are changed ONLY by the atomic claim query in the repository,
 * never by this entity (hence insertable = false: the column defaults apply on insert).
 */
@Entity
@Table(name = "vendor_upload_link")
public class VendorUploadLink extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at", insertable = false, updatable = false)
    private Instant lastUsedAt;

    @Column(name = "use_count", insertable = false, updatable = false)
    private int useCount;

    @Column(name = "max_uploads", nullable = false, updatable = false)
    private int maxUploads;

    @Column(name = "max_total_bytes", nullable = false, updatable = false)
    private long maxTotalBytes;

    @Column(name = "used_bytes", insertable = false, updatable = false)
    private long usedBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VendorUploadLink() {
    }

    public VendorUploadLink(UUID id, UUID organizationId, UUID vendorId, String tokenHash, UUID createdByUserId,
            Instant expiresAt, int maxUploads, long maxTotalBytes, Instant now) {
        super(id);
        this.organizationId = organizationId;
        this.vendorId = vendorId;
        this.tokenHash = tokenHash;
        this.createdByUserId = createdByUserId;
        this.expiresAt = expiresAt;
        this.maxUploads = maxUploads;
        this.maxTotalBytes = maxTotalBytes;
        this.createdAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Not revoked and not expired. An EXHAUSTED link is still live: the vendor may view it and gets a clear 422. */
    public boolean isLive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public LinkStatus statusAt(Instant now) {
        if (revokedAt != null) {
            return LinkStatus.REVOKED;
        }
        if (!expiresAt.isAfter(now)) {
            return LinkStatus.EXPIRED;
        }
        return useCount >= maxUploads ? LinkStatus.EXHAUSTED : LinkStatus.ACTIVE;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public int getUseCount() {
        return useCount;
    }

    public int getMaxUploads() {
        return maxUploads;
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
