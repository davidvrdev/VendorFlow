# CLAUDE.md — VendorFlow

**VendorFlow** is a B2B SaaS "Vendor Compliance OS" for US property managers, HOA managers and facilities managers:
*"Upload your vendor list. VendorFlow tells you what's missing, what's expiring, and who you need to chase."*
Model: **Vendor → Required documents → Current status → Expiration → Action.**

## Start of every session (mandatory)
1. Read this file, then `docs/PROJECT_STATE.md`, `docs/SESSION_HANDOFF.md`, `docs/ROADMAP.md`.
2. `git status` and `git log --oneline -10`; inspect uncommitted changes.
3. Run the relevant tests (see Commands).
4. Tell the owner briefly: CURRENT STATE · CURRENT TASK · BLOCKERS · NEXT ACTION. Then work.

Read before touching: architecture → `docs/ARCHITECTURE.md` + `docs/DECISIONS.md`; security/auth/uploads →
`docs/SECURITY.md`; tests → `docs/TESTING.md`; schema → `docs/DATABASE.md`; endpoints → `docs/API.md`.

## End of every session (mandatory)
Run tests + build → review `git diff` → update `docs/PROJECT_STATE.md`, `docs/SESSION_HANDOFF.md`,
`docs/ROADMAP.md` (if changed), `docs/CHANGELOG.md`, decisions (ADR or DECISIONS.md) → commit. Never leave state ambiguous.

## Stack
- Backend: Java 21 target (JDK ≥21), Spring Boot 4.1, Maven wrapper, Spring Security (sessions via Spring Session
  JDBC), Spring Data JPA, Flyway, PostgreSQL 17. Tests: JUnit 5, Spring Boot Test, Testcontainers.
- Frontend: Next.js 16 (App Router), React 19, TypeScript strict, Tailwind 4, shadcn/ui, zod, react-hook-form.
  Tests: Vitest + Testing Library, Playwright.
- Infra: Docker / docker compose (local Postgres), GitHub Actions. Prod (planned): Supabase Postgres + Storage,
  Resend, Stripe.

## Repository layout
```
backend/     Spring Boot API (package-by-feature under com.vendorflow)
frontend/    Next.js app (src/app routes, src/features modules, src/components/ui shadcn)
docs/        Source of truth: state, roadmap, architecture, ADRs, runbooks
.claude/     Project subagent definitions (agents/) and shared settings
docker-compose.yml   Local Postgres
.env.example         All env vars (placeholders only)
```

## Commands
```bash
docker compose up -d postgres                 # local DB (Docker Desktop must be running)
cd backend && ./mvnw spring-boot:run          # API on :8080 (profile "local" by default)
cd backend && ./mvnw verify                   # all backend tests (Testcontainers needs Docker)
cd frontend && npm run dev                    # UI on :3000, /api/* proxied to :8080
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
cd frontend && npx playwright test            # E2E (needs backend + db running)
```
On Windows use `mvnw.cmd` from PowerShell/cmd, or `./mvnw` from Git Bash.

**Migrations**: add `backend/src/main/resources/db/migration/V{n}__{snake_description}.sql`. Forward-only; never edit
an applied migration. Flyway runs on startup; tests run every migration against a real Postgres.

## Development rules
- Package-by-feature; controllers thin; entities never leave the application layer (DTO records out).
- One feature never uses another feature's repository — call its application service.
- Every list endpoint paginated (max 100). No N+1 (fetch joins/projections). Transactions in application services.
- Errors: RFC 9457 ProblemDetail with `requestId`. Never expose stack traces or internal messages.
- New dependency? Justify it (needed? maintained? license? CVEs? simpler alternative?) and log it in DECISIONS.md.
- Frontend: every list has loading/empty/error; every form has validation/errors/pending/disabled; destructive
  actions confirm. Status = icon + text + color. Semantic HTML, labels, visible focus.
- Comments explain *why*, not *what*. The owner must be able to read and understand every part of the code.

## Security rules (non-negotiable — see docs/SECURITY.md)
- Organization id comes from `TenantContext` (server session + DB-verified membership), **never** from client input.
- Every tenant repository query filters by `organizationId`. Foreign ids → 404.
- Authorization checked in application services (`AuthorizationService.require(Permission.X)`).
- Every new endpoint ships with: authz test for the lowest denied role + cross-tenant test.
- Request DTOs are explicit records; never bind request bodies to entities (mass assignment).
- Uploads: size cap, extension allowlist, magic-byte check, server-generated keys, private storage, attachment downloads.
- Never log passwords, tokens, session ids, API keys, webhook bodies, document contents.
- Never commit secrets. `.env*` is gitignored; only `.env.example` with placeholders.

## Git rules
- Branch `main`; small semantic commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `security:`, `chore:`.
- Before committing: `git status`, `git diff`, run tests.
- Never `git reset --hard`, `git clean -fd`, `rm -rf`, force-push, or delete files/data without explicit owner approval.

## Multi-agent workflow
- The main session (Opus) is the **director**: plans, decomposes, owns architecture and integration, reviews every
  worker result against the Definition of Done, and keeps `docs/` current.
- Workers are project subagents in `.claude/agents/` (Sonnet): `backend-engineer`, `frontend-engineer`,
  `qa-engineer`, `security-reviewer`, `code-reviewer`. Launch them via the Agent tool.
- Every worker brief contains: goal, context, relevant files, constraints, acceptance criteria, definition of done.
- Every worker reports: what it did, files changed, tests run, results, problems, decisions, risks, remaining work.
- Parallelize only independent work; never two workers on the same critical files at once.
- "Compiles" ≠ done. Done = Definition of Done below.

## Definition of Done
Code implemented · architecture reviewed · relevant tests (incl. authz + tenant isolation) · error handling ·
validation · security review · complete UI states · reasonable accessibility · adequate logging · docs updated ·
PROJECT_STATE + ROADMAP updated · no known relevant bugs · clean build · tests pass · git diff reviewed.

## Quality gates (before closing a phase)
1 Functional · 2 Tests pass · 3 Security (cross-tenant? unauthorized endpoints? id tampering? dangerous files?) ·
4 UX states · 5 Architecture maintainable · 6 Production (100 users? 10k?) · 7 Documentation (could the next Claude continue?)

## Do NOT
- Build future-phase features early (AI, vendor portal, integrations) — see ROADMAP.
- Add microservices, brokers, Redis, or infrastructure without an ADR.
- Trust the frontend for authorization or Stripe data from the browser.
- Disable/delete failing tests to get green. Fake an integration. Hardcode credentials.
- Create god classes/components or put everything in one file.
