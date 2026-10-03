# VendorFlow — Decision Log

Major decisions get an ADR in `docs/adr/`. Small decisions are logged here, one line each, newest first.

## ADR index
| ADR | Title | Status |
|---|---|---|
| [0001](adr/ADR-0001-modular-monolith.md) | Modular monolith | Accepted |
| [0002](adr/ADR-0002-authentication-spring-sessions.md) | Spring Security + server-side sessions | Accepted (owner-confirmed) |
| [0003](adr/ADR-0003-same-origin-via-nextjs-rewrites.md) | Same-origin API via Next.js rewrites | Accepted |
| [0004](adr/ADR-0004-tenant-isolation.md) | Tenant isolation strategy | Accepted |
| [0005](adr/ADR-0005-derived-compliance-status.md) | Derived compliance status | Accepted |
| [0006](adr/ADR-0006-jobs-outbox-in-postgres.md) | @Scheduled + Postgres outbox | Accepted |
| [0007](adr/ADR-0007-file-storage-and-downloads.md) | Private storage, authorized downloads | Accepted |
| [0008](adr/ADR-0008-fixed-roles.md) | Fixed roles enum | Accepted |
| [0009](adr/ADR-0009-runtime-versions.md) | Java 21 target, Boot 4.1, Next 16 | Accepted |

## Small decisions
| Date | Decision | Why |
|---|---|---|
| 2026-10-03 | Docs and UI copy in English; chat with owner in Spanish | US market; docs are read by future agents/devs |
| 2026-10-03 | Monorepo: `backend/`, `frontend/`, `docs/` | One place for state, one CI, atomic cross-cutting changes |
| 2026-10-03 | npm (not pnpm) for the frontend | Already installed; no measurable benefit at this size |
| 2026-10-03 | bcrypt via DelegatingPasswordEncoder, not Argon2 | Argon2 needs BouncyCastle; bcrypt cost 12 is OWASP-acceptable and upgradeable in place |
| 2026-10-03 | Enums as `text` + CHECK, not Postgres enums | Easier evolution; portable |
| 2026-10-03 | Emails case-insensitive via `lower(email)` unique index, no `citext` | Avoid extension dependency |
| 2026-10-03 | Document history = superseded chain + audit events (no separate `document_version` table) | One concept fewer; "new upload supersedes" matches how COIs renew; nothing is overwritten |
| 2026-10-03 | Document types seeded per organization | Each org can rename/deactivate types without global coupling |
| 2026-10-03 | Daily digest email instead of one email per document | Avoid inbox noise; "every alert implies an action" |
| 2026-10-03 | Org becomes read-only (not locked out) when subscription inactive | Never hold customer data hostage; reduces churn friction |
| 2026-10-03 | Foreign/nonexistent resource → 404 (not 403) | Avoid leaking existence across tenants |
| 2026-10-03 | No global client-side cache lib (TanStack Query) for now | Server Components + `router.refresh()` suffice; add when a real need appears |

## Pending decisions
| Decision | Needed by | Notes |
|---|---|---|
| Production hosting for backend + frontend | Phase 11 | Candidates: Fly.io / Render / Railway for API, Vercel for Next.js; Supabase for DB+storage |
| Pricing & plan limits | Phase 8 | One paid plan in MVP; price/limits are a business decision for the owner |
| Product domain name / sending domain for Resend | Phase 6 | Needs DNS (SPF/DKIM) — owner action |
| Max vendors per plan? | Phase 8 | Business decision |
