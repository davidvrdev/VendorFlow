# PROJECT_STATE — VendorFlow

_Last updated: 2026-10-03 (session 1)_

## Current Status
Phases 0, 1 and 2 (Vendors) ☑ **done**. Phase 3 (Documents) **starting**.
Users can sign up, manage their organization/members/invitations, and manage vendors with required document
types. No document upload yet.

## Current Sprint
Phase 3 — Documents. Contract: `docs/API.md` § "Phase 3 contract details" (authoritative).
Scope: V5 migration (`document`), ObjectStorage (filesystem impl) + FileScanner hook, upload with layered validation
(size → extension → magic bytes → type → dates), supersede, review, archive, date edits, authorized download,
document-type management, vendor detail extended with current documents, vendor history includes document events;
UI: upload dialog, documents per requirement, review/archive actions, history, document-types settings page.
Plan: backend + frontend workers in parallel, then integration E2E, **security review (uploads)**, close.

## Completed
- Discovery & design: docs, ADR-0001…0009, data model, API contract, threat model, privacy inventory.
- Phase 0: backend + frontend foundations, CI workflow, docker compose.
- Phase 2: document types (read + seed), vendors CRUD, requirements, list/search/filter/sort, history; UI.
- Phase 1: identity, sessions, CSRF, verification, reset, lockout, rate limiting, absolute session lifetime,
  tenant context, RBAC, members, invitations, org settings, audit, email outbox (logging sender), e2e mailbox;
  frontend for all flows; security review + fixes.

## In Progress
- Phase 3 (Documents).

## Blocked
- Nothing. CI has never run (no git remote) — owner must create the GitHub repo and push.

## Next
1. Phase 2 → 3 (Documents) → 4 (Compliance) → 5 (Dashboard) → 6 (Notifications) → 7 (CSV) → 8 (Billing)
   → 9 (Hardening) → 10 (E2E/QA). Owner authorized chaining (see Important Context).

## Known Issues
- Running the jar directly on Windows/JDK 25 needs `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/vf-tmp`
  (`mvnw verify` / `spring-boot:run` handle it automatically via the `windows-uds-tmpdir` pom profile).
- Playwright dev server logs "The destination stream closed early" when contexts close mid-stream — harmless.

## Technical Debt
- `MembershipRepository`/`InvitationRepository` join `AppUser` in JPQL (cross-feature read model) — accepted for
  single-query paging/sorting by name; revisit if identity becomes a separate module.
- Package-level cycle `audit` ↔ `organization` (via `TenantContext`). Consider moving `TenantContext` to `shared`
  when the architecture test is added (Phase 9).
- Rate limiter and per-recipient email throttle are in-memory/check-then-insert, per instance. Move to Postgres
  before running >1 API instance.
- Members/invitations UI reads only the first page (50) with "Showing N of M".
- `SecurityConfig` lives in `shared.security`.

## Decisions Pending
See `docs/DECISIONS.md` § Pending (hosting, pricing/plan limits, sending domain).

## Last Session
Session 1 (2026-10-03): environment inspection; ADR-0002 owner decision; design docs; Phase 0; Phase 1 in batches
(B1/F1 parallel, B2, security review, SF1 fixes + F2 frontend alignment and full-stack E2E); Phase 1 closed.

## Next Session
See `docs/SESSION_HANDOFF.md`.

## Verification (last run, 2026-10-03)
- Backend `./mvnw verify`: **239 tests, 0 failures** (Testcontainers Postgres, incl. real-server trusted-proxy tests, N+1 guard).
- Frontend: lint ✓, typecheck ✓, **186 unit tests** ✓, build ✓, Playwright smoke 5/5 ✓; `npm audit --omit=dev` 0.
- Full-stack E2E (`SPRING_PROFILES_ACTIVE=local,e2e` backend + `E2E_FULLSTACK=1`): **14/14** ✓ (incl. vendor lifecycle).

## Git
- Branch `main`, no remote. Phase 1 closing commits: `ec0bdd7 security: fix Phase 1 review findings…` + docs commit.

## Important Context
- Machine: Windows 11, Git Bash + PowerShell. JDK 25 only (compile `release=21`, ADR-0009). Maven via `mvnw`.
  Node 24 / npm 11. Docker Desktop 29 (start it before tests). No `psql`, `supabase` CLI, `gh`.
- Owner decision: own auth with Spring Security + server-side sessions (ADR-0002), not Supabase Auth.
- **Owner instruction (2026-10-03): chain all MVP phases through Phase 10 without asking between phases**, as long
  as each phase passes its quality gates. Stop only for blocking gate failures, business decisions (pricing, plan
  limits) or owner-only inputs (Stripe test keys, Resend key + domain, GitHub remote). Phase 11+ needs the owner.
- Tokens (verify/reset/invite) travel only in URL fragments (`#token=`) and are POSTed by the page.
- Role rule: ADMIN manages only MEMBER/VIEWER; granting ADMIN/OWNER or touching ADMIN/OWNER requires OWNER.
- Testing traps: never use spring-security-test `.with(csrf())` (poisons the shared filter chain); use
  `support/ApiClient` + `TestAccounts`. Tests moving the clock > 7 days must re-login.
- Full-stack E2E: run backend with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local,e2e`, then
  `cd frontend && E2E_FULLSTACK=1 npx playwright test`.
- Subagents in `.claude/agents/` load only in a new Claude Code session; in session 1 workers were launched as
  `general-purpose` + `model: sonnet` and told to follow those files.
- Bash tool on this machine fails on large heredocs with quotes — use the Write tool for files.
