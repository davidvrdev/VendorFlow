-- V8: billing (docs/DATABASE.md, Phase 8). One subscription row per organization + a global Stripe webhook ledger.

CREATE TABLE subscription (
    id                      uuid PRIMARY KEY,
    organization_id         uuid        NOT NULL UNIQUE REFERENCES organization (id) ON DELETE CASCADE,
    status                  text        NOT NULL
        CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'INCOMPLETE', 'UNPAID')),
    plan                    text        NOT NULL DEFAULT 'standard',
    -- Both Stripe ids are only ever written by our own billing code (checkout flow / verified webhook).
    stripe_customer_id      text        UNIQUE,
    stripe_subscription_id  text        UNIQUE,
    -- Our own 14-day trial (no card). Access while TRIALING depends on this date, evaluated against the app clock.
    trial_ends_at           timestamptz,
    current_period_end      timestamptz,
    cancel_at_period_end    boolean     NOT NULL DEFAULT false,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL
);

-- Global (no organization): Stripe event ids are unique across the account. Insert-first idempotency.
CREATE TABLE stripe_event (
    id                 text PRIMARY KEY,
    type               text        NOT NULL,
    stripe_created_at  timestamptz NOT NULL,
    received_at        timestamptz NOT NULL,
    -- NULL while FAILED (never processed).
    processed_at       timestamptz,
    status             text        NOT NULL CHECK (status IN ('PROCESSED', 'FAILED')),
    -- Exception class name only: never payloads, messages or signatures.
    error              text
);

-- Existing organizations start a fresh 14-day trial at deploy time (the length of the trial period is
-- vendorflow.billing.trial-days for NEW organizations; this backfill is fixed at 14 days).
INSERT INTO subscription (id, organization_id, status, plan, trial_ends_at, cancel_at_period_end, created_at, updated_at)
SELECT gen_random_uuid(), o.id, 'TRIALING', 'standard', now() + interval '14 days', false, now(), now()
FROM organization o;
