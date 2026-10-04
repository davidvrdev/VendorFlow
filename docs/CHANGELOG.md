# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/). Versions start at 0.1.0 with the first beta.

## [Unreleased]
### Added
- Project documentation system: architecture, data model, API design, security/threat model, roadmap, ADRs 0001–0009.
- Backend foundation: Spring Boot 4.1.1 (Java 21 target), Flyway baseline, deny-by-default security, request ids,
  RFC 9457 error responses, Testcontainers test base, Dockerfile (non-root, layered).
- Frontend foundation: Next.js 16, Tailwind 4, shadcn/ui, API client with CSRF header + safe error parsing,
  compliance status badge, app shell, Vitest + Playwright.
- GitHub Actions CI workflow (backend verify; frontend lint/typecheck/test/build/e2e smoke).
- Phase 11 deployment preparation (ADR-0010): `S3ObjectStorage` (AWS SDK v2, Supabase Storage S3, tested against a real S3 protocol endpoint), `app.storage.provider=filesystem|s3` with a ProfileGuard rule (prod refuses filesystem without opt-in), production Dockerfiles (backend hardened, frontend standalone, ClamAV), `render.yaml` Blueprint (Frankfurt; public web, private api + clamav), CI `docker-build` and `deploy` (Render hooks) jobs, `scripts/backup-db.sh`, `/healthz` on the frontend, migration V10 (revoke Supabase API roles), graceful shutdown and log levels in `application-prod.yml`, `DEPLOYMENT.md` and `RUNBOOKS.md`.
- **Phase 1 — Authentication & Organizations**: signup (creates organization + owner), login/logout with
  server-side sessions in Postgres, CSRF, email verification, password reset (revokes all sessions), account
  lockout, rate limiting, 7-day absolute session lifetime, multi-organization membership with switching,
  roles OWNER/ADMIN/MEMBER/VIEWER enforced server-side, members management (last-owner invariant), invitations,
  organization settings, audit events, transactional email outbox. UI for all of it. Full-stack E2E suite
  (13 flows) using an e2e-profile test mailbox.

- **Phase 14 — Vendor portal**: tokenized upload links (no vendor account; token in `X-Portal-Token`, hashed at rest,
  oracle-free 404), staff create/list/revoke + email, public `/portal` page (fragment token stripped), same upload
  pipeline (ClamAV, quota), portal uploads over an approved document become CANDIDATEs that never displace it until
  approved, per-link upload/byte budgets, pre-multipart token guard, "Via portal" badges. ADR-0011.

- **Phase 9 — Security hardening**: ArchUnit architecture rules (repository scoping, no entities in API, cycle
  ratchet), endpoint inventory test (auth + CSRF on every endpoint), API + page security headers, per-request nonce CSP,
  rate-limit coverage test, OSV dependency scan + opt-in OWASP profile, Tomcat/Jackson CVE bumps. OWASP ASVS L1
  walkthrough; all FAILs fixed: ClamAV scanning (fail closed), change password, top-100k common-password list,
  Argon2id with bcrypt upgrade-on-login, `__Host-` session cookie, show/hide password toggles.
- **Phase 10 — E2E / QA**: the 10 critical flows traced to Playwright specs (incl. signed Stripe webhook against an
  e2e-only gateway stub), global fixture failing on CSP violations / page errors (found and fixed Radix style
  injections), cold-start flake fixed, full-stack E2E job in CI.

- **Phase 8 — Billing**: per-organization subscription (14-day trial), Stripe Checkout + Customer Portal,
  signed idempotent webhooks (re-fetch from Stripe, converge out of order), read-only mode (402) when inactive,
  prod fail-closed config guard, billing settings page and app-wide subscription banner. Verified live against
  Stripe test mode (checkout session, webhook → ACTIVE, cancel → CANCELED, portal, 402 on write).

- **Phase 2 — Vendors**: per-organization document types (8 defaults; COI, Workers' Comp and W-9 required by
  default), vendor create/edit/deactivate/reactivate, per-vendor required documents, list with search, filters,
  sorting and pagination, vendor history. Full-stack E2E vendor lifecycle.
- **Phase 3 — Documents**: upload PDF/PNG/JPG up to 15 MB per vendor requirement with server-side content
  validation, automatic supersede with history, review (approve/reject with note), date edits, archive, authorized
  attachment downloads, custom document types, per-organization storage quota.
- **Phase 4 — Compliance engine**: per-requirement status (Missing, Expired, Needs review, Expiring soon, OK) and
  vendor status (Compliant, Needs attention, Non-compliant) derived live in the organization's time zone;
  compliance column, filter and sorting in the vendor list; status and days to expiry per requirement.

- **Phase 5 — Dashboard**: summary of active, compliant, attention and non-compliant vendors plus missing/expired/
  expiring/to-review documents, and a prioritized "Needs attention" list with one-click upload, renewal and review.

- **Phase 6 — Notifications**: Resend email delivery (pending owner API key + verified domain), automatic expiry
  reminders at configurable thresholds with a daily digest to owners/admins, "Request from vendor" emails, email
  activity log.
- **Phase 7 — CSV**: export vendors (with current filters and compliance columns, Excel-friendly, formula-injection
  safe) and import vendors in two steps — a per-row preview (create / update / unchanged / errors) and an all-or-nothing
  commit that never erases existing data.

### Security
- Phase 1 security review findings fixed: trusted-proxy client IP resolution, rate-limit path normalization,
  atomic lockout, fail-closed production configuration, control-character rejection in names, per-recipient
  account email throttle, token scrubbing for undelivered emails, email redaction in stored errors.
- Phase 3 upload security review findings fixed: per-organization storage quota, per-user upload rate limit,
  CSRF token accepted only from the request header, HEAD download not audited, startup warning without malware scanner.
- Phase 7 CSV security review findings fixed: header/column and cell-size caps, bounded error echo, per-user export
  rate limit, vendor row locks during import commit, re-validation of stored preview rows, stored rows cleared on commit.
