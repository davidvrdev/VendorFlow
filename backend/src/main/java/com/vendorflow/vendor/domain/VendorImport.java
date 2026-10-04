package com.vendorflow.vendor.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A stored CSV import preview (table vendor_import). The rows are kept as plain JSON maps: the application layer
 * owns their shape (VendorImportService), the entity only carries them.
 */
@Entity
@Table(name = "vendor_import")
public class VendorImport extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VendorImportStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> rows;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    protected VendorImport() {
    }

    public VendorImport(UUID organizationId, UUID createdByUserId, List<Map<String, Object>> rows,
            Map<String, Object> summary, Instant now, Instant expiresAt) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.createdByUserId = createdByUserId;
        this.status = VendorImportStatus.PREVIEWED;
        this.rows = rows;
        this.summary = summary;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Marks the import applied and drops the stored cell values (vendor contact data) that are no longer needed. */
    public void markCommitted(Instant now) {
        this.status = VendorImportStatus.COMMITTED;
        this.committedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.rows = List.of();
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public VendorImportStatus getStatus() {
        return status;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
