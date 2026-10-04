# VendorFlow — Roadmap

Rule: a phase is done only when it passes all quality gates (see `CLAUDE.md` § Quality gates).
Do not start future phases early. Status: ☐ not started · ◐ in progress · ☑ done.

| # | Phase | Status | Exit criteria (summary) |
|---|---|---|---|
| — | Discovery & design (docs, ADRs, data model, threat model) | ☑ | Docs system in place; architecture + DB + API + security designed |
| 0 | Foundation | ☑ | Backend + frontend scaffolds build; Flyway baseline; health endpoint; request ids; ProblemDetail errors; docker compose Postgres; CI runs build + tests; Testcontainers smoke test |
| 1 | Authentication & Organizations | ☑ | Signup/login/logout/verify/reset; sessions in DB; CSRF; org creation; memberships; RBAC; invitations; tenant context filter; audit events; email outbox + dispatcher (logging sender); rate limits; cross-tenant + role tests; UI for auth, members, invitations, org settings |
| 2 | Vendors | ☑ | Document types (read) + per-org seed; CRUD, requirements, list w/ search/filter/pagination, UI with all states, audit events |
| 3 | Documents | ☑ | Document types; upload with validation (magic bytes, size); storage abstraction; download; supersede; review; history |
| 4 | Compliance engine | ☑ | `ComplianceCalculator` + SQL aggregates agree (shared test vectors); org time zone; vendor status |
| 5 | Dashboard | ☑ | Summary counts + prioritized attention list; fast with 500 vendors / 5k docs |
| 6 | Notifications | ☑ | Resend sender (outbox exists since Phase 1); reminder ledger; daily digest; document request; idempotency tests |
| 7 | CSV | ☑ | Export; import with full validation preview + atomic commit |
| 8 | Billing | ☑ | Trial; Checkout; Portal; signed + idempotent webhooks; read-only mode when inactive |
| 9 | Security hardening | ☑ | CSP & headers; rate limits verified; repository-scope architecture test; dependency scanning; OWASP ASVS L1 pass |
| 10 | E2E / QA | ☑ | Playwright: the 10 critical flows in TESTING.md green in CI |
| 11 | Production deployment | ◐ | Hosting ADR; containers; migrations in pipeline; secrets; monitoring; backups; runbooks |
| 12 | Beta | ☐ | 3–5 design partners onboarded; feedback loop |
| 13 | AI extraction | ☐ | Structured extraction with confidence + human review + provenance |
| 14 | Vendor portal | ☐ | Tokenized upload links, no vendor account |
| 15 | Automated chasing | ☐ | Scheduled vendor follow-ups |
| 16 | Integrations | ☐ | PMS / accounting / CRM connectors |

## Phase 0 — Foundation task list (done 2026-10-03; CI workflow written, not yet run: no git remote)
- [x] Repo skeleton, `.gitignore`, `.env.example`, `docker-compose.yml` (Postgres 17)
- [x] Backend: Spring Boot 4.1 / Java 21 target, Maven wrapper, Flyway, JPA, Security (locked down), Actuator, validation
- [x] Backend: `RequestIdFilter`, global `ProblemDetail` handler, structured logging config, profiles (local/test/prod)
- [x] Backend: Testcontainers base test + context-loads + health + error-shape tests
- [x] Frontend: Next.js 16 + TS strict + Tailwind 4 + shadcn/ui init, ESLint, Vitest, Playwright installed
- [x] Frontend: app shell layout, `/api` rewrite to backend, API client stubs, one smoke test each (Vitest, Playwright)
- [x] CI: GitHub Actions — backend `verify`, frontend lint/typecheck/test/build
- [x] Project subagents in `.claude/agents/`
- [x] Docs updated, first commits
