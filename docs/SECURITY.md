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
- Password policy (implemented, `PasswordPolicy`): 12–128 chars and at most 72 UTF-8 bytes (bcrypt rejects longer input; Spring Security 7 throws), not equal to the email, not in `security/common-passwords.txt` (~300 entries, case-insensitive); no composition rules (NIST 800-63B).
- Hashing (implemented): `DelegatingPasswordEncoder` with a single `{bcrypt}` entry built by us at cost **12** (`app.security.bcrypt-strength`; the stock `PasswordEncoderFactories` factory is cost 10). The test profile lowers it to 4 for speed only. Unknown emails are checked against a dummy hash so login timing does not reveal whether an account exists.
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
- Log: auth success/failure (user id or email domain), authorization denials (permission, user, org), document
  operations (ids), reminder/outbox transitions, Stripe event ids/types, application errors with stack traces.

## 7. Secrets and startup safety
- `ProfileGuard` (fails the boot): `e2e`+`prod`; with `prod`: dev or short (<32) ip-hash secret, `session-cookie-secure=false`, non-https `app.base-url`; WITHOUT `prod`: a database host that is not localhost/127.0.0.1/[::1]/host.docker.internal unless `app.allow-non-local-db-without-prod=true` (a forgotten `SPRING_PROFILES_ACTIVE=prod` must not run production data with local settings; `application-test.yml` sets the opt-in because Testcontainers may use a remote Docker host).
- Supplied via environment variables (see `.env.example`). Local dev uses `.env` files that are gitignored.
- Required in prod: `DATABASE_URL/USER/PASSWORD`, `APP_BASE_URL` (https), optional `TRUSTED_PROXIES_REGEX`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `RESEND_API_KEY`,
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
Order: authz -> vendor in tenant (404) -> non-empty -> size (declared + streamed byte count, 15 MB) -> extension allowlist on the sanitized name -> magic bytes (client Content-Type ignored, mime detected) -> active org type -> dates -> `FileScanner` hook (422) -> streamed write (SHA-256 computed on the way) -> one DB transaction (vendor row lock, supersede, audit); object deleted if the transaction fails. Storage keys are `org/{uuid}/doc/{uuid}` validated against `[a-z0-9-]` segments and re-checked against the root after normalize. Filenames are display-only (last segment, control/bidi stripped, <=255). Downloads: authorize -> audit -> stream with attachment, nosniff, `Cache-Control: private, no-store`, `CSP: sandbox`, RFC 5987 filename. Uploads rate-limited 30/min/IP and 30/min/user; per-organization storage quota. Not done: real malware scanner, S3 storage.

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
