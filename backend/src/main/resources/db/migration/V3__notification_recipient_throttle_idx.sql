-- V3: supports the per-recipient throttle of account emails (OutboxService): count rows per lower(recipient) in the
-- last hour.
CREATE INDEX notification_recipient_created_idx ON notification (lower(recipient_email), created_at);
