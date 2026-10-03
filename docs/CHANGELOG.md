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
- **Phase 1 — Authentication & Organizations**: signup (creates organization + owner), login/logout with
  server-side sessions in Postgres, CSRF, email verification, password reset (revokes all sessions), account
  lockout, rate limiting, 7-day absolute session lifetime, multi-organization membership with switching,
  roles OWNER/ADMIN/MEMBER/VIEWER enforced server-side, members management (last-owner invariant), invitations,
  organization settings, audit events, transactional email outbox. UI for all of it. Full-stack E2E suite
  (13 flows) using an e2e-profile test mailbox.

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

### Security
- Phase 1 security review findings fixed: trusted-proxy client IP resolution, rate-limit path normalization,
  atomic lockout, fail-closed production configuration, control-character rejection in names, per-recipient
  account email throttle, token scrubbing for undelivered emails, email redaction in stored errors.
- Phase 3 upload security review findings fixed: per-organization storage quota, per-user upload rate limit,
  CSRF token accepted only from the request header, HEAD download not audited, startup warning without malware scanner.
