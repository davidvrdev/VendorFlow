package com.vendorflow.portal.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One document type a link may upload (child table so the composite tenant FKs hold). */
@Entity
@Table(name = "vendor_upload_link_type")
public class VendorUploadLinkType extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "link_id", nullable = false, updatable = false)
    private UUID linkId;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    protected VendorUploadLinkType() {
    }

    public VendorUploadLinkType(UUID organizationId, UUID linkId, UUID documentTypeId) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.linkId = linkId;
        this.documentTypeId = documentTypeId;
    }

    public UUID getLinkId() {
        return linkId;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }
}
