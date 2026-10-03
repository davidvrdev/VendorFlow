# VendorFlow — Architecture

> Read this before changing structure, adding a module, or adding a dependency.
> Decisions with real trade-offs live in `docs/adr/`. This file describes the *current* shape.

## 1. System overview

```
Browser ──HTTPS──▶ Next.js (frontend/)            ──HTTP (private)──▶ Spring Boot API (backend/) ──▶ PostgreSQL
                   • App Router UI                                     • REST /api/v1                 (Supabase in prod)
                   • /api/* rewritten to backend                       • Auth, RBAC, tenant isolation
                     (same-origin → cookies + CSRF just work)          • Compliance engine            ──▶ Object storage (private)
                   • Server Components fetch backend                   • Scheduled jobs (reminders,       (filesystem in dev,
                     forwarding the session cookie                       outbox email dispatch)            S3-compatible in prod)
                                                                       • Stripe webhooks              ──▶ Resend (email)
                                                                                                      ──▶ Stripe (billing)
```

**One deployable backend (modular monolith) + one Next.js frontend.** No microservices, no message broker,
no Redis. PostgreSQL is the only stateful dependency besides object storage. See ADR-0001.

### Why same-origin via Next.js rewrites
The browser only ever talks to the Next.js origin. `/api/*` is rewritten to the Spring backend.
This means the session cookie is first-party (`SameSite=Lax`, `HttpOnly`, `Secure`), there is no CORS surface
in production, and the backend can sit on a private network. See ADR-0003.

## 2. Backend (Spring Boot 4.x, Java 21 bytecode)

### Package layout — package-by-feature
```
com.vendorflow
├── VendorFlowApplication
├── shared/            cross-cutting: errors (ProblemDetail), request-id filter, pagination, clock, audit API
├── identity/          users, signup, login, sessions, email verification, password reset
├── organization/      organizations, memberships, roles, invitations, tenant context
├── vendor/            vendors, vendor requirements, CSV import/export
├── document/          document types, documents, uploads, storage abstraction, file validation
├── compliance/        status computation (pure domain logic) + dashboard queries
├── notification/      reminder scheduling, email outbox, EmailSender (Resend / logging)
├── billing/           Stripe customer/subscription, webhook processing
└── audit/             append-only audit events
```
Inside each feature, split only where it earns its keep:
- `api/` — `@RestController`s and request/response DTOs (Java records). Controllers are thin.
- `application/` — use-case services; own transactions (`@Transactional`) and authorization checks.
- `domain/` — entities, enums, pure logic (e.g. `ComplianceCalculator` has no Spring dependency).
- `infrastructure/` — repositories, external clients (Stripe, Resend, storage).

Rules:
- Controllers never touch repositories. Entities never leave the application layer — map to DTOs.
- Features talk to each other through application services, never another feature's repository.
- No feature reads `organizationId` from request input for authorization; it comes from `TenantContext`.

### Request lifecycle
1. `RequestIdFilter` assigns/propagates `X-Request-Id`, puts it in the MDC (appears in every log line and error body).
2. Spring Security: session lookup (Spring Session JDBC), CSRF check for unsafe methods.
3. `TenantContextFilter`: for authenticated requests, loads the *active organization* id from the session and
   **re-verifies the membership and role from the database** on every request. Missing membership → 403 and the
   active org is cleared. The role is never cached in the session (role changes apply immediately).
4. Controller → application service. Service calls `authz.require(Permission.X)` (role → permission map).
5. Repositories take `organizationId` as an explicit argument (`findByIdAndOrganizationId`). There is no
   repository method for a tenant-owned entity that does not filter by organization.
6. Errors map to RFC 9457 `ProblemDetail` with `requestId`; never a stack trace. A resource in another tenant
   returns **404**, not 403, so existence is not leaked.

### Tenant isolation — defense in depth
| Layer | Mechanism |
|---|---|
| Identity | Active org lives in the server-side session; client cannot set it except via `POST /api/v1/session/organization` which verifies membership. |
| Per request | Membership re-checked from DB each request. |
| Repository | Every tenant query is scoped by `organization_id`; ArchUnit-style test asserts it (Phase 9). |
| Database | `organization_id NOT NULL` on every tenant table; **composite foreign keys** `(organization_id, x_id)` make cross-tenant references impossible to store. |
| Tests | Every endpoint family has a cross-tenant test (user of org B probing org A ids → 404). |
| Future | Postgres RLS is deliberately deferred (ADR-0004); composite FKs + scoped repositories give most of the value now. |

### Persistence
- PostgreSQL 17 (Supabase-managed in prod, Docker locally, Testcontainers in tests).
- Spring Data JPA (Hibernate) with `ddl-auto=validate`; **Flyway owns the schema**.
- UUID primary keys generated in the application (no sequential ids exposed → no enumeration).
- `open-in-view=false`. Lists use explicit fetch joins / projections; N+1 is a review blocker.

### Compliance status — derived, not stored
Status depends on *today*, so storing it creates drift. `ComplianceCalculator` (pure function) derives:
`MISSING | REVIEW_REQUIRED | EXPIRED | EXPIRING | OK` from (requirement, current document, today, org window).
Dashboard aggregates are computed in SQL with the same rules (one source of truth tested against the Java rules).
See ADR-0005.

### Background work
- Spring `@Scheduled` jobs inside the API process. No broker.
- **Reminder job** (daily, plus on startup catch-up): detects threshold crossings (30/14/7/1 days, and after
  expiry), records each in the `reminder` ledger with a unique key, and enqueues outbox notifications.
  Unique constraints make re-runs and concurrent instances safe (idempotent by construction).
- **Outbox dispatcher** (every ~30s): claims `notification` rows with `FOR UPDATE SKIP LOCKED`, sends via
  `EmailSender`, retries with exponential backoff, marks `DEAD` after N attempts. Web requests never wait on email.
See ADR-0006.

### File storage
- `ObjectStorage` interface; `FilesystemObjectStorage` for dev/test, `S3ObjectStorage` for prod (Supabase Storage
  S3 endpoint). Object keys are server-generated (`org/{orgId}/doc/{uuid}`) — user filenames never touch paths.
- Uploads validated by size cap, extension allowlist **and** magic-byte sniffing (PDF, PNG, JPEG). Content-Type
  header is not trusted. SHA-256 recorded.
- Downloads go through an authorized backend endpoint that streams the object with
  `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`. Presigned URLs are an optional later
  optimization (ADR-0007).

### External services
| Service | Abstraction | Dev/test implementation |
|---|---|---|
| Email (Resend) | `EmailSender` | `LoggingEmailSender` (logs recipient domain + template, never body/tokens) |
| Storage | `ObjectStorage` | `FilesystemObjectStorage` under a configured root |
| Stripe | `BillingGateway` | Stripe test mode; webhook tests use real signature computation with a test secret |

## 3. Frontend (Next.js 16, App Router, TypeScript, Tailwind 4, shadcn/ui)

```
frontend/src/
├── app/                  routes only (thin): (auth)/login, (auth)/signup, (app)/dashboard, (app)/vendors/[id] ...
├── features/             feature modules: vendors/, documents/, dashboard/, auth/, settings/, billing/
│   └── <feature>/        components/, api.ts (typed calls), schemas.ts (zod), types.ts
├── components/ui/        shadcn primitives (generated, owned by us)
├── components/           shared app components (StatusBadge, EmptyState, PageHeader, DataTable)
└── lib/                  api client (server + browser variants), auth helpers, utils
```
- **Reads**: Server Components call the backend with the incoming session cookie (`lib/api/server.ts`).
- **Writes**: Client components call `/api/...` (same origin) with the CSRF header (`lib/api/client.ts`), then
  `router.refresh()`. No global client cache library until a real need appears.
- Every list: loading / empty / error states. Every form: zod validation, field errors, pending + disabled state.
- Permissions in UI are cosmetic (hide/disable); the backend enforces.
- Compliance status is always **icon + text + color**, never color alone.

## 4. Environments
| Env | Frontend | Backend | DB | Storage | Email |
|---|---|---|---|---|---|
| local | `npm run dev` | `./mvnw spring-boot:run` | Docker Postgres (compose) | filesystem | logging |
| test | Vitest / Playwright | Testcontainers | Testcontainers Postgres | temp dir | in-memory capture |
| prod | TBD (Phase 11, ADR) | container | Supabase Postgres | Supabase Storage (S3) | Resend |

## 5. Scale assumptions (Gate 6)
- 100 orgs × 100 vendors × ~8 docs ≈ 80k documents: trivial for one Postgres + one API instance.
- 10k orgs ≈ 8M documents: still one Postgres; needs indexes on `(organization_id, expiration_date)` (present),
  reminder job batching per org (designed), and 2+ API instances (sessions in DB, jobs idempotent → safe).
