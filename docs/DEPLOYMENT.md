# VendorFlow — Deployment

Status: **prepared, not yet deployed** (Phase 11). Decision and topology: ADR-0010 (Render + Supabase). The owner's
step-by-step is in `RUNBOOKS.md`. Infrastructure as code: `render.yaml`.

## Artifacts
| Path | What |
|---|---|
| `backend/Dockerfile` | Temurin 21 JRE, non-root (uid 10001), Spring Boot layered jar, `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, HEALTHCHECK on `/actuator/health/readiness`, SIGTERM-aware exec |
| `frontend/Dockerfile` | node:24-alpine, 3 stages, Next `output: standalone`, non-root `node` user, HEALTHCHECK `/healthz`. `BACKEND_URL` is a BUILD arg (rewrite target is baked at build time; default `http://vendorflow-api:8080`) |
| `clamav/Dockerfile` | official `clamav/clamav:1.4`, clamd on TCP 3310, 16 MB stream limit (>= the 15 MB upload cap) |
| `render.yaml` | Blueprint: web (public) + api (private) + clamav (private), Frankfurt, secrets `sync: false` |
| `.github/workflows/ci.yml` | `docker-build` job + `deploy` job (Render deploy hooks, main only, after all jobs, skipped when secrets are absent) |
| `scripts/backup-db.sh` | encrypted `pg_dump` (restore drill in RUNBOOKS.md) |

## Production configuration (profile `prod`)
`SPRING_PROFILES_ACTIVE=prod` is set in the image and the Blueprint. `ProfileGuard` refuses to boot when any of these is
wrong (messages name the variable, never the value): weak/dev `APP_IP_HASH_SECRET` (<32 chars), non-Secure cookie,
non-https `APP_BASE_URL`, `EMAIL_PROVIDER` other than `resend`, billing disabled or Stripe test keys, scanner not
`clamav`, **storage not `s3`** (or S3 settings missing / endpoint not https), and a non-local DB without `prod`.
Also in `application-prod.yml`: ECS-format JSON logs on stdout, log level INFO, graceful shutdown (30 s), Hikari pool 10
(`DATABASE_POOL_SIZE`). Session cookie is `__Host-VF_SESSION` (Secure) and HSTS is sent on secure requests (SecurityConfig).
Forwarded headers: `server.forward-headers-strategy=native` + `TRUSTED_PROXIES_REGEX` (default covers 10/8 = Render private network).

### Environment variables
Authoritative list with comments: `.env.example`. Which service gets what: the `envVars` of `render.yaml`. Where each value
comes from: RUNBOOKS.md "Environment variables: where each value comes from".

## Database connection (Supabase)
Use the **Session pooler** string (host `aws-0-eu-central-1.pooler.supabase.com`, port **5432**, user `postgres.<project-ref>`)
because Render cannot use the IPv6-only direct host on all plans. Never the Transaction pooler (port 6543): no session state,
no advisory locks (Flyway). JDBC URL: `jdbc:postgresql://<host>:5432/postgres?sslmode=require`.
Supabase's Data API (REST/GraphQL on `public`) must stay OFF; migration V10 also revokes the API roles' privileges.

## Migration strategy (Flyway) — zero-downtime = expand / contract
- Flyway runs when the API starts. It takes a database lock (advisory lock via the schema history table), so if two
  instances start at once one migrates and the other waits; at MVP there is one instance anyway.
- Render starts the NEW instance, waits until it is healthy, then stops the old one. For a short time old code runs
  against the new schema (and old frontend against the new API). Therefore every migration must be **backward compatible with the
  previous release**:
  1. **Expand** (release N): add nullable columns / new tables / new indexes (`CREATE INDEX CONCURRENTLY` is not possible inside Flyway's transaction; keep tables small or use a separate non-transactional migration), backfill in small batches; code writes both old and new.
  2. **Migrate** (release N+1): code reads the new shape.
  3. **Contract** (release N+2): drop the old column/table/constraint only after N+1 is live and stable.
  Never in one release: rename a column, drop a column still read by the previous release, add NOT NULL without default, change a type.
- Never edit an applied migration (checksum fails and the API will not start). A failed migration leaves the new instance
  unhealthy, so Render keeps the old one serving: fix forward with a new `V{n+1}` file. Do NOT run `flyway repair` blindly in
  production; if a migration half-applied, read RUNBOOKS.md "Incident basics".
- Order of deploy: backend first, then frontend (`deploy` job does this).
- Long migrations (> ~30 s): run as a separate step before the release instead of at startup (future work: Render pre-deploy command running Flyway only).

## Request limits at the ingress (SECURITY.md section 9, M2)
Required by the security design: 16 MB only on `POST /api/v1/vendors/*/documents`, ~1.5 MB (import preview) elsewhere,
body/header timeouts, per-IP connection limits.
**On Render these cannot be configured per route**: Render's edge has its own generic limits and no rule engine. What we
have instead: (1) Next.js `experimental.proxyClientMaxBodySize: 16mb` (global, so a larger body is truncated/rejected at the
frontend; the largest legitimate body is the 15 MB document); (2) the backend's own limits (multipart 16/17 MB, Tomcat
`max-swallow-size`, document 15 MB, per-IP and per-user rate limits, authentication before any body is parsed for
the upload route, CSRF header-only so multipart is not parsed for token-less requests); (3) Render's connection handling for slow
clients. Remaining gap: a non-upload route can receive up to 16 MB (JSON parsing is bounded by Jackson and memory). If it becomes a
problem: put Cloudflare (free plan, WAF rule: body > 1.5 MB and path not the upload route → block) in front of
`vendorflow-web`, or add a small `Content-Length` filter in the API (recommended follow-up, not built).

## Release checklist
- [ ] CI green on main (all jobs, including `docker-build` and `e2e-fullstack`)
- [ ] Migrations reviewed: expand/contract, no destructive change, no edit of applied files
- [ ] New env vars added to `render.yaml` + `.env.example` + RUNBOOKS.md
- [ ] Deploy hooks configured (or Render auto deploy), watch the deploy logs
- [ ] Smoke test: sign up (email arrives), upload a PDF (scanned, stored in Supabase), download it, Stripe test checkout is NOT used in live, webhook delivery 200
- [ ] Rollback plan noted (RUNBOOKS.md "Rollback")
