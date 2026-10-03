-- V2: identity, organizations, memberships, invitations, tokens, audit, notification outbox, Spring Session.
-- See docs/DATABASE.md. Enums are text + CHECK (easier to evolve than Postgres enums).
-- Every tenant-owned table gets UNIQUE (organization_id, id) so that child tables can use composite foreign keys.

CREATE TABLE organization (
    id                    uuid PRIMARY KEY,
    name                  text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    time_zone             text        NOT NULL DEFAULT 'America/New_York',
    expiring_window_days  integer     NOT NULL DEFAULT 30 CHECK (expiring_window_days BETWEEN 1 AND 180),
    reminder_offsets_days integer[]   NOT NULL DEFAULT '{30,14,7,1}',
    reminders_enabled     boolean     NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    -- 1..5 thresholds, each 1..180 (uniqueness is validated by the API; array_length is NULL for '{}' so cardinality is used)
    CONSTRAINT organization_reminder_offsets_chk CHECK (
        cardinality(reminder_offsets_days) BETWEEN 1 AND 5
        AND 1 <= ALL (reminder_offsets_days)
        AND 180 >= ALL (reminder_offsets_days))
);

CREATE TABLE app_user (
    id                          uuid PRIMARY KEY,
    email                       text        NOT NULL CHECK (char_length(email) <= 254),
    full_name                   text        NOT NULL CHECK (char_length(full_name) BETWEEN 1 AND 100),
    password_hash               text        NOT NULL,
    email_verified_at           timestamptz,
    failed_login_attempts       integer     NOT NULL DEFAULT 0,
    locked_until                timestamptz,
    -- Convenience for restoring the active org at login. Never trusted: membership is re-verified every time.
    -- SET NULL so deleting an organization never blocks on users who last used it.
    last_active_organization_id uuid REFERENCES organization (id) ON DELETE SET NULL,
    last_login_at               timestamptz,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL
);
-- Case-insensitive uniqueness without the citext extension (portable to Supabase).
CREATE UNIQUE INDEX app_user_email_lower_uq ON app_user (lower(email));

CREATE TABLE membership (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id),
    user_id         uuid        NOT NULL REFERENCES app_user (id),
    role            text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT membership_org_id_uq UNIQUE (organization_id, id),
    CONSTRAINT membership_org_user_uq UNIQUE (organization_id, user_id)
);
CREATE INDEX membership_user_idx ON membership (user_id);

CREATE TABLE invitation (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organization (id),
    email              text        NOT NULL CHECK (char_length(email) <= 254),
    -- OWNER can never be granted by invitation (ownership transfer is a separate, explicit operation).
    role               text        NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    -- SHA-256 of the random token; the raw token only exists in the email link.
    token_hash         text        NOT NULL,
    invited_by_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    expires_at         timestamptz NOT NULL,
    accepted_at        timestamptz,
    revoked_at         timestamptz,
    created_at         timestamptz NOT NULL,
    CONSTRAINT invitation_org_id_uq UNIQUE (organization_id, id),
    CONSTRAINT invitation_token_hash_uq UNIQUE (token_hash)
);
-- At most one pending (not accepted, not revoked) invitation per org + email.
-- Expired-but-unrevoked rows still count as pending; the service revokes them before re-inviting.
CREATE UNIQUE INDEX invitation_pending_uq ON invitation (organization_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TABLE user_token (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    purpose    text        NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash text        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT user_token_hash_uq UNIQUE (token_hash)
);
CREATE INDEX user_token_user_purpose_idx ON user_token (user_id, purpose);

-- Append-only: application code only ever INSERTs. Organization/actor are nullable (e.g. signup happens before
-- an organization exists in the request context). No ON DELETE action: audit rows must never silently disappear
-- or be rewritten, so deleting an org/user is blocked until retention rules say otherwise.
CREATE TABLE audit_event (
    id              uuid PRIMARY KEY,
    organization_id uuid REFERENCES organization (id),
    actor_user_id   uuid REFERENCES app_user (id),
    action          text        NOT NULL,
    entity_type     text        NOT NULL,
    entity_id       uuid,
    metadata        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    request_id      text,
    ip_hash         text,
    created_at      timestamptz NOT NULL
);
CREATE INDEX audit_event_entity_idx ON audit_event (organization_id, entity_type, entity_id, created_at DESC);
CREATE INDEX audit_event_org_created_idx ON audit_event (organization_id, created_at DESC);

-- Transactional email outbox (ADR-0006). organization_id is NULL for account emails (verification / reset).
CREATE TABLE notification (
    id                  uuid PRIMARY KEY,
    organization_id     uuid REFERENCES organization (id),
    kind                text        NOT NULL CHECK (kind IN
        ('INVITATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'COMPLIANCE_DIGEST', 'DOCUMENT_REQUEST')),
    recipient_email     text        NOT NULL,
    -- Duplicate enqueue of the same logical email is a no-op (INSERT ... ON CONFLICT DO NOTHING).
    idempotency_key     text        NOT NULL,
    -- Minimal template data. May hold a raw token until SENT/DEAD; the dispatcher then scrubs it.
    payload             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status              text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD')),
    attempts            integer     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL,
    last_error          text,
    provider_message_id text,
    sent_at             timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT notification_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT notification_org_id_uq UNIQUE (organization_id, id)
);
-- Dispatcher scan: due rows by status.
CREATE INDEX notification_status_next_idx ON notification (status, next_attempt_at);

-- Spring Session JDBC schema, copied verbatim from
-- org/springframework/session/jdbc/schema-postgresql.sql (spring-session-jdbc 4.1.1).
-- spring.session.jdbc.initialize-schema=never: Flyway is the single owner of the schema.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
