# VendorFlow — Security & Privacy

> Security is a product requirement. Read this before touching auth, authorization, uploads, webhooks, or logging.

## 1. Threat model (summary)

**Assets**: vendor compliance documents (may contain policy numbers, addresses, TINs on W-9s), vendor contact data,
user accounts, org membership/roles, billing state.

**Actors**: anonymous internet user; authenticated user of org A attacking org B; low-privilege member/viewer
escalating inside their org; malicious file uploader; forged webhook sender; compromised dependency.

| Threat | Primary controls |
|---|---|
| Cross-tenant access (IDOR/BOLA) | Org from server session, membership re-verified per request, org-scoped repositories, composite FKs, 404 on foreign ids, cross-tenant tests per endpoint |
| Privilege escalation | Permission checks in application services (not only `@PreAuthorize` on controllers); OWNER role cannot be granted by ADMIN; last-owner invariant; tests per role |
| Mass assignment | Request DTOs are explicit records; never bind to entities; `organizationId`/`role`/`status` fields are not accepted where not intended |
| Account takeover / brute force | bcrypt (cost 12) via DelegatingPasswordEncoder; per-account lockout (5 failures → 15 min, decided atomically in the database after bcrypt); per-IP rate limit on auth endpoints (matched on the decoded, normalized path); generic login error; session id rotated on login |
| Session theft | `HttpOnly; Secure; SameSite=Lax` cookie; server-side sessions (revocable); idle timeout 8h (sliding) + absolute lifetime 7 days since login (`SessionLifetimeFilter`, `app.security.session-absolute-timeout`); logout invalidates server session |
| CSRF | Spring Security CSRF with cookie token repository + `X-XSRF-TOKEN` header for all unsafe methods (webhooks exempt; they use signatures) |
| XSS | React escaping; no `dangerouslySetInnerHTML`; strict CSP (Phase 9); user filenames rendered as text only; email templates escape all values |
| SQL injection | JPA parameter binding only; no string-concatenated queries; sort fields allowlisted |
| Malicious upload | Size cap (default 15 MB); extension allowlist (pdf, png, jpg, jpeg); magic-byte detection; client Content-Type ignored; server-generated storage keys; private storage; downloaded as `attachment` with `nosniff`; files never rendered inline from our origin; malware-scan hook point (`FileScanner`, no-op in MVP) |
| Path traversal | Storage keys are `org/{uuid}/doc/{uuid}` built by server; filesystem storage resolves + normalizes and asserts the path stays under root |
| Malicious PDFs | We never parse/render PDFs server-side in the MVP (only magic-byte check). Browser downloads as attachment. Future AI extraction runs in an isolated worker (Phase 13) |
| SSRF | Backend makes outbound calls only to fixed configured hosts (Stripe, Resend, storage). No user-supplied URLs are fetched |
| Webhook forgery / replay | Stripe signature verification with tolerance window (SDK `Webhook.constructEvent`); event id stored (PK) → replays are no-ops; state changes derive from re-fetched Stripe objects where ordering matters |
| Webhook endpoint flooding (L1) | `/api/v1/webhooks/stripe` is unauthenticated and rate-limited per IP (`stripe-webhook`, 600/min) with a 256 KiB body cap. Stripe sends from a small IP set, so a flood from those IPs could consume the budget and delay legitimate deliveries; Stripe retries with backoff, so this degrades timeliness, not correctness. Rejections log one WARN with the reason class only (signature mismatch / stale timestamp / malformed). Prod startup refuses disabled billing and non-live Stripe keys without explicit opt-in (`allow-disabled-in-prod`, `allow-test-keys-in-prod`) |
| Error disclosure | RFC 9457 ProblemDetail with generic messages; stack traces only in server logs; `server.error.include-*=never` |
| Insecure CORS | Production: no CORS (same-origin via Next.js rewrite). Dev: explicit allowlist `http://localhost:3000` only |
| Secrets leakage | Secrets only via environment variables; `.env*` gitignored; `.env.example` has placeholders; logs never include secrets/tokens/passwords/file contents |
| Enumeration | UUID ids; login and password-reset responses do not reveal whether an email exists. **Signup does** (409 "email already registered"): accepted trade-off for a usable signup form, bounded by the per-IP signup rate limit (5/min) |
| Denial of wallet / abuse | Rate limits on auth, upload, invitation and email-triggering endpoints; pagination caps; max 3 verification/reset emails per recipient address per hour (`OutboxService`, excess silently not enqueued) |
| Client-IP spoofing | `server.forward-headers-strategy=native` (Tomcat RemoteIpValve): X-Forwarded-For honoured only from trusted proxies, rightmost untrusted entry wins (section 9) |
| Fail-open misconfiguration | `app.security.session-cookie-secure` defaults to true; `ProfileGuard` refuses startup for weak prod settings and for a non-local database without the `prod` profile (section 7) |
| Text/header injection via names | `@PlainText` rejects control (Cc) and format (Cf, bidi overrides) characters in every human-entered name; email templates still escape/one-line values |

## 2. Authentication (ADR-0002)
- Email + password, Spring Security, server-side sessions stored in Postgres (Spring Session JDBC).
- Password policy (implemented, `PasswordPolicy`): 12-128 characters (not bytes; no 72-byte cap since Argon2id), not equal to the email, not in `security/common-passwords.txt` (top-100k list loaded into a HashSet, case-insensitive, see DECISIONS.md); no composition rules (NIST 800-63B). Change: `POST /me/password` (current password verified, other sessions revoked, current rotated, audit + email).
- Hashing (implemented): `DelegatingPasswordEncoder` default `{argon2}` = Argon2id (m=19 MiB, t=2, p=1, `app.security.argon2-*`; BouncyCastle `bcprov`), legacy `{bcrypt}` (cost 12) still verifies and is re-hashed to Argon2id on the next successful login. Test profile lowers the costs for speed only. Unknown emails are checked against a dummy hash so login timing does not reveal whether an account exists.
- Email verification required before inviting others or receiving reminders (login allowed; banner shown).
- Password reset: single-use token (hash stored), 30-minute expiry; all sessions of the user invalidated on reset.
- Lockout details: the stale pre-bcrypt user read is NOT used to decide; after bcrypt the failure update (increment, lock at 5) and the success update (reset) each run as one SQL statement that only matches an account that is not locked right now, and a login whose success update changes 0 rows is rejected. Parallel guesses therefore count at most 5, and a correct guess in flight while the lock engages is refused.
- Login (implemented): generic "Invalid email or password" for unknown email, wrong password and locked account (identical body). Lockout: 5 consecutive failures set `locked_until = now + 15 min` (atomic SQL update; counter is kept so one more failure after expiry re-locks; success resets it); failures while locked are not counted. Per-IP limiter (`shared.ratelimit`, fixed window, fires before CSRF/auth); in-process (documented
  limitation: per-instance; acceptable until >1 instance — then move counters to Postgres).

## 3. Authorization
Roles and permissions (`RolePermissions`):

| Permission | OWNER | ADMIN | MEMBER | VIEWER |
|---|:-:|:-:|:-:|:-:|
| View vendors/documents/dashboard | ✓ | ✓ | ✓ | ✓ |
| Download documents | ✓ | ✓ | ✓ | ✓ |
| Create/edit vendors, upload documents, edit doc metadata | ✓ | ✓ | ✓ | |
| Review (approve/reject) documents | ✓ | ✓ | ✓ | |
| Archive vendors/documents, CSV import | ✓ | ✓ | | |
| Manage requirements & document types | ✓ | ✓ | | |
| Invite users / change roles (not OWNER) / remove members | ✓ | ✓ | | |
| Organization settings (reminders, time zone) | ✓ | ✓ | | |
| Billing, grant/revoke OWNER, delete organization | ✓ | | | |

Enforcement happens in application services via `AuthorizationService.require(Permission)`. Controllers may add
`@PreAuthorize` as a second line, never as the only one.

## 4. Tenant isolation checklist (apply to every new endpoint)
- [ ] Organization id comes from `TenantContext`, never from path/body/query.
- [ ] Every repository call includes `organizationId`.
- [ ] Child resources are loaded through their parent within the same org (e.g. document → vendor → org).
- [ ] Foreign/nonexistent ids return 404 identically.
- [ ] Cross-tenant integration test exists.
- [ ] Role test exists for the lowest role that must be denied.

## 5. Privacy — data inventory

| Data | Why | Who can access | Retention | Logged? |
|---|---|---|---|---|
| User email, name | Login, notifications | The user; members of shared orgs (name+email) | Until account deletion | Email domain only + user id |
| Password hash | Authentication | Nobody (never returned) | Until changed/deleted | Never |
| Vendor contact data | Core product | Org members | Until vendor deleted / org deleted + 30 days | Ids only |
| Uploaded documents | Core product | Org members (authorized download) | Until archived+purged / org deleted + 30 days | Ids, size, mime, sha256 — never contents or original filenames at INFO |
| Audit events | Traceability | Owners/Admins (Phase 9 UI) | 2 years | n/a |
| IP addresses | Abuse prevention | Not stored raw; `ip_hash` (HMAC with server secret) in audit events | With audit event | Raw IP may appear in infra access logs (platform-controlled) |
| Stripe ids | Billing | Owners | Life of subscription + legal retention | Event id + type |
| Card data | — | **Never stored or seen** (Stripe Checkout / Portal) | — | — |

## 6. Logging rules
- Structured JSON logs in prod (Spring Boot structured logging, ECS format); `requestId`, `userId`, `orgId` in MDC.
- **Never log**: passwords, password hashes, session ids, CSRF tokens, invitation/reset/verification tokens, API keys,
  Stripe signatures, webhook raw bodies, document contents, full request bodies.
- **Email delivery** (`ResendEmailSender`, `LoggingEmailSender`): logs only notification id, kind, status and (logging sender) the recipient domain. Never the API key, request/response bodies, subject, text/html (they hold links and names) or full addresses. Exceptions from the Resend client carry only `HTTP <status> <allowlisted error name>`; the dispatcher additionally redacts addresses and token-like runs before storing `last_error`. `EmailMessage.toString()` prints only the id. `GET /notifications` never returns payloads.
- Log: auth success/failure (user id or email domain), authorization denials (permission, user, org), document
  operations (ids), reminder/outbox transitions, Stripe event ids/types, application errors with stack traces.

## 7. Secrets and startup safety
- `ProfileGuard` (fails the boot): `e2e`+`prod`; with `prod`: dev or short (<32) ip-hash secret, `session-cookie-secure=false`, non-https `app.base-url`, **`app.email.provider` other than `resend`** (EMAIL_PROVIDER unset = logging = a production app that silently sends nothing; the sender itself also refuses to start without `RESEND_API_KEY` / `EMAIL_FROM`); WITHOUT `prod`: a database host that is not localhost/127.0.0.1/[::1]/host.docker.internal unless `app.allow-non-local-db-without-prod=true` (a forgotten `SPRING_PROFILES_ACTIVE=prod` must not run production data with local settings; `application-test.yml` sets the opt-in because Testcontainers may use a remote Docker host).
- Supplied via environment variables (see `.env.example`). Local dev uses `.env` files that are gitignored.
- Required in prod: `DATABASE_URL/USER/PASSWORD`, `APP_BASE_URL` (https), optional `TRUSTED_PROXIES_REGEX`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_ID` (all three required while `BILLING_ENABLED` is true, which is the prod default; `ProfileGuard` refuses to boot otherwise), `RESEND_API_KEY`,
  `STORAGE_S3_*`, `APP_IP_HASH_SECRET` (>= 32 chars, not a dev value). Rotation procedure: `docs/runbooks/secret-rotation.md` (Phase 11).

## 8. Residual risks (accepted)
- **Timing of the DB write (L3)**: a failed login for a known email performs a counter UPDATE that an unknown email does not, so response time can differ by a few milliseconds; the bcrypt verification (the dominant cost) is equalized. Not fixed.
- **Signup reveals existing emails** (409), see the Enumeration row.
- In-process rate limiter (per instance); see section 2.
- The 3-per-hour account-email throttle is check-then-insert without a lock, so concurrent requests can overshoot slightly; acceptable for abuse damping.

## 9. Deployment requirement: client IP and trusted proxies
Rate limiting and the audit `ip_hash` use `request.getRemoteAddr()`. The app runs with `server.forward-headers-strategy: native` in ALL profiles: Tomcat's RemoteIpValve walks `X-Forwarded-For` from right to left and only believes an entry when the peer that sent it is a trusted proxy; the first untrusted address becomes the client IP. (`framework`/ForwardedHeaderFilter was rejected: it trusts the client-controlled leftmost entry, so one header value per request defeated every limit.)
- Trusted proxies = `server.tomcat.remoteip.internal-proxies`, env `TRUSTED_PROXIES_REGEX` (regex on the peer IP). Default: loopback + private ranges (10/8, 172.16/12, 192.168/16, 169.254/16, 127/8, ::1).
- The Next.js server (rewrite/proxy to the API) and any edge/CDN in front **must append the real client IP** to `X-Forwarded-For` (the peer address it sees, added to the right of whatever the browser sent), and the API port must be **reachable only through trusted proxies** (private network / firewall). If the API is reachable directly from the internet, set `TRUSTED_PROXIES_REGEX` to the exact proxy addresses; peers outside it cannot influence their IP.
- If the real proxy is not in the trusted list, every user shares the proxy's IP and one noisy client rate-limits everybody: check this at deployment.
- Tests: `ClientIpTrustedProxyTest`, `ClientIpUntrustedPeerTest` (real Tomcat).

## 10. Reporting
Security issues: contact the owner privately; do not open public issues.

## Upload controls (implemented, Phase 3)
Order: authz -> vendor in tenant (404) -> non-empty -> size (declared + streamed byte count, 15 MB) -> extension allowlist on the sanitized name -> magic bytes (client Content-Type ignored, mime detected) -> active org type -> dates -> `FileScanner` hook (422) -> streamed write (SHA-256 computed on the way) -> one DB transaction (vendor row lock, supersede, audit); object deleted if the transaction fails. Storage keys are `org/{uuid}/doc/{uuid}` validated against `[a-z0-9-]` segments and re-checked against the root after normalize. Filenames are display-only (last segment, control/bidi stripped, <=255). Downloads: authorize -> audit -> stream with attachment, nosniff, `Cache-Control: private, no-store`, `CSP: sandbox`, RFC 5987 filename. Uploads rate-limited 30/min/IP and 30/min/user; per-organization storage quota. Done since: ClamAV scanner (Phase 10) and S3 storage (`S3ObjectStorage`, ADR-0010).

### Upload hardening (Phase 3 security batch)
- **Storage quota (M1)**: `app.documents.org-quota` (default 5 GB; 40 MB in the test profile) caps the SUM of `size_bytes` of ALL documents of an organization in every state (superseded/archived files are still stored). Exceeding it gives 413 problem `title: "Storage quota exceeded"`, type `.../storage-quota-exceeded` (distinct from `file-too-large`), nothing stored, no temp file left. Two checks: a fast pre-check with the declared size before the scan/store, and the AUTHORITATIVE check in the DB transaction (after the vendor row lock) with the streamed byte count; if it fails the transaction rolls back and the stored object is deleted. The quota is NOT serialized per organization (the lock is per vendor), so concurrent uploads to different vendors of one org can overshoot by at most (concurrent uploads x 15 MB); accepted and bounded by the per-user/per-IP limits.
- **Per-user upload limit (L2)**: in addition to 30/min per IP (RateLimitFilter), `DocumentUploadService` applies 30/min per authenticated user id (rule `document-upload-user`, same `RateLimiter`, in-process like the others). 429 + `Retry-After`. Users behind one NAT do not share a budget; one user rotating IPs is still limited.
- **CSRF header only (L1)**: `HeaderOnlyCsrfTokenRequestHandler` resolves the token ONLY from `X-XSRF-TOKEN`. The default handler also reads the `_csrf` parameter, which forces the container to parse (and spool to disk) a multipart body before the CSRF check could fail. Test: `CsrfHeaderOnlyRealServerTest` (real Tomcat; spool directory watched; token-less upload, also with `_csrf` as form field, is 403 and spools nothing).
- **Download auditing (L3)**: only `GET /documents/{id}/download` is served and audited. `HEAD` (which Spring would otherwise route to the GET handler) returns 405 `Allow: GET` before any service call, so no `document.downloaded` row exists without a served file.
- **Startup warning**: with the `prod` profile and the no-op `FileScanner`, one WARN "No malware scanner configured" is logged at startup (startup is not blocked).

### Deployment requirement (M2): the ingress must cap request bodies
The application limits (15 MB file / 16 MB part / 17 MB request) apply only AFTER the server accepted the connection and started reading. Put the limits in front (details in DEPLOYMENT.md "Security requirements for the ingress"): 16 MB body ONLY on `POST /api/v1/vendors/*/documents`, about 1 MB on every other route, request/body read timeouts, per-IP connection limits. Next.js `proxyClientMaxBodySize` is global to all rewritten routes, so it cannot express the per-route rule: enforce it at the ingress/CDN.

### Residual risks (Phase 3)
- **R1**: magic-byte checks are shallow (a file can start with `%PDF-` and still be hostile or malformed). A real malware scanner (ClamAV or equivalent via the `FileScanner` hook) is REQUIRED before accepting real customer uploads in production.
- **R2**: see M2 above (ingress body caps are a deployment duty).
- **R3**: no orphan sweeper yet: if an object is stored and both the DB transaction and the best-effort delete fail, an orphan file remains. A periodic reconciliation job is future work.
- **R4**: no retention/deletion policy for documents (superseded/archived files and their quota usage grow until one exists).
- **R5**: the uploader may approve their own document (review needs only `DOCUMENTS_REVIEW`; no four-eyes rule). Product decision pending.

### Defined behaviors
- Uploads to INACTIVE vendors are allowed (keeps records complete).
- Changing a document type's `hasExpiration` keeps existing document dates. Phase 4 compliance rules decide: types without expiration ignore dates; types with expiration and a null date become REVIEW_REQUIRED.

## CSV import/export controls (implemented, Phase 7)
- **CSV / formula injection (export)**: every text cell starting with `=`, `+`, `-`, `@`, TAB or CR is written with a leading `'` (one place: `VendorCsv.protect`). Vendor names and notes are
  user-entered, so an exported file opened in Excel/Sheets must never execute them. The import strips that prefix again (only `'` + trigger char) so exports round-trip. The
  `Content-Type` is `text/csv` with `nosniff`, served as an attachment with a fixed server-built file name, `Cache-Control: no-store`.
- **Parsing limits (import)**: 1 MB file (declared size AND real length), 2,000 data rows, `.csv` extension on the sanitized name, strict UTF-8 (`CharsetDecoder` with REPORT, no replacement
  characters), RFC 4180 parsing by Apache Commons CSV (no custom parser), header checks (required/duplicate/unknown, max 30 columns), 10 previews/min per user. Parse errors never echo file
  content; no stack traces. Cell values go through the same Bean Validation + `@PlainText` rules as the vendor API (control and bidi/zero-width characters rejected).
- **No ids from the client in the file**: a CSV cannot name vendor ids; matching is by company name inside the caller's organization only (`VendorRepository.findByLowerNames` takes the organization id),
  so a file can never touch another organization's vendors. Import ids of other organizations are 404.
- **Stored previews / import data retention**: contain vendor contact data; short-lived (1 h), the rows are **cleared (empty array, summary kept) as soon as the commit succeeds**, uncommitted previews are purged 24 h after expiry. Commit re-validates every stored row with the vendor rules (422 if any fails), locks the import row (no double apply) and row-locks the matched vendors ordered by id (no lost update against a concurrent edit; a changed outcome is a 409).
- **Intended behaviour (L4)**: any OWNER/ADMIN of the organization may commit a colleague's preview (both hold `ARCHIVE_AND_IMPORT`; the audit event records the committing user).
- **Hostile-file limits**: header at most 30 columns (else 400 `Invalid CSV file`); at most 20 "Unknown column" errors plus one summary; echoed column names cut to 50 characters; cells over 10,000 characters are a row error and never stored; ERROR rows are stored truncated to their column maximum, so stored size and response size stay bounded whatever the file contains.
- **Export rate limit**: `vendor-export-user`, 10 per 10 minutes per user, applied before the query (429 + `Retry-After`). `RateLimitProperties.windows` gives a rule its own window (default for others stays 1 minute).
- **Export scope**: filters are the list endpoint's, tenant-scoped in SQL; cap 10,000 rows; audited (`vendor.exported`: filters + count only).
- **Residual**: the per-user preview limit is in-process like the other limits; the ingress should cap bodies on `/api/v1/vendors/import/preview` to ~1.5 MB (DEPLOYMENT.md). The app-side container
  limit for multipart is still 16 MB (our own 1 MB check runs after the container has accepted the body).

## Frontend hardening (Phase 9)
- **CSP** (`frontend/src/lib/security/headers.ts`, attached per request in `src/proxy.ts`): `default-src 'self'`; `script-src 'self' 'nonce-…' 'strict-dynamic'` (+ `'unsafe-eval'` and `ws:` only when `NODE_ENV!=='production'`); `style-src 'self' 'nonce-…'` plus two sha256 hashes for sonner's runtime-injected stylesheet (sonner cannot take a nonce; **the hash changes when sonner is upgraded**, `e2e/csp.spec.ts` fails and prints the new one); `style-src-attr 'unsafe-inline'` (style attributes from Radix/sonner positioning cannot carry a nonce and cannot load resources or run script); `img-src 'self' data: blob:`; `connect-src 'self'`; `frame-ancestors 'none'`; `form-action 'self'` + Stripe checkout/billing; `base-uri 'self'`; `object-src 'none'`; `upgrade-insecure-requests` in prod. Because nonces need per-request rendering, the root layout calls `connection()`: every page is dynamic (no static prerender, no PPR).
- Zod is set to `jitless` (`components/zod-csp-config.tsx`) so its `new Function` probe does not trip the no-eval policy.
- Other headers (next.config.ts): nosniff, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, Permissions-Policy (camera/mic/geolocation off), HSTS (2 years, includeSubDomains) in production only; `X-Powered-By` removed.
- **Bundle secrets**: `npm run check:bundle-secrets` (CI, after build) scans `.next/static` for Stripe/Resend key patterns and server-only env var names.
- **Dependencies**: `npm audit --omit=dev` = 0 vulnerabilities on 2026-10-04; CI fails on high/critical (`--audit-level=high`).
- **Review**: no `dangerouslySetInnerHTML`; the only `window.location.assign` is the Stripe redirect guarded by `isAllowedStripeUrl`; no `target="_blank"` links; remaining `href`s are internal paths, `mailto:`/`tel:` (digits sanitised) and API download URLs built from ids.
- The ingress/CDN must not strip or override `Content-Security-Policy` and should not cache HTML (nonce per response).

## Backend hardening (Phase 9) — ASVS L1 controls and where they are tested
Everything below is enforced by the build (`./mvnw verify`); a regression fails a test, not a review.

| ASVS area | Control | Enforced in | Test |
|---|---|---|---|
| V1.1 architecture | Repositories are private to their feature; controllers/api never touch repositories; entities never appear in `api` fields, return types or controller signatures (only the static `from(entity)` DTO mappers may read one); `shared` depends on no feature; `audit` depends only on `shared`; no NEW feature cycle | package layout, `shared.tenant.TenantContext/Role` | `architecture/ArchitectureTest` (ArchUnit, ~4 s, no Spring context) |
| V4.1 / V4.2 access control, tenant isolation | Every method declared on a repository takes `organizationId`, except an explicit, justified allowlist (global tables: `app_user`, `user_token`, `organization`; token-capability lookups; system jobs). Inherited `findById/findAll/deleteById/...` are banned on tenant repositories. The allowlist fails on stale entries | `ArchitectureTest.UNSCOPED_REPOSITORY_METHODS` | `ArchitectureTest` (2 tests) |
| V4.1.1 deny by default | Every `RequestMappingHandlerMapping` endpoint is either on the public allowlist (10 entries) or answers 401 anonymously; unmapped paths and actuator endpoints other than `health` answer 401 | `SecurityConfig` (`anyRequest().authenticated()`) | `shared/EndpointInventoryTest` |
| V4.2.2 / V13.2 CSRF | Every mutating endpoint rejects a missing or mismatching `X-XSRF-TOKEN` with 403, public ones included; the only exemption is the signed Stripe webhook | `SecurityConfig` | `EndpointInventoryTest`, `CsrfHeaderOnlyRealServerTest` |
| V14.4 / V14.5 HTTP security headers | On every API response incl. 400/401/403/404/429: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Permissions-Policy` (camera, microphone, geolocation, payment, usb, ... off), `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (+ Pragma/Expires). `Strict-Transport-Security: max-age=31536000 ; includeSubDomains` on secure requests only (TLS or `X-Forwarded-Proto` from a trusted proxy; plain-http local dev is unaffected). No `Server`/`X-Powered-By`. 429 responses are written before the security chain, so `ProblemJsonWriter` sets nosniff + no-store itself. Document downloads keep their own stricter `CSP: sandbox` and `Cache-Control: private, no-store`. Page-level CSP/HSTS for the HTML app is the frontend's (section "Frontend hardening") | `SecurityConfig.headers(...)`, `ProblemJsonWriter` | `shared/SecurityHeadersTest`, `shared/SecurityHeadersRealServerTest` (real Tomcat: Server/X-Powered-By absent, HSTS follows X-Forwarded-Proto, CSRF cookie `SameSite=Lax`), `DocumentActionsTest` (sandbox CSP kept) |
| V2.2 / V11.1 anti-automation | Per-IP fixed-window rules: `login` 10, `signup` 5, `password-reset-request` 5, `invitation` 20, `resend-verification` 3, **`token-redemption` 20 (new: `verify-email` + `password-reset/confirm`)**, `document-upload` 30, `stripe-webhook` 600 /min; per-user: `document-upload-user` 30, `document-request-user` 30, `vendor-import-preview-user` 10 /min, `vendor-export-user` 10 per 10 min. All answer 429 problem+json with `Retry-After` and `requestId` | `RateLimitFilter`, `RateLimiter`, services for the per-user rules | `shared/RateLimitRulesTest` (`COVERAGE` map: a rule without a test fails `everyConfiguredRuleHasATest`), plus the detailed tests it points to |
| V3.2 session binding | New session id on every login (old row deleted), a session id planted by an attacker before login never authenticates, no session is created for anonymous requests | `AuthService`, Spring Session JDBC | `AuthFlowTest.loginReturnsMeAndRotatesSessionId`, `SessionCookieTest.aSessionIdPlantedBeforeLoginNeverBecomesTheAuthenticatedSession`, `loginInvalidatesTheServerSideRowOfTheSessionItReplaces`, `SecurityAndRateLimitTest.sessionCookieIsNotSetOnAnonymousRequests` |
| V3.3 session termination | Logout deletes the `spring_session` row (not only the cookie) and expires the cookie with the same attributes; idle timeout 8 h sliding (`max_inactive_interval = 28800`); absolute 7 days; password reset invalidates all sessions | `AuthService`, `SessionLifetimeFilter` | `SessionCookieTest`, `AuthFlowTest.logoutInvalidatesSession`, `SessionLifetimeTest`, `PasswordResetTest` |
| V3.4 cookie flags | `__Host-VF_SESSION` when Secure (prod; `VF_SESSION` on plain http local/test):  `Secure` (default true; `false` only in local/e2e/test profiles, `ProfileGuard` refuses it with `prod`), `HttpOnly`, `SameSite=Lax`, `Path=/`, host-only (no `Domain`), non-persistent. `XSRF-TOKEN`: script-readable by design, `SameSite=Lax`, `Secure` on https | `SessionCookieConfig`, `SecurityConfig.csrfTokenRepository` | `SessionCookieTest` (run with `session-cookie-secure=true`; also reads the yml defaults of main/prod/local/e2e), `SecurityHeadersRealServerTest` |
| V2.1 passwords | Argon2id default, bcrypt verifies and is upgraded on login, 12-128 chars (non-ASCII ok), top-100k common-password list, change-password endpoint (current password required, other sessions revoked, audit, notice email also after reset), per-user rate limit `password-change` 5/min | `SecurityConfig.passwordEncoder`, `PasswordPolicy`, `PasswordVerifier`, `PasswordChangeService` | `AuthFlowTest` (Argon2 prefix, 64+ char non-ASCII, 129 rejected, bcrypt upgrade), `PasswordPolicyTest`, `PasswordChangeTest`, `PasswordResetTest`, `RateLimitRulesTest` |
| V12.4 malware scan | `FileScanner` clamd INSTREAM client (`vendorflow.storage.scanner=clamav`), fail closed: scanner down = 503 "File scanning unavailable", infected = 422 "File rejected" (virus name only in server log); `ProfileGuard` refuses `noop` in prod unless `vendorflow.storage.allow-noop-scanner-in-prod=true` | `ClamAvFileScanner`, `DocumentUploadService`, `ProfileGuard.checkScanner` | `ClamAvFileScannerTest` (fake clamd), `DocumentScannerFailClosedTest`, `ProfileGuardTest` |
| V14.2 dependencies | Runtime dependency set scanned against OSV (key-less) on every PR touching the build and weekly; opt-in OWASP dependency-check profile (see below) | `backend/scripts/osv-scan.mjs`, `.github/workflows/security-scan.yml`, pom profile `security-scan` | CI job `osv` |

### Public (unauthenticated) endpoint allowlist
`GET /auth/csrf`, `POST /auth/{signup,login,logout,verify-email,password-reset/request,password-reset/confirm}`, `POST /invitations/{lookup,accept}`, `POST /webhooks/stripe` (signature), `GET /actuator/health[/**]` (not an MVC endpoint). Adding one means editing `SecurityConfig` AND `EndpointInventoryTest.PUBLIC` in the same change.

### Database round trips per authenticated GET (measured, `DbRoundTripsTest`)
Statements executed on the real Postgres (JDK proxy around the DataSource, so Spring Session's JdbcTemplate writes count too; warm request, 2026-10-04):

| Endpoint | Statements | Breakdown |
|---|---|---|
| any anonymous request (`/auth/csrf`, a 401) | 0 | no session row is created, no query |
| `GET /organization` | 4 | session read, membership/role check, organization row, session touch |
| `GET /dashboard/summary` | 5 | session read, membership, organization (timezone/window), 1 aggregate query, session touch |
| `GET /me` | 5 | session read, membership, user, all memberships of the user, session touch |
| `GET /vendors` | 6 | session read, membership, organization, page query + count query, session touch |

Fixed overhead of ANY authenticated request is **3** (session read, membership/role re-check by `TenantContextFilter`, session `LAST_ACCESS_TIME` update), i.e. not above the "more than 3" trigger, so nothing was changed: the membership check must not be cached across requests (role changes and removals apply immediately, ADR-0004) and the session touch is what implements the sliding idle timeout. The flush mode already is `ON_SAVE` (Spring Session default): one UPDATE per request, no write when the session is unchanged beyond the touch. Ratchet: `DbRoundTripsTest` fails if an endpoint exceeds the numbers above, if a request issues more than one session read+write pair, or if anonymous traffic touches the database. Possible later savings (not worth the risk now): skip the touch when the last one is under a minute old (needs a custom `SessionRepository` wrapper), reuse the filter's membership row in `MeService`.

### Dependency scanning baseline (2026-10-04)
- Method: runtime classpath of `backend` (110 artifacts) resolved with Maven and queried against OSV (GitHub advisories + NVD + ecosystem feeds). Reproduce: `cd backend && node scripts/osv-scan.mjs` (no key). Deep scan: `NVD_API_KEY=... ./mvnw -Psecurity-scan org.owasp:dependency-check-maven:check` (fails at CVSS >= 7; suppressions only in `backend/dependency-check-suppressions.xml` with a reason and an expiry). The OWASP profile resolves and is wired but its first run (NVD download) was NOT executed here: no key was available.
- **Before**: 4 vulnerable artifacts. `tomcat-embed-core 11.0.24`: CVE-2026-65905 (DIGEST auth bypass), CVE-2026-65182 (improper access control), CVE-2026-68525 (FORM auth), all rated CRITICAL, fixed in 11.0.25; none of the three authenticators is used (Spring Security does all authentication) but the version was fixed anyway. `jackson-core 3.1.5`: CVE-2026-89425, CVE-2026-89407 (HIGH, parser DoS), fixed in 3.1.7. `jackson-databind 3.1.5`: CVE-2026-91777, -91776, -68497 (HIGH, DoS), CVE-2026-83557, -19032 (MODERATE), fixed in 3.1.7.
- **Fix**: `tomcat.version=11.0.25` and `jackson-bom.version=3.1.7` overrides in `pom.xml` (documented in DECISIONS.md; drop them when Spring Boot manages fixed versions). **After**: 0 findings on the same 110 artifacts; `./mvnw verify` green.
- Direct dependencies beyond Boot: `stripe-java 34.0.0`, `commons-csv 1.14.1`: no advisories. Test scope (Testcontainers, ArchUnit 1.5.1) is not scanned.

### Residual risks (backend hardening)
- Per-IP limiter state is per instance (unchanged, section 2).
- Remaining feature cycles ({identity, notification, organization}, {compliance, document, vendor}) are frozen by `ArchitectureTest.KNOWN_CYCLES`, not removed.
- The repository-scoping rule checks the *signature* (a parameter named `organizationId`), not the query text; a `@Query` that ignores its parameter would pass. Mitigations: cross-tenant tests per endpoint (TESTING.md) and review of every `@Query`.
- No `Cross-Origin-*` (COOP/CORP/COEP) headers: the API is only reached same-origin through the Next.js proxy, and none of them protects JSON responses beyond what `nosniff` + CSP already do.
- HSTS depends on the proxy sending `X-Forwarded-Proto: https` from a trusted address (section 9); the edge should also send HSTS itself.

## 13. Production hosting notes (Render + Supabase, ADR-0010)
- Backend is a Render **private service** (no public URL); only `vendorflow-web` reaches it. ClamAV is private too.
- Supabase: Data API OFF; migration V10 revokes `anon`/`authenticated`/`service_role` privileges on `public`; the Storage bucket is private and only the API holds the S3 key; DB connection over TLS via the session-mode pooler.
- `ProfileGuard` also refuses prod with filesystem storage (without `STORAGE_ALLOW_FILESYSTEM_IN_PROD=true`) and with incomplete S3 settings or a non-https S3 endpoint.
- Ingress body caps (M2) cannot be expressed per route on Render: see DEPLOYMENT.md "Request limits at the ingress". Open follow-up: an in-app `Content-Length` filter or Cloudflare WAF rule.
- Client IP behind Render's edge + the Next.js hop is unverified until the first deploy: RUNBOOKS.md 1.10.

## Vendor portal controls (implemented, Phase 14, ADR-0011)
- Capability = 256-bit token (header `X-Portal-Token` on `GET /api/v1/portal/link` and `POST /api/v1/portal/link/documents`), stored as SHA-256 only; lookup by unique hash (no secret comparison in application code); format check (`[A-Za-z0-9_-]{43}`) before any DB access.
- Unknown / malformed / expired / revoked / inactive-vendor link: one identical 404. No tenant data beyond org display name, vendor name, requested types and their status.
- Org id comes from the link row; the session/TenantContext is ignored on `/api/v1/portal/**`. The 402 read-only interceptor is excluded there and replaced by an explicit check on the link's organization.
- Upload = the same pipeline as staff (size, extension, magic bytes, ClamAV fail closed, quota, server keys); the document is PENDING review; the use budget is claimed atomically in the upload transaction.
- Rate limits: per IP (`portal-view`, `portal-upload`) and per link (`portal-link` for everything, `portal-link-upload` 10/min for upload attempts INCLUDING failed ones); staff creation per user.
- **Anonymous multipart (M3)**: `PortalUploadGuardFilter` runs before Spring Security and before multipart resolution: malformed/unknown/unusable token -> the same 404 as every invalid link (one indexed hash lookup), declared Content-Length above the request limit -> 413, so a made-up token cannot make the server buffer or write a body. Tomcat `max-part-count` is pinned (10), `max-swallow-size` bounds drained bodies.
- **Budgets (M2)**: per link `max_uploads` (max 50) and `max_total_bytes` (default 100 MB, max 500 MB) claimed in ONE atomic UPDATE with the use count.
- **No displacement (M1)**: a vendor can never replace an APPROVED, unexpired document; the upload becomes a CANDIDATE that does not count for compliance until a staff reviewer approves it.
- The raw token is never persisted, audited or logged; `Cache-Control: no-store`, `Referrer-Policy: no-referrer` on every response (also errors); a test captures all log output of the portal flows (our code at DEBUG, framework loggers at their shipped levels) and asserts the token is absent. Framework DEBUG logging prints request lines and could print headers: never enable DEBUG for `org.springframework.web` or `org.springframework.security` in production.
- **Token transport**: the token is sent in the `X-Portal-Token` request header, never in the URL, so proxy/Render/Next.js access logs do not contain it; the application does not log request headers. Do not add header logging for `/api/v1/portal/` in any proxy.
- Residual risks: a link holder can replace the vendor's CURRENT document of an allowed type (old one kept as SUPERSEDED, new one needs staff approval); an emailed link sits in the vendor's mailbox until it expires or is revoked; the undelivered-email token scrub (24 h) leaves the link valid but the email DEAD (staff can create a new link).
