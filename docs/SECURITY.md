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
| Account takeover / brute force | bcrypt (cost 12) via DelegatingPasswordEncoder; per-account lockout (5 failures → 15 min); per-IP rate limit on auth endpoints; generic login error; session id rotated on login |
| Session theft | `HttpOnly; Secure; SameSite=Lax` cookie; server-side sessions (revocable); idle timeout 8h; logout invalidates server session |
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
| Enumeration | UUID ids; signup/password-reset responses do not reveal whether an email exists |
| Denial of wallet / abuse | Rate limits on auth, upload, invitation and email-triggering endpoints; pagination caps |

## 2. Authentication (ADR-0002)
- Email + password, Spring Security, server-side sessions stored in Postgres (Spring Session JDBC).
- Password policy: min 12 chars, max 128; checked against a small common-password list; no composition rules (NIST 800-63B).
- Email verification required before inviting others or receiving reminders (login allowed; banner shown).
- Password reset: single-use token (hash stored), 30-minute expiry; all sessions of the user invalidated on reset.
- Login: generic "Invalid email or password". Lockout counter per account; per-IP limiter in-process (documented
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

## 7. Secrets
- Supplied via environment variables (see `.env.example`). Local dev uses `.env` files that are gitignored.
- Required in prod: `DATABASE_URL/USER/PASSWORD`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `RESEND_API_KEY`,
  `STORAGE_S3_*`, `APP_IP_HASH_SECRET`. Rotation procedure: `docs/runbooks/secret-rotation.md` (Phase 11).

## 8. Reporting
Security issues: contact the owner privately; do not open public issues.
