-- V12: automated vendor chasing (Phase 15, ADR-0012). See docs/DATABASE.md "Phase 15 migration".

-- One optional row per organization; no row = defaults with chasing DISABLED (opt-in).
CREATE TABLE chasing_settings (
    organization_id  uuid PRIMARY KEY REFERENCES organization (id) ON DELETE CASCADE,
    enabled          boolean     NOT NULL DEFAULT false,
    cadence_days     int         NOT NULL DEFAULT 7  CHECK (cadence_days BETWEEN 3 AND 30),
    max_attempts     int         NOT NULL DEFAULT 4  CHECK (max_attempts BETWEEN 1 AND 10),
    lead_days        int         NOT NULL DEFAULT 30 CHECK (lead_days BETWEEN 7 AND 90),
    send_hour_local  int         NOT NULL DEFAULT 9  CHECK (send_hour_local BETWEEN 0 AND 23),
    cc_staff         boolean     NOT NULL DEFAULT false,
    updated_at       timestamptz NOT NULL
);
CREATE INDEX chasing_settings_enabled_idx ON chasing_settings (organization_id) WHERE enabled;

-- Per-vendor state, created lazily (pause, opt-out, episode counters).
CREATE TABLE vendor_chasing (
    vendor_id                uuid PRIMARY KEY,
    organization_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    paused                   boolean     NOT NULL DEFAULT false,
    -- MANUAL = staff paused; OPT_OUT = the vendor unsubscribed (staff cannot resume through the API).
    paused_reason            text        CHECK (paused_reason IN ('MANUAL', 'OPT_OUT')),
    paused_at                timestamptz,
    -- Chases sent in the current episode (reset when the vendor has no deficient requirement).
    episode_attempts         int         NOT NULL DEFAULT 0 CHECK (episode_attempts >= 0),
    last_chased_at           timestamptz,
    last_chased_local_date   date,
    last_link_id             uuid,
    -- Set once per episode when staff were told the attempts are used up.
    exhausted_notified_at    timestamptz,
    updated_at               timestamptz NOT NULL,
    CONSTRAINT vendor_chasing_paused_reason_ck CHECK (paused = (paused_reason IS NOT NULL)),
    CONSTRAINT vendor_chasing_vendor_fk FOREIGN KEY (organization_id, vendor_id)
        REFERENCES vendor (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT vendor_chasing_link_fk FOREIGN KEY (organization_id, last_link_id)
        REFERENCES vendor_upload_link (organization_id, id) ON DELETE RESTRICT
);
CREATE INDEX vendor_chasing_org_idx ON vendor_chasing (organization_id);

-- Ledger: one row per chase. UNIQUE (vendor_id, local_date) is the idempotency guarantee (one chase per vendor per
-- org-local day, however many ticks or instances run).
CREATE TABLE vendor_chase (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    vendor_id           uuid        NOT NULL,
    local_date          date        NOT NULL,
    attempt             int         NOT NULL CHECK (attempt >= 1),
    types               jsonb       NOT NULL,
    link_id             uuid        NOT NULL,
    -- Hex SHA-256 of the 256-bit opt-out token carried by this chase's email; the raw token is never stored.
    opt_out_token_hash  text        NOT NULL CHECK (char_length(opt_out_token_hash) = 64),
    opt_out_expires_at  timestamptz NOT NULL,
    -- Hex SHA-256 of the lower-cased address this chase was emailed to: the opt-out suppresses exactly that address and
    -- the per-recipient cross-organization daily cap counts these rows (no plaintext address is copied).
    recipient_hash      text        NOT NULL CHECK (char_length(recipient_hash) = 64),
    created_at          timestamptz NOT NULL,
    CONSTRAINT vendor_chase_vendor_day_uq UNIQUE (vendor_id, local_date),
    CONSTRAINT vendor_chase_org_id_uq UNIQUE (organization_id, id),
    CONSTRAINT vendor_chase_opt_out_uq UNIQUE (opt_out_token_hash),
    CONSTRAINT vendor_chase_vendor_fk FOREIGN KEY (organization_id, vendor_id)
        REFERENCES vendor (organization_id, id) ON DELETE CASCADE,
    -- Deferred: the ledger row is inserted first (idempotency claim), the link right after in the same transaction.
    CONSTRAINT vendor_chase_link_fk FOREIGN KEY (organization_id, link_id)
        REFERENCES vendor_upload_link (organization_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX vendor_chase_vendor_idx ON vendor_chase (organization_id, vendor_id, created_at DESC);
CREATE INDEX vendor_chase_recipient_idx ON vendor_chase (recipient_hash, created_at);
-- One chase per address per UTC day across ALL organizations: closes the race of two organizations chasing the same
-- address at the same moment (insertChase ON CONFLICT DO NOTHING turns it into a refused claim).
CREATE UNIQUE INDEX vendor_chase_recipient_day_uq ON vendor_chase (recipient_hash, ((created_at AT TIME ZONE 'UTC')::date));

-- Global (NOT tenant-scoped) suppression list: an address that unsubscribed from automated chasing is not emailed by
-- chasing, manual document requests or portal-link emails of ANY organization. Only the SHA-256 of the lower-cased
-- address is stored. Deliberately no organization_id: the consent belongs to the address, not to a tenant.
CREATE TABLE email_suppression (
    email_hash  text        PRIMARY KEY CHECK (char_length(email_hash) = 64),
    reason      text        NOT NULL CHECK (reason IN ('CHASING_OPT_OUT')),
    created_at  timestamptz NOT NULL
);

ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check CHECK (kind IN
    ('INVITATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'COMPLIANCE_DIGEST', 'DOCUMENT_REQUEST', 'PASSWORD_CHANGED',
     'PORTAL_UPLOAD', 'VENDOR_CHASE', 'CHASING_STAFF_NOTICE'));
