-- V11: vendor portal (Phase 14, ADR-0011). Tokenized upload links; the raw token is never stored, only its SHA-256.
-- See docs/DATABASE.md "Phase 14 migration".

CREATE TABLE vendor_upload_link (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organization (id),
    vendor_id          uuid        NOT NULL,
    -- Hex SHA-256 of the 256-bit random token. UNIQUE = the lookup index of the public portal.
    token_hash         text        NOT NULL CHECK (char_length(token_hash) = 64),
    created_by_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    expires_at         timestamptz NOT NULL,
    revoked_at         timestamptz,
    last_used_at       timestamptz,
    -- Successful uploads so far; claimed atomically in the upload transaction (see docs/DATABASE.md).
    use_count          int         NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    max_uploads        int         NOT NULL DEFAULT 20 CHECK (max_uploads BETWEEN 1 AND 50),
    -- Byte budget of the link (default 100 MB, max 500 MB) and the bytes consumed so far; both are claimed together
    -- with use_count in one atomic UPDATE, so concurrent uploads cannot overshoot either budget.
    max_total_bytes    bigint      NOT NULL DEFAULT 104857600 CHECK (max_total_bytes BETWEEN 1048576 AND 524288000),
    used_bytes         bigint      NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    created_at         timestamptz NOT NULL,
    CONSTRAINT vendor_upload_link_budget_ck CHECK (used_bytes <= max_total_bytes),
    CONSTRAINT vendor_upload_link_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT vendor_upload_link_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT vendor_upload_link_org_id_uq UNIQUE (organization_id, id),
    CONSTRAINT vendor_upload_link_vendor_fk FOREIGN KEY (organization_id, vendor_id)
        REFERENCES vendor (organization_id, id) ON DELETE RESTRICT
);
CREATE INDEX vendor_upload_link_vendor_idx ON vendor_upload_link (organization_id, vendor_id, created_at DESC);

-- The document types a link may upload (a child table instead of uuid[] so the tenant FK rule holds).
CREATE TABLE vendor_upload_link_type (
    id               uuid PRIMARY KEY,
    organization_id  uuid NOT NULL,
    link_id          uuid NOT NULL,
    document_type_id uuid NOT NULL,
    CONSTRAINT vendor_upload_link_type_uq UNIQUE (link_id, document_type_id),
    CONSTRAINT vendor_upload_link_type_link_fk FOREIGN KEY (organization_id, link_id)
        REFERENCES vendor_upload_link (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT vendor_upload_link_type_document_type_fk FOREIGN KEY (organization_id, document_type_id)
        REFERENCES document_type (organization_id, id) ON DELETE RESTRICT
);
CREATE INDEX vendor_upload_link_type_org_type_idx ON vendor_upload_link_type (organization_id, document_type_id);

-- Where a document came from. uploaded_by_user_id stays NULL for portal uploads.
-- A portal upload for a requirement whose CURRENT document is APPROVED (and not expired) is stored as a CANDIDATE: it
-- does not count for compliance (every compliance query reads state = 'CURRENT' only) until staff approve it, which
-- then supersedes the old document atomically. At most one candidate per (vendor, type): a newer one supersedes it.
ALTER TABLE document DROP CONSTRAINT document_state_check;
ALTER TABLE document ADD CONSTRAINT document_state_check
    CHECK (state IN ('CURRENT', 'CANDIDATE', 'SUPERSEDED', 'ARCHIVED'));
CREATE UNIQUE INDEX document_candidate_uq ON document (vendor_id, document_type_id) WHERE state = 'CANDIDATE';

ALTER TABLE document ADD COLUMN source text NOT NULL DEFAULT 'STAFF' CHECK (source IN ('STAFF', 'PORTAL'));
ALTER TABLE document ADD COLUMN upload_link_id uuid;
ALTER TABLE document ADD CONSTRAINT document_upload_link_fk FOREIGN KEY (organization_id, upload_link_id)
    REFERENCES vendor_upload_link (organization_id, id) ON DELETE RESTRICT;
ALTER TABLE document ADD CONSTRAINT document_source_link_ck CHECK ((source = 'PORTAL') = (upload_link_id IS NOT NULL));
CREATE INDEX document_org_upload_link_idx ON document (organization_id, upload_link_id) WHERE upload_link_id IS NOT NULL;

ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check CHECK (kind IN
    ('INVITATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'COMPLIANCE_DIGEST', 'DOCUMENT_REQUEST', 'PASSWORD_CHANGED',
     'PORTAL_UPLOAD'));
