# VendorFlow — Deployment

Status: **not deployed**. Production design happens in Phase 11 (hosting ADR pending, see DECISIONS.md).

## Target shape (planned)
- **Database**: Supabase Postgres (managed backups, PITR on paid tier). Flyway migrations run by the backend at
  startup in the first iteration; move to a dedicated pipeline step if migrations get long.
- **Storage**: Supabase Storage private bucket via S3-compatible API.
- **Backend**: Docker image (Temurin 21 JRE, non-root user), health via `/actuator/health/{liveness,readiness}`.
- **Frontend**: Next.js (`output: standalone`) container or Vercel; `BACKEND_URL` points to the private API.
- **Email**: Resend with a verified sending domain (SPF/DKIM/DMARC).
- **Billing**: Stripe live mode keys; webhook endpoint `https://<app>/api/v1/webhooks/stripe`.

## Required environment variables
See `.env.example` (root) for the authoritative list. Secrets are injected by the hosting platform; never committed.

## Release checklist (to be completed in Phase 11)
- [ ] CI green on main
- [ ] Migrations reviewed (no destructive change without expand/contract)
- [ ] Secrets present in target environment
- [ ] Smoke test: signup, upload, download, webhook
- [ ] Rollback plan noted
