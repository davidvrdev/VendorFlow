-- V4: document types (per organization), vendors, vendor requirements. See docs/DATABASE.md.
-- Every tenant table has UNIQUE (organization_id, id) so children can use composite foreign keys: a requirement can
-- never point at a vendor or document type of another organization, even through a bug in application code.

CREATE TABLE document_type (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL REFERENCES organization (id),
    code                text        NOT NULL CHECK (char_length(code) BETWEEN 1 AND 50),
    name                text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    has_expiration      boolean     NOT NULL,
    required_by_default boolean     NOT NULL,
    active              boolean     NOT NULL DEFAULT true,
    sort_order          integer     NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT document_type_org_id_uq UNIQUE (organization_id, id),
    CONSTRAINT document_type_org_code_uq UNIQUE (organization_id, code)
);

CREATE TABLE vendor (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organization (id),
    company_name       text        NOT NULL CHECK (char_length(company_name) BETWEEN 1 AND 200),
    contact_name       text CHECK (char_length(contact_name) <= 120),
    email              text CHECK (char_length(email) <= 254),
    phone              text CHECK (char_length(phone) <= 40),
    category           text CHECK (char_length(category) <= 60),
    status             text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    notes              text CHECK (char_length(notes) <= 5000),
    -- SET NULL (like invitation.invited_by_user_id): the vendor outlives the user who created it.
    created_by_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT vendor_org_id_uq UNIQUE (organization_id, id)
);
-- Case-insensitive uniqueness of the (already trimmed) company name per organization. This index is the final arbiter
-- for two concurrent creates; the service pre-check only produces the friendly error in the common case.
CREATE UNIQUE INDEX vendor_org_company_name_lower_uq ON vendor (organization_id, lower(company_name));
CREATE INDEX vendor_org_status_idx ON vendor (organization_id, status);

CREATE TABLE vendor_requirement (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    vendor_id        uuid        NOT NULL,
    document_type_id uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT vendor_requirement_vendor_type_uq UNIQUE (vendor_id, document_type_id),
    -- Composite FKs (organization_id, x_id): parent and child must belong to the same organization.
    CONSTRAINT vendor_requirement_vendor_fk FOREIGN KEY (organization_id, vendor_id)
        REFERENCES vendor (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT vendor_requirement_document_type_fk FOREIGN KEY (organization_id, document_type_id)
        REFERENCES document_type (organization_id, id) ON DELETE RESTRICT
);
-- The UNIQUE above serves lookups by vendor; this one serves "which vendors require type X" and FK checks.
CREATE INDEX vendor_requirement_org_type_idx ON vendor_requirement (organization_id, document_type_id);

-- Backfill: organizations created before this migration get the same 8 default types that new organizations get from
-- DocumentTypeService.seedDefaults (keep the two lists in sync; docs/DATABASE.md is the reference).
INSERT INTO document_type (id, organization_id, code, name, has_expiration, required_by_default, active, sort_order,
                           created_at, updated_at)
SELECT gen_random_uuid(), o.id, d.code, d.name, d.has_expiration, d.required_by_default, true, d.sort_order,
       now(), now()
FROM organization o
CROSS JOIN (VALUES
    ('COI',                  'Certificate of Insurance', true,  true,  10),
    ('GENERAL_LIABILITY',    'General Liability',        true,  false, 20),
    ('WORKERS_COMP',         'Workers'' Compensation',   true,  true,  30),
    ('BUSINESS_LICENSE',     'Business License',         true,  false, 40),
    ('PROFESSIONAL_LICENSE', 'Professional License',     true,  false, 50),
    ('W9',                   'W-9',                      false, true,  60),
    ('CONTRACT',             'Contract',                 false, false, 70),
    ('OTHER',                'Other',                    false, false, 80)
) AS d (code, name, has_expiration, required_by_default, sort_order)
ON CONFLICT (organization_id, code) DO NOTHING;
