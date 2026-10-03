package com.vendorflow.vendor.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "vendor")
public class Vendor extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "contact_name")
    private String contactName;

    private String email;

    private String phone;

    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VendorStatus status;

    private String notes;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Vendor() {
    }

    public Vendor(UUID organizationId, UUID createdByUserId, Instant now) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.createdByUserId = createdByUserId;
        this.status = VendorStatus.ACTIVE;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
    }

    /** Replaces every editable field (the caller has already normalized the values). Does not touch updatedAt. */
    public void setDetails(String companyName, String contactName, String email, String phone, String category,
            String notes) {
        this.companyName = companyName;
        this.contactName = contactName;
        this.email = email;
        this.phone = phone;
        this.category = category;
        this.notes = notes;
    }

    public void setStatus(VendorStatus status) {
        this.status = status;
    }

    /** Truncated to microseconds like the column, so the value in a response equals the one read back later. */
    public void touch(Instant now) {
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getContactName() {
        return contactName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getCategory() {
        return category;
    }

    public VendorStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
