package com.vendorflow.document.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A kind of document an organization asks vendors for (COI, W-9...). Seeded per organization, see DocumentTypeService. */
@Entity
@Table(name = "document_type")
public class DocumentType extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "has_expiration", nullable = false)
    private boolean hasExpiration;

    @Column(name = "required_by_default", nullable = false)
    private boolean requiredByDefault;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentType() {
    }

    public DocumentType(UUID organizationId, String code, String name, boolean hasExpiration,
            boolean requiredByDefault, int sortOrder, Instant now) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.hasExpiration = hasExpiration;
        this.requiredByDefault = requiredByDefault;
        this.active = true;
        this.sortOrder = sortOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isHasExpiration() {
        return hasExpiration;
    }

    public boolean isRequiredByDefault() {
        return requiredByDefault;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
