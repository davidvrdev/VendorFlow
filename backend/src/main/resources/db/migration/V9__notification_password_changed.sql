-- Security notice email "your password was changed" (ASVS V2.1.6 / V3.7.1). The kind CHECK was created inline in V2,
-- so PostgreSQL named it notification_kind_check.
ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check CHECK (kind IN
    ('INVITATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'COMPLIANCE_DIGEST', 'DOCUMENT_REQUEST', 'PASSWORD_CHANGED'));
