-- V7: CSV vendor import previews (docs/DATABASE.md, docs/API.md "Phase 7 contract details").
-- A preview stores the parsed + validated rows so that commit applies exactly what the user reviewed. Nothing in
-- `vendor` changes until commit. Rows hold vendor contact data, so previews are short-lived (expires_at) and purged.

CREATE TABLE vendor_import (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organization (id),
    created_by_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    status             text        NOT NULL CHECK (status IN ('PREVIEWED', 'COMMITTED', 'EXPIRED')),
    -- Array of parsed rows (rowNumber, companyName, action, changes, errors, normalized cell values, matched vendor id).
    rows               jsonb       NOT NULL,
    -- { total, create, update, unchanged, error }
    summary            jsonb       NOT NULL,
    created_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    committed_at       timestamptz,
    CONSTRAINT vendor_import_org_id_uq UNIQUE (organization_id, id)
);

-- Cleanup scans by expiry across all organizations.
CREATE INDEX vendor_import_expires_idx ON vendor_import (expires_at);
