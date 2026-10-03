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

### Security
- Phase 1 security review findings fixed: trusted-proxy client IP resolution, rate-limit path normalization,
  atomic lockout, fail-closed production configuration, control-character rejection in names, per-recipient
  account email throttle, token scrubbing for undelivered emails, email redaction in stored errors.
