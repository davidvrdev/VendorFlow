# PROJECT_STATE — VendorFlow

_Last updated: 2026-10-04 (session 2)_

## Current Status
Phases 0–10 ☑ **done** (… CSV, Billing, Security hardening, E2E/QA) = **MVP complete**. Owner authorized all remaining phases on 2026-10-04 (session 3); work
continues phase by phase, stopping only on owner-only blockers.
Users can manage organizations/members, vendors, required document types, upload/review/archive/download vendor
documents, see derived compliance, work from a prioritized dashboard, request documents from vendors by email, receive
a daily digest (real delivery pending the owner's Resend key + domain), and export/import vendors as CSV.

## Current Sprint
None — paused after Phase 7. Next when the owner resumes: **Phase 8 — Billing (Stripe)**. Needs owner input first:
Stripe test-mode keys, pricing and plan limits (DECISIONS.md § Pending).

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
- Phase 7: CSV export (filters, compliance columns, BOM/CRLF, formula-injection prefixing, 10k cap, per-user rate
  limit) and two-step import (server-stored preview with CREATE/UPDATE/UNCHANGED/ERROR using the vendor API's own
  validation → atomic commit with re-validation, import + vendor row locks, stored rows cleared after commit);
  Apache Commons CSV; import wizard UI; security review + fixes (header/cell caps, export rate limit, locks).

- Phase 8: subscriptions (14-day trial, Standard 49 EUR/month), Stripe Checkout/Portal, signed idempotent webhooks,
  402 read-only mode, prod fail-closed guard; billing page + banner; security review fixed (M1, L2–L5); verified live
  in Stripe test mode with the Stripe CLI forwarding webhooks.

- Phase 9: architecture/endpoint/header/rate-limit/round-trip tests, CSP, dependency scanning, ASVS L1 walkthrough
  with all FAILs fixed (ClamAV, change password, 100k list, Argon2id, __Host- cookie, password toggles).
- Phase 10: 10 critical flows green full-stack (26/26 twice), CSP/page-error fixture, CI full-stack job.
  Exploratory boundary QA (dates/time zones/idempotency) only shallow — carried to beta.

## In Progress
- Phase 11 — Production deployment: everything built and tested locally (ADR-0010 Render + Supabase, S3ObjectStorage,
  Dockerfiles, render.yaml, CI deploy hooks, backups, RUNBOOKS.md). **Blocked on the owner**: create Supabase/Render
  accounts, Resend domain, Stripe live keys + webhook, deploy hooks (docs/RUNBOOKS.md §1), then smoke test.

## Blocked
- CI: repo is on GitHub (davidvrdev/VendorFlow); first runs not yet inspected by the director (private repo, no gh CLI).

## Next
Phase 8 (Billing) → 9 (Hardening) → 10 (E2E/QA) = end of MVP; then 11 (deployment). Only on the owner's go.

## Known Issues
- Running the jar directly on Windows/JDK 25 needs `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/vf-tmp`
  (`mvnw verify` / `spring-boot:run` handle it automatically via the `windows-uds-tmpdir` pom profile).
- Dev machine can run low on commit memory: use `npx vitest run --maxWorkers=2 --testTimeout=30000`; close other heavy
  apps before full-stack E2E. A full `./mvnw verify` takes 10–25 min here.
- Background tasks started by Claude Code are killed after their time limit: start the E2E backend/frontend with a
  long timeout, and make sure port 8080 is free first (a surviving old java process silently serves stale code).

## Technical Debt
- `MembershipRepository`/`InvitationRepository` join `AppUser` in JPQL (cross-feature read model) — accepted.
- Feature cycles `{identity, notification, organization}` and `{compliance, document, vendor}` frozen by the ArchUnit ratchet (audit↔organization fixed).
- Rate limiter, per-recipient email throttle are in-memory/per instance — move to Postgres before >1 API instance.
- Members/invitations UI reads only the first page (50) with "Showing N of M".
- Measured: 3 fixed DB statements per authenticated request (session read, membership, session touch); enforced by DbRoundTripsTest.
- `S3ObjectStorage` not implemented (ADR-0007), no real malware scanner, no orphan sweeper, no file retention policy.
- Storage quota can overshoot by (concurrent uploads × 15 MB) per org (documented).
- UX: vendor detail repeats "Add the vendor's email to request documents" per row — one card-level notice instead.
- Real email delivery via Resend never exercised (needs owner key + verified domain); `reminder.notification_id` unused.
- `PUT /vendors/{id}` has no optimistic locking (`@Version`): concurrent edits are last-writer-wins (CSV commit locks).
- CSV header cap counts trailing empty header cells (a sheet exported with >30 trailing commas is rejected).
- Ingress should cap `POST /vendors/import/preview` bodies at ~1.5 MB (DEPLOYMENT.md to update in Phase 11).

## Decisions Pending
See `docs/DECISIONS.md` § Pending (hosting, pricing/plan limits, sending domain).
- **Product (owner)**: may the uploader approve their own document? Current behavior: yes (any MEMBER+). Separation
  of duties is safer for audits but hurts 1–2 person teams. Default kept until the owner decides.

## Last Session
Session 2 (2026-10-04): owner authorized Phase 7 only. Contract → backend + frontend workers in parallel →
integration (E2E 19/19) → security review (no critical/high) → fixes (header/cell caps, export rate limit, vendor
row locks on commit, stored-row re-validation) → closed. Paused as instructed.
Session 1 (2026-10-03/04): environment inspection; ADR-0002 owner decision; design docs; Phase 0; Phase 1 in batches
(B1/F1 parallel, B2, security review, SF1 fixes + F2 frontend alignment and full-stack E2E); Phase 1 closed;
Phase 2 (vendors) closed; Phase 3 (documents) closed after upload security review + fixes and an E2E stability fix
(standalone build, timeouts, e2e bcrypt cost). Phase 4 contract merged into API.md.

## Next Session
See `docs/SESSION_HANDOFF.md`.

## Verification (last run, 2026-10-04)
- Backend `./mvnw verify`: **516 tests, 0 failures** (run by the security-fix worker on the final Phase 7 code).
- Frontend: lint ✓, typecheck ✓, **371 unit tests** ✓, build ✓; `npm audit --omit=dev` 0 (Phase 7 frontend worker).
- Full-stack E2E (standalone build + `local,e2e` backend, 2 workers): **19/19** ✓ (incl. CSV export → preview → commit).

## Git
- Branch `main`, no remote. Phase 7: `51b3237 feat(vendor): CSV export and two-step CSV import…`, `e05c7c6 feat(frontend)…` + docs commit.

## Important Context
- Machine: Windows 11, Git Bash + PowerShell. JDK 25 only (compile `release=21`, ADR-0009). Maven via `mvnw`.
  Node 24 / npm 11. Docker Desktop 29 (start it before tests). No `psql`, `supabase` CLI, `gh`.
- Owner decision: own auth with Spring Security + server-side sessions (ADR-0002), not Supabase Auth.
- **Owner instruction (2026-10-04): the owner authorizes phases explicitly.** Last authorization: Phase 7 only, then
  stop. Do NOT start Phase 8 (Billing) until the owner says so. Within an authorized phase, still stop for blocking
  gate failures, business decisions (pricing, plan limits) or owner-only inputs (Stripe keys, Resend key + domain,
  GitHub remote).
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
