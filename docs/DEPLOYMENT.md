# VendorFlow — Deployment

Status: **not deployed**. Production design happens in Phase 11 (hosting ADR pending, see DECISIONS.md).

## Target shape (planned)
- **Database**: Supabase Postgres (managed backups, PITR on paid tier). Flyway migrations run by the backend at
  startup in the first iteration; move to a dedicated pipeline step if migrations get long.
- **Storage**: Supabase Storage private bucket via S3-compatible API.
- **Backend**: Docker image (Temurin 21 JRE, non-root user), health via `/actuator/health/{liveness,readiness}`.
- **Frontend**: Next.js (`output: standalone`) container or Vercel; `BACKEND_URL` points to the private API.
- **Email**: Resend with a verified sending domain (SPF/DKIM/DMARC).
- **Billing**: Stripe live mode keys; webhook endpoint `https://<app>/api/v1/webhooks/stripe`.

## Security requirements for the ingress
The application cannot protect itself from slow or oversized requests before it has accepted them. The reverse proxy / CDN / load balancer in front of the API (and of the Next.js server, whose `proxyClientMaxBodySize` is a single global limit for all rewritten routes) MUST enforce:
- **Body size per route**: 16 MB ONLY on `POST /api/v1/vendors/*/documents` (file limit 15 MB + multipart overhead; app limits are 16 MB/part, 17 MB/request); about 1 MB on every other route.
- **Timeouts**: request header and request body read timeouts (slow-loris / slow-body defence), and an overall upstream timeout.
- **Connections**: per-IP limits on concurrent connections and on new connections per second.
- **Client IP**: append the real client address to `X-Forwarded-For` and keep the API reachable only through the proxy (SECURITY.md section 9).
- **Malware scanning**: configure a real `FileScanner` before production; the app logs a WARN at startup in the `prod` profile if the no-op scanner is active.

## Required environment variables
See `.env.example` (root) for the authoritative list. Secrets are injected by the hosting platform; never committed.

## Release checklist (to be completed in Phase 11)
- [ ] CI green on main
- [ ] Migrations reviewed (no destructive change without expand/contract)
- [ ] Secrets present in target environment
- [ ] Smoke test: signup, upload, download, webhook
- [ ] Rollback plan noted
