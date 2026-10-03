# PROJECT_STATE — VendorFlow

_Last updated: 2026-10-03 (session 1)_

## Current Status
Phase 0 (Foundation) **done**. Phase 1 (Authentication & Organizations) **in progress**.
No end-user product features are usable yet.

## Current Sprint
Phase 1 — split into three batches:
- **B1 (backend, running)**: V2 migration (all Phase 1 tables), Spring Session JDBC, CSRF cookie, signup/login/logout/me,
  session org switch, TenantContextFilter, RBAC (`Permission`/`RolePermissions`/`AuthorizationService`), audit,
  email outbox + dispatcher + LoggingEmailSender, rate limiting, lockout, GET/PATCH /organization.
- **F1 (frontend, running in parallel)**: auth pages, guarded `(app)` layout, org switcher, settings
  (organization, members, invitations) against the contract in `docs/API.md` § Phase 1 contract details.
- **B2 (backend, next)**: verify-email + resend, password reset (+ invalidate all sessions), members management,
  invitations (create/list/revoke/lookup/accept).
Then: integrate, run full-stack E2E (`E2E_FULLSTACK=1`), security review, code review, close Phase 1.

## Completed
- Discovery & design: docs system, ADR-0001…0009, data model, API contract, threat model, privacy inventory.
- Phase 0: backend (Boot 4.1.1, Flyway baseline, deny-by-default security, request ids, ProblemDetail errors,
  Testcontainers base, Dockerfile); frontend (Next 16, shadcn, API client w/ CSRF header, status badge, shell,
  Vitest + Playwright); CI workflow; docker compose Postgres.

## In Progress
- Phase 1 B1 + F1 (workers running in session 1).

## Blocked
- Nothing. (CI has never run: no git remote yet — owner must create the GitHub repo and push.)

## Next
1. Review B1/F1, launch B2, integrate, full-stack E2E, security + code review, commit, close Phase 1.
2. Phase 2 — Vendors.

## Known Issues
- JDK 25 on this Windows machine: embedded Tomcat start fails without
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/vf-tmp` (see DEV_SETUP Troubleshooting). Tests unaffected (MockMvc).

## Technical Debt
- `SecurityConfig` lives in `shared.security`; may move/split when identity grows (decide in Phase 1 review).

## Decisions Pending
See `docs/DECISIONS.md` § Pending (hosting, pricing/plan limits, sending domain).

## Last Session
Session 1 (2026-10-03): environment inspection; owner chose own Spring Security sessions (ADR-0002); design docs;
Phase 0 built by two parallel Sonnet workers, reviewed, fixed (shadcn → devDependency, stable jar name,
DEV_SETUP merge), committed. Phase 1 started.

## Next Session
See `docs/SESSION_HANDOFF.md`.

## Verification (last run)
- Backend `./mvnw verify`: 15 tests, 0 failures (2026-10-03).
- Frontend: lint ✓, typecheck ✓, 25 unit tests ✓, build ✓, Playwright smoke 1/1 ✓; `npm audit --omit=dev` 0 vulns.
- `docker build backend` ✓.

## Git
- Branch `main`, no remote. Last Phase-0 commit: `afb4486 docs: close Phase 0, Phase 1 API contract, dev setup`.

## Important Context
- Machine: Windows 11, Git Bash + PowerShell. JDK 25 only (compile `release=21`, ADR-0009). Maven via `mvnw`.
  Node 24 / npm 11. Docker Desktop 29 (start it before tests). No `psql`, `supabase` CLI, `gh`.
- Owner decision: own auth with Spring Security + server-side sessions (ADR-0002), not Supabase Auth.
- **Owner instruction (2026-10-03): chain all MVP phases through Phase 10 without asking between phases**, as long
  as each phase passes its quality gates. Stop only for blocking gate failures, business decisions (pricing, plan
  limits) or owner-only inputs (Stripe test keys, Resend key + domain, GitHub remote). Phase 11+ needs the owner.
- Tokens (verify/reset/invite) travel only in URL fragments (`#token=`) and are POSTed by the page.
- Email outbox moved into Phase 1 (needed by verification/invite emails).
- Subagents in `.claude/agents/` load only in a new Claude Code session; in session 1 workers were launched as
  `general-purpose` + `model: sonnet` and told to follow those files.
- Bash tool on this machine fails on large heredocs with quotes — use the Write tool for files.
