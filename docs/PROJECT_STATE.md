# PROJECT_STATE — VendorFlow

_Last updated: 2026-10-03 (session 1)_

## Current Status
Discovery & design complete. Phase 0 (Foundation) in progress: scaffolding backend + frontend.
No product features exist yet.

## Current Sprint
Phase 0 — Foundation (see ROADMAP.md task list).

## Completed
- Environment inspection (see Important Context).
- Documentation system: CLAUDE.md, ARCHITECTURE, DATABASE, API, SECURITY (threat model + privacy inventory),
  PRODUCT_SPEC, ROADMAP, TESTING, DEPLOYMENT, DECISIONS, ADR-0001…0009, CHANGELOG.
- Project subagents: `.claude/agents/{backend-engineer,frontend-engineer,qa-engineer,security-reviewer,code-reviewer}.md`.
- `docker-compose.yml` (Postgres 17), `.env.example`, `.gitignore`.

## In Progress
- Phase 0 scaffolding (backend + frontend + CI).

## Blocked
- Nothing.

## Next
1. Finish Phase 0 and pass its gates.
2. Phase 1: auth + organizations + RBAC + invitations + tenant context.

## Known Issues
- None yet.

## Technical Debt
- None yet.

## Decisions Pending
See `docs/DECISIONS.md` § Pending (hosting, pricing, sending domain, plan limits).

## Last Session
Session 1 (2026-10-03): inspected environment, owner chose Spring Security sessions over Supabase Auth,
wrote the design docs and ADRs, started Phase 0.

## Next Session
Read SESSION_HANDOFF.md — it lists exact commands and the next task.

## Verification
- No code yet.

## Git
- Repo initialized on `main` (no remote configured).

## Important Context
- Machine: Windows 11, Git Bash + PowerShell. JDK **25** only (we compile with `release=21`, ADR-0009).
  No global Maven (use `mvnw`). Node 24 / npm 11. Docker Desktop 29 (must be running for Testcontainers/Postgres).
  No `psql`, `supabase` CLI or `gh` installed.
- Owner decision: **own auth with Spring Security + server-side sessions** (ADR-0002), not Supabase Auth.
- Subagents in `.claude/agents/` only load in a new Claude Code session; in the session that created them,
  workers were launched as `general-purpose` with `model: sonnet` and instructed to follow those files.
