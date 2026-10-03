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
| 2026-10-03 | `server.forward-headers-strategy=native` + `server.tomcat.remoteip.internal-proxies` (env `TRUSTED_PROXIES_REGEX`) in all profiles | `framework` trusted the leftmost X-Forwarded-For (spoofable rate-limit/audit key); RemoteIpValve honours it only from trusted proxies. Deployment requirement: SECURITY.md section 9 |
| 2026-10-03 | Rate-limit rules match the decoded, normalized, lower-cased path (UrlPathHelper + cleanPath) | `/auth/%6cogin` reached the handler unlimited; all variants now share one budget |
| 2026-10-03 | Lockout decided in SQL after bcrypt (both update statements require "not locked now") | The pre-bcrypt snapshot let parallel guesses and an in-flight correct guess bypass the lock |
| 2026-10-03 | `session-cookie-secure` defaults to true; ProfileGuard refuses weak prod config and a non-local DB without prod | A missing `SPRING_PROFILES_ACTIVE` must fail closed, not run with local creds/secret |
| 2026-10-03 | `@PlainText` (rejects Cc + Cf) on all human-entered names; max 3 verification/reset emails per recipient per hour (silently dropped) | Control/bidi characters in email bodies; mailbox flooding via anonymous endpoints. Cf also blocks ZWJ in names (accepted) |
| 2026-10-03 | Undelivered outbox rows lose their raw token after the token lifetime (24h; invitations 7d) and become DEAD; `last_error` also redacts email addresses | Raw tokens must not linger; provider errors may echo addresses |
| 2026-10-03 | Absolute session lifetime 7 days via `SessionLifetimeFilter` (order -101, before the Security chain) | A sliding idle timeout alone lets a stolen session live forever; running before Security keeps public endpoints usable with an expired cookie and yields 401 through the normal entry point |
| 2026-10-03 | E2E mailbox answers only loopback callers (404 otherwise) | Defense in depth on top of profile e2e |
| 2026-10-03 | `shadcn` is a devDependency | Only its CSS is consumed at build time; keeps its CLI dependency tree (7 high advisories) out of production |
| 2026-10-03 | `spring.profiles.default=local` (not `active`) | Env/test profiles replace it instead of stacking |
| 2026-10-03 | `NullRequestCache` in Spring Security | JSON API: a 401 must not create a session cookie |
| 2026-10-03 | Stable jar name `vendorflow-api.jar` | Dockerfile independent of version bumps |
| 2026-10-03 | Email outbox built in Phase 1 (not Phase 6) | Verification/reset/invite emails need it; avoids a throwaway sync sender |
| 2026-10-03 | Secret tokens only in URL fragments of email links, POSTed by the page | Keeps tokens out of access logs and Referer headers |
| 2026-10-03 | Docs and UI copy in English; chat with owner in Spanish | US market; docs are read by future agents/devs |
| 2026-10-03 | Monorepo: `backend/`, `frontend/`, `docs/` | One place for state, one CI, atomic cross-cutting changes |
| 2026-10-03 | npm (not pnpm) for the frontend | Already installed; no measurable benefit at this size |
| 2026-10-03 | bcrypt via DelegatingPasswordEncoder, not Argon2 | Argon2 needs BouncyCastle; bcrypt cost 12 is OWASP-acceptable and upgradeable in place |
| 2026-10-03 | Enums as `text` + CHECK, not Postgres enums | Easier evolution; portable |
| 2026-10-03 | Emails case-insensitive via `lower(email)` unique index, no `citext` | Avoid extension dependency |
| 2026-10-03 | Document history = superseded chain + audit events (no separate `document_version` table) | One concept fewer; "new upload supersedes" matches how COIs renew; nothing is overwritten |
| 2026-10-03 | Document types seeded per organization | Each org can rename/deactivate types without global coupling |
| 2026-10-03 | Daily digest email instead of one email per document | Avoid inbox noise; "every alert implies an action" |
| 2026-10-03 | Storage quota per organization, default 5 GB, sums ALL states; pre-check with declared size + authoritative re-check with the streamed count inside the upload transaction; not serialized per org (overshoot <= concurrent uploads x 15 MB) | Superseded/archived files still occupy storage; serializing per org would need an org-level lock on every upload for a bounded, small overshoot |
| 2026-10-03 | Per-user upload limit (30/min) checked in `DocumentUploadService` via `RateLimiter`, `ApiException` carries optional Retry-After | The user id is only known after authentication; keeps one limiter implementation and the global 429 problem shape |
| 2026-10-03 | CSRF token resolved from the header only (`HeaderOnlyCsrfTokenRequestHandler`) | The `_csrf` parameter fallback makes Tomcat parse/spool multipart bodies before CSRF can reject; all clients already send the header |
| 2026-10-03 | `HEAD /documents/{id}/download` -> 405 in the controller | Spring maps HEAD to the GET handler; it would audit a download that served no file |
| 2026-10-03 | No-op `FileScanner` is a named class; prod + no-op logs one WARN at startup (does not fail) | Visible in prod logs without blocking staging/early deployments; failing startup would block the MVP before the scanner exists |
| 2026-10-03 | Uploads to INACTIVE vendors allowed; changing a type's `hasExpiration` keeps existing dates (Phase 4 compliance interprets them) | Keeps records complete and avoids destructive data changes on a config toggle |
| 2026-10-03 | Ingress must cap bodies (16 MB only on the upload route, ~1 MB elsewhere), timeouts, per-IP connections (DEPLOYMENT.md) | The app cannot stop slow/oversized requests before accepting them; Next `proxyClientMaxBodySize` is global |
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
