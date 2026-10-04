package com.vendorflow.document.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One uploaded file plus its compliance metadata. The file bytes live in object storage under {@code storageKey}.
 * Ids only (no JPA relations across features); mutators are the only legal state transitions.
 */
@Entity
@Table(name = "document")
public class Document extends UuidEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, updatable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(name = "uploaded_by_user_id", updatable = false)
    private UUID uploadedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DocumentSource source;

    /** The portal link a PORTAL upload came through; null for STAFF uploads. */
    @Column(name = "upload_link_id", updatable = false)
    private UUID uploadLinkId;

    @Column(name = "superseded_by_document_id")
    private UUID supersededByDocumentId;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
    }

    /** A freshly uploaded document: CURRENT and PENDING review. {@code uploadLinkId != null} marks a PORTAL upload. */
    public Document(UUID id, UUID organizationId, UUID vendorId, UUID documentTypeId, LocalDate issueDate,
            LocalDate expirationDate, String storageKey, String originalFilename, String mimeType, long sizeBytes,
            String sha256, UUID uploadedByUserId, UUID uploadLinkId, boolean candidate, Instant now) {
        super(id);
        this.source = uploadLinkId == null ? DocumentSource.STAFF : DocumentSource.PORTAL;
        this.uploadLinkId = uploadLinkId;
        this.organizationId = organizationId;
        this.vendorId = vendorId;
        this.documentTypeId = documentTypeId;
        this.state = candidate ? DocumentState.CANDIDATE : DocumentState.CURRENT;
        this.reviewStatus = ReviewStatus.PENDING;
        this.issueDate = issueDate;
        this.expirationDate = expirationDate;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedByUserId = uploadedByUserId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void supersede(UUID newerDocumentId, Instant now) {
        this.state = DocumentState.SUPERSEDED;
        this.supersededByDocumentId = newerDocumentId;
        this.updatedAt = now;
    }

    /** A reviewed-and-approved CANDIDATE becomes the CURRENT document (the caller has already superseded the old one). */
    public void promote(Instant now) {
        this.state = DocumentState.CURRENT;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        this.state = DocumentState.ARCHIVED;
        this.updatedAt = now;
    }

    public void review(ReviewStatus decision, String note, UUID reviewerUserId, Instant now) {
        this.reviewStatus = decision;
        this.reviewNote = note;
        this.reviewedByUserId = reviewerUserId;
        this.reviewedAt = now;
        this.updatedAt = now;
    }

    public void changeDates(LocalDate issueDate, LocalDate expirationDate, Instant now) {
        this.issueDate = issueDate;
        this.expirationDate = expirationDate;
        this.updatedAt = now;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public DocumentState getState() {
        return state;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public DocumentSource getSource() {
        return source;
    }

    public UUID getUploadLinkId() {
        return uploadLinkId;
    }

    public UUID getUploadedByUserId() {
        return uploadedByUserId;
    }

    public UUID getSupersededByDocumentId() {
        return supersededByDocumentId;
    }

    public UUID getReviewedByUserId() {
        return reviewedByUserId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
