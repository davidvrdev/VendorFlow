package com.vendorflow.vendor.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** "This vendor must provide a document of this type." Ids only: no JPA relations across features. */
@Entity
@Table(name = "vendor_requirement")
public class VendorRequirement extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VendorRequirement() {
    }

    public VendorRequirement(UUID organizationId, UUID vendorId, UUID documentTypeId, Instant now) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.vendorId = vendorId;
        this.documentTypeId = documentTypeId;
        this.createdAt = now;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }
}
