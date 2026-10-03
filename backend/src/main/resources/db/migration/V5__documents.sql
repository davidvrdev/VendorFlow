-- V5: documents (uploaded files + compliance metadata) and the indexes the document features need.
-- See docs/DATABASE.md § document. The file itself lives in object storage under storage_key; only metadata is here.

CREATE TABLE document (
    id                        uuid PRIMARY KEY,
    organization_id           uuid        NOT NULL REFERENCES organization (id),
    vendor_id                 uuid        NOT NULL,
    document_type_id          uuid        NOT NULL,
    state                     text        NOT NULL CHECK (state IN ('CURRENT', 'SUPERSEDED', 'ARCHIVED')),
    review_status             text        NOT NULL CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    issue_date                date,
    expiration_date           date,
    -- Server-generated (org/{orgId}/doc/{documentId}); never derived from user input.
    storage_key               text        NOT NULL CHECK (char_length(storage_key) <= 200),
    -- Sanitized, display-only.
    original_filename         text        NOT NULL CHECK (char_length(original_filename) BETWEEN 1 AND 255),
    -- Detected from the magic bytes, never the client Content-Type.
    mime_type                 text        NOT NULL CHECK (mime_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    size_bytes                bigint      NOT NULL CHECK (size_bytes > 0),
    sha256                    text        NOT NULL CHECK (char_length(sha256) = 64),
    -- SET NULL: the document outlives the user who uploaded/reviewed it (also reserved for vendor-portal uploads).
    uploaded_by_user_id       uuid REFERENCES app_user (id) ON DELETE SET NULL,
    superseded_by_document_id uuid,
    reviewed_by_user_id       uuid REFERENCES app_user (id) ON DELETE SET NULL,
    reviewed_at               timestamptz,
    review_note               text CHECK (char_length(review_note) <= 1000),
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    CONSTRAINT document_dates_ck CHECK (issue_date IS NULL OR expiration_date IS NULL OR issue_date <= expiration_date),
    CONSTRAINT document_org_id_uq UNIQUE (organization_id, id),
    -- Composite FKs (organization_id, x_id): a document can never point at a vendor/type of another organization.
    CONSTRAINT document_vendor_fk FOREIGN KEY (organization_id, vendor_id)
        REFERENCES vendor (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT document_document_type_fk FOREIGN KEY (organization_id, document_type_id)
        REFERENCES document_type (organization_id, id) ON DELETE RESTRICT,
    -- DEFERRABLE: when a new upload supersedes the old CURRENT one, the old row is updated (to free the partial unique
    -- index below) BEFORE the new row it points at is inserted; the FK is checked at commit.
    CONSTRAINT document_superseded_by_fk FOREIGN KEY (organization_id, superseded_by_document_id)
        REFERENCES document (organization_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

-- At most one CURRENT document per (vendor, type). The service serializes uploads with a vendor row lock; this index
-- is the backstop that makes a bug or a race fail loudly instead of leaving two current documents.
CREATE UNIQUE INDEX document_current_uq ON document (vendor_id, document_type_id) WHERE state = 'CURRENT';
-- Dashboard / reminder scans (Phases 4-6): "current documents expiring before X".
CREATE INDEX document_org_expiration_idx ON document (organization_id, expiration_date) WHERE state = 'CURRENT';
-- Per-vendor listings and FK checks.
CREATE INDEX document_org_vendor_idx ON document (organization_id, vendor_id);
-- FK check support for the type FK (deleting/updating a type) and "documents of this type" lookups.
CREATE INDEX document_org_type_idx ON document (organization_id, document_type_id);

-- Document-type names are unique per organization, case-insensitively (service pre-check + this index as arbiter
-- for two concurrent creates/renames). The 8 seeded defaults have distinct names, so existing rows satisfy it.
CREATE UNIQUE INDEX document_type_org_name_lower_uq ON document_type (organization_id, lower(name));

-- Vendor history = the vendor's own audit events + the events of its documents (metadata.vendorId). The query uses
-- jsonb_extract_path_text(metadata, 'vendorId') (what Hibernate's function() renders; same value as metadata->>'vendorId').
-- Partial on entity_type = 'document' so the index only holds document events; created_at DESC serves newest-first
-- paging. IMMUTABLE function, so it is allowed in an index expression.
CREATE INDEX audit_event_document_vendor_idx
    ON audit_event (organization_id, (jsonb_extract_path_text(metadata, 'vendorId')), created_at DESC)
    WHERE entity_type = 'document';
