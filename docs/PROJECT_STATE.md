# PROJECT_STATE — VendorFlow

_Last updated: 2026-10-03 (session 1)_

## Current Status
Phases 0–6 ☑ **done** (Foundation, Auth & Organizations, Vendors, Documents, Compliance, Dashboard, Notifications).
**Work paused by the owner after Phase 6 (2026-10-04).** Phase 7 (CSV) not started.
Users can manage organizations/members, vendors, required document types, and upload/review/archive/download
vendor documents, see derived compliance, work from a prioritized dashboard, request documents from vendors by
email and receive a daily digest (real email delivery pending the owner's Resend key + domain).

## Current Sprint
None — paused after Phase 6 by the owner. Next when resumed: **Phase 7 — CSV import/export** (contract not written
yet). Planned design: export `GET /vendors/export.csv` (UTF-8 BOM, CSV-injection-safe cells, compliance columns);
import = upload → server-side preview (per-row CREATE/UPDATE/UNCHANGED/ERROR, stored import job with 1 h expiry) →
atomic commit with re-validation; OWNER/ADMIN; ≤1 MB / ≤2,000 rows; parser = Apache Commons CSV (decision to log in
DECISIONS.md when implemented). Ask the owner before resuming.

## Completed
- Discovery & design: docs, ADR-0001…0009, data model, API contract, threat model, privacy inventory.
- Phase 0: backend + frontend foundations, CI workflow, docker compose.
- Phase 1: identity, sessions, CSRF, verification, reset, lockout, rate limiting, absolute session lifetime,
  tenant context, RBAC, members, invitations, org settings, audit, email outbox (logging sender), e2e mailbox;
  frontend for all flows; security review + fixes.
- Phase 2: document types (read + seed), vendors CRUD, requirements, list/search/filter/sort, history; UI.
- Phase 3: documents (layered upload validation, filesystem storage with traversal-safe keys, supersede, review,
  archive, date edits, attachment downloads), document-type management, storage quota, per-user upload limit,
  header-only CSRF; UI for all; security review + fixes.
- Phase 4: pure ComplianceCalculator + single-query SQL aggregation with 38 shared Java/SQL vectors, org time zone
  "today", compliance filter/sorts, per-requirement status and days to expiry, next-action-first UI.
- Phase 5: dashboard summary + prioritized attention list (one shared compliance SQL builder), 500-vendor perf test
  (~100–160 ms), one-click upload/renew/review from the dashboard.
- Phase 6: Resend sender (HTTP, idempotency, retry classification; mock-tested), idempotent reminder ledger, daily
  digest, vendor document requests, email activity log; prod refuses the logging provider.

## In Progress
- Nothing. Paused after Phase 6 at the owner's request.

## Blocked
- Nothing. CI has never run (no git remote) — owner must create the GitHub repo and push.

## Next
1. Phase 2 → 3 (Documents) → 4 (Compliance) → 5 (Dashboard) → 6 (Notifications) → 7 (CSV) → 8 (Billing)
   → 9 (Hardening) → 10 (E2E/QA). Owner authorized chaining (see Important Context).

## Known Issues
- Running the jar directly on Windows/JDK 25 needs `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/vf-tmp`
  (`mvnw verify` / `spring-boot:run` handle it automatically via the `windows-uds-tmpdir` pom profile).
- Playwright dev server logs "The destination stream closed early" when contexts close mid-stream — harmless.
- **Dev machine resource exhaustion (2026-10-03 evening)**: with ~5 GB commit memory free, vitest workers and
  Chromium failed to start and Docker/WSL slowed ~5x. Not a code issue. Use `npx vitest run --maxWorkers=2`; close
  other heavy apps before full-stack E2E.
- Background tasks started by Claude Code are killed after their time limit (30 min default): start the E2E backend
  with a long timeout, and check that port 8080 is free first (a surviving old java process silently serves stale
  config — this cost an hour of debugging).

## Technical Debt
- `MembershipRepository`/`InvitationRepository` join `AppUser` in JPQL (cross-feature read model) — accepted for
  single-query paging/sorting by name; revisit if identity becomes a separate module.
- Package-level cycle `audit` ↔ `organization` (via `TenantContext`). Consider moving `TenantContext` to `shared`
  when the architecture test is added (Phase 9).
- Rate limiter and per-recipient email throttle are in-memory/check-then-insert, per instance. Move to Postgres
  before running >1 API instance.
- Members/invitations UI reads only the first page (50) with "Showing N of M".
- `SecurityConfig` lives in `shared.security`.
- Spring Session JDBC writes on every request (lastAccessedTime) + membership query per request: ~3–4 DB round trips
  per API call. Measure and optimize in Phase 9 (e.g. session flush mode, caching membership briefly — careful: role
  changes must apply immediately).
- `S3ObjectStorage` not implemented yet (ADR-0007) — required before Phase 11. No real malware scanner, no orphan
  object sweeper, no retention policy for superseded/archived files.
- Storage quota can overshoot by (concurrent uploads × 15 MB) per org (documented).
- UX: vendor detail repeats "Add the vendor's email to request documents" on every requestable row when the vendor
  has no email — show one notice on the card instead (Phase 9 UX pass).
- Real email delivery via Resend never exercised (needs owner key + verified domain); `reminder.notification_id` unused.

## Decisions Pending
See `docs/DECISIONS.md` § Pending (hosting, pricing/plan limits, sending domain).
- **Product (owner)**: may the uploader approve their own document? Current behavior: yes (any MEMBER+). Separation
  of duties is safer for audits but hurts 1–2 person teams. Default kept until the owner decides.

## Last Session
Session 1 (2026-10-03): environment inspection; ADR-0002 owner decision; design docs; Phase 0; Phase 1 in batches
(B1/F1 parallel, B2, security review, SF1 fixes + F2 frontend alignment and full-stack E2E); Phase 1 closed;
Phase 2 (vendors) closed; Phase 3 (documents) closed after upload security review + fixes and an E2E stability fix
(standalone build, timeouts, e2e bcrypt cost). Phase 4 contract merged into API.md.

## Next Session
See `docs/SESSION_HANDOFF.md`.

## Verification (last run, 2026-10-03)
- Backend `./mvnw verify`: **449 tests, 0 failures** (incl. Resend mock tests, 40-day reminder simulation). ~25 min on this machine.
- Frontend: lint ✓, typecheck ✓, **338 unit tests** ✓ (`--maxWorkers=2` when the machine is memory-constrained), build ✓; `npm audit --omit=dev` 0.
- Full-stack E2E (standalone build + `local,e2e` backend, 2 workers): **18/18** ✓ (incl. document request + digest).

## Git
- Branch `main`, no remote. Phase 3 closing commits: `5f63856 security: fix Phase 3 review findings…`, `3c52019 test(e2e)…` + docs commit.

## Important Context
- Machine: Windows 11, Git Bash + PowerShell. JDK 25 only (compile `release=21`, ADR-0009). Maven via `mvnw`.
  Node 24 / npm 11. Docker Desktop 29 (start it before tests). No `psql`, `supabase` CLI, `gh`.
- Owner decision: own auth with Spring Security + server-side sessions (ADR-0002), not Supabase Auth.
- **Owner instruction (2026-10-04): STOP after Phase 6** — do not start Phase 7 until the owner asks. (Earlier,
  2026-10-03: chain all MVP phases through Phase 10 without asking between phases**, as long
  as each phase passes its quality gates. Stop only for blocking gate failures, business decisions (pricing, plan
  limits) or owner-only inputs (Stripe test keys, Resend key + domain, GitHub remote). Phase 11+ needs the owner.
- Tokens (verify/reset/invite) travel only in URL fragments (`#token=`) and are POSTed by the page.
- Role rule: ADMIN manages only MEMBER/VIEWER; granting ADMIN/OWNER or touching ADMIN/OWNER requires OWNER.
- Testing traps: never use spring-security-test `.with(csrf())` (poisons the shared filter chain); use
  `support/ApiClient` + `TestAccounts`. Tests moving the clock > 7 days must re-login.
- Full-stack E2E: run backend with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local,e2e`, then
  `cd frontend && E2E_FULLSTACK=1 npx playwright test` (Playwright builds and starts the standalone server itself;
  run the backend as a background task with a long timeout and make sure port 8080 was free first).
- Subagents in `.claude/agents/` load only in a new Claude Code session; in session 1 workers were launched as
  `general-purpose` + `model: sonnet` and told to follow those files.
- Bash tool on this machine fails on large heredocs with quotes — use the Write tool for files.
