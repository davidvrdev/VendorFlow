-- V6: expiry-reminder ledger (docs/DATABASE.md, ADR-0006) + per-organization "last reminder run" marker.

-- Run tracking: the date (in the organization's own time zone) of the last completed reminder run. The job claims an
-- organization by locking its row (SELECT ... FOR UPDATE), checks this date, does its work and stores today's date in
-- the same transaction: at most one run per org-local day, even with several instances, and a crash re-runs the day.
ALTER TABLE organization ADD COLUMN last_reminder_run_date date;

CREATE TABLE reminder (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organization (id),
    document_id      uuid        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('EXPIRING', 'EXPIRED')),
    -- Threshold in days (30/14/7/1...) for EXPIRING; 0 for EXPIRED.
    offset_days      int         NOT NULL CHECK (offset_days >= 0),
    -- The expiration date this row was raised for: a renewal (new date) naturally restarts the thresholds.
    expiration_date  date        NOT NULL,
    -- Reserved (digests are per recipient, so one ledger row has no single notification); stays NULL for now.
    notification_id  uuid,
    -- Set when the row was included in a daily digest; NULL = not yet reported.
    digested_at      timestamptz,
    created_at       timestamptz NOT NULL,
    -- A threshold fires once per (document, kind, offset, expiration date), however often/wherever the job runs.
    CONSTRAINT reminder_threshold_uq UNIQUE (document_id, kind, offset_days, expiration_date),
    -- Composite FKs: a reminder can never point at a document/notification of another organization.
    CONSTRAINT reminder_document_fk FOREIGN KEY (organization_id, document_id)
        REFERENCES document (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT reminder_notification_fk FOREIGN KEY (organization_id, notification_id)
        REFERENCES notification (organization_id, id) ON DELETE RESTRICT
);

-- "Rows of this organization not yet digested" (small partial index).
CREATE INDEX reminder_org_undigested_idx ON reminder (organization_id) WHERE digested_at IS NULL;
-- FK support for document lookups.
CREATE INDEX reminder_org_document_idx ON reminder (organization_id, document_id);

-- GET /notifications: newest first per organization.
CREATE INDEX notification_org_created_idx ON notification (organization_id, created_at DESC);
