# ADR-0010: Hosting on Render (Frankfurt) with Supabase (EU) for database and storage

- Status: Accepted · Date: 2026-10-04 (owner chose the stack; this ADR records topology and trade-offs)

## Context
VendorFlow needs a production home for: a Next.js UI, a Spring Boot API, a malware scanner (ClamAV, ~1.3 GB RAM), a
PostgreSQL 17 database and private document storage. The owner has no ops team and wants "create accounts, paste secrets,
click deploy". Customers are US property managers, but the owner is in the EU, so an EU region keeps latency and
support simple (data residency for US customers is not a stated requirement; see Risks).

## Decision
**Render** runs the three containers, **Supabase** provides Postgres and S3-compatible Storage. Everything in the EU
(Render `frankfurt`, Supabase "Central EU (Frankfurt)", `eu-central-1`).

```
browser --https--> vendorflow-web  (Render web service, PUBLIC, Next.js standalone)
                      |  /api/* rewrite + Server Components, Render private network
                      v
                   vendorflow-api  (Render PRIVATE service, Spring Boot, profile prod)
                      |--> vendorflow-clamav (Render PRIVATE service, clamd :3310)
                      |--> Supabase Postgres  (TLS, session-mode pooler / direct, port 5432)
                      '--> Supabase Storage   (S3 API, private bucket, path-style)
```

### Topology: same origin, backend private
Render offers private services (`type: pserv`): reachable only by other services of the same workspace and region, by
hostname (`vendorflow-api:8080`), with no public URL. So the ADR-0003 design is kept as is: the browser talks only to
`vendorflow-web`; Next.js rewrites `/api/*` and Server Components call the API over the private network. Why not a public API:
no CORS, first-party `SameSite=Lax` `__Host-` cookie, the API cannot be hit directly (so no way around the rate limiter's
trusted-proxy assumption, SECURITY.md section 9), and a smaller attack surface (no second public hostname to protect).
Stripe webhooks arrive at `https://<app>/api/v1/webhooks/stripe` and pass through the same rewrite.
Cost of this choice: uploads stream through Next (OK at 15 MB, ADR-0003) and the Next rewrite target is baked at build time
(Dockerfile `BACKEND_URL` build arg).

### Other decisions
- **Storage**: `S3ObjectStorage` (AWS SDK v2, ADR-0007) against Supabase's S3 endpoint; private bucket; the service-role-style
  S3 access key lives only in the API's environment. Provider switch `STORAGE_PROVIDER=filesystem|s3`; the `prod` profile
  refuses `filesystem` (Render disks are ephemeral) unless explicitly opted in (`ProfileGuard`).
- **Database**: Supabase Postgres 17 via the **session-mode pooler** (IPv4, port 5432) or direct connection. Not the
  transaction pooler (port 6543): Flyway and Hibernate need session state and advisory locks. The Supabase Data API is
  switched off and migration V10 revokes the `anon`/`authenticated`/`service_role` grants as a second lock.
- **Migrations**: Flyway at API startup (a Postgres advisory lock serialises concurrent instances). Expand/contract
  discipline for zero downtime (DEPLOYMENT.md).
- **Deploys**: `render.yaml` Blueprint with `autoDeployTrigger: off`; GitHub Actions calls the Render deploy hooks only
  after every CI job passes, backend first.
- **Malware scanning**: ClamAV official image as a private service with a 2 GB disk for signatures.
- **No new observability vendor** (no Sentry/APM): JSON logs to Render's log stream + log-based alerts (RUNBOOKS.md). Revisit
  with an ADR when volume justifies it.
- **New dependency**: `software.amazon.awssdk:s3` (+ `url-connection-client`; the default Netty and Apache 5 HTTP clients are excluded: Apache HttpClient 5 on the classpath silently changed the HTTP client Spring picks for `RestClient` and made the real-server tests hang), Apache-2.0, maintained by
  AWS, BOM-managed, in the OSV scan. Needed because ADR-0007 requires a real S3 client; the simpler alternative (hand-signed
  SigV4 over HttpClient) is security-sensitive code we should not own.
  Test-only: a LocalStack container (S3 only, pinned 3.8, no extra Maven dependency) for real-protocol tests. MinIO was tried first, but its images are no longer pullable from Docker Hub/Quay.

## Costs (ballpark, USD/month, Oct 2026 list prices; verify before committing)
| Item | Plan | ~Cost |
|---|---|---|
| vendorflow-web | Render Starter (512 MB) | 7 |
| vendorflow-api | Render Standard (2 GB) | 25 |
| vendorflow-clamav (+2 GB disk) | Render Standard (2 GB) | 25 + 0.5 |
| Supabase | Pro (daily backups, 8 GB DB, 100 GB storage) | 25 |
| Resend | Free (3k emails/mo) | 0 |
| **Total** | | **~83** |

Cheaper start: API on Starter (watch for OOM kills) and Supabase Free (no daily backups: NOT recommended for customer data) gives ~40. Stripe fees are per transaction.

## Trade-offs
+ Few moving parts, one dashboard per vendor, Blueprint = infrastructure as code, cheap enough for a first customer.
+ Backend not internet-reachable.
- Render has no per-route request-size rules and no WAF at the edge on standard plans (see SECURITY.md "ingress body caps": mitigated by Next's 16 MB limit, backend limits and rate limits; revisit with Cloudflare in front if abuse appears).
- Single region, single instance per service: a deploy of the API means a short gap (Render keeps the old instance until the new one is healthy, but there is no HA across failures). Acceptable for MVP; scale by `numInstances` (rate limits are per instance, SECURITY.md).
- Two vendors to trust for data. Supabase Storage S3 is a younger S3 implementation; we only use PUT/GET/HEAD/DELETE.
- Client IP behind Render's edge + Next hop must be verified after the first deploy (RUNBOOKS.md "Check the client IP").

## Exit plan
Everything is plain Docker + Postgres + S3: the same images run on Fly.io, Railway, AWS ECS or a VPS with
docker compose. DB: `pg_dump`/restore (`scripts/backup-db.sh`) to any Postgres 17. Storage: copy the bucket with any S3 tool
(`rclone`/`aws s3 sync`) and change `STORAGE_S3_*`. Nothing uses a Supabase- or Render-specific API (V10 is a no-op elsewhere).

## Alternatives rejected
- Vercel for Next.js: great, but a second vendor and the API would have to be public (Vercel cannot reach a private network) or tunnelled.
- Fly.io/Railway: fine technically; owner picked Render (simple UI, Blueprints, private services).
- Public API + CORS: rejected in ADR-0003.
- Self-hosted Postgres/MinIO: operations burden without a team.
