# VendorFlow — REST API

Base path: `/api/v1`. JSON only (except uploads: `multipart/form-data`; CSV export: `text/csv`).
Status in this file: **(planned)** until the phase that implements it marks it **(implemented)**.

## Conventions
- **Auth**: session cookie `VF_SESSION` (HttpOnly). Unsafe methods require header `X-XSRF-TOKEN` matching the
  `XSRF-TOKEN` cookie. `GET /api/v1/auth/csrf` primes the cookie.
- **Tenant**: the active organization comes from the session. No endpoint accepts `organizationId` as input
  (except `POST /session/organization`, which verifies membership).
- **IDs**: UUIDs. A resource in another org or nonexistent → `404`.
- **Pagination**: `?page=0&size=25` (max 100). Response:
  `{ "items": [...], "page": 0, "size": 25, "totalItems": 123, "totalPages": 5 }`
- **Sorting**: `?sort=companyName,asc` — fields are allowlisted per endpoint.
- **Errors**: RFC 9457 `application/problem+json`:
  ```json
  { "type": "https://vendorflow.app/problems/validation", "title": "Validation failed", "status": 400,
    "detail": "One or more fields are invalid.", "instance": "/api/v1/vendors", "requestId": "c0a8…",
    "errors": [ { "field": "email", "message": "must be a valid email" } ] }
  ```
  Codes: 400 validation, 401 unauthenticated, 403 forbidden (role) / no active org, 404 not found, 409 conflict
  (duplicate name, state conflict), 413 file too large, 415 unsupported file, 422 business rule, 429 rate limited,
  402 subscription inactive (write blocked).
- **Request id**: every response carries `X-Request-Id`; clients may send one (validated format) to correlate.

## Endpoints

### Health
- `GET /actuator/health` — liveness/readiness (no details exposed publicly). **(implemented Phase 0)**

### Auth & session (Phase 1)
- `GET  /auth/csrf` → 204, sets `XSRF-TOKEN`
- `POST /auth/signup` `{ email, password, fullName, organizationName }` → 201 (session started)
- `POST /auth/login` `{ email, password }` → 200 `Me`
- `POST /auth/logout` → 204
- `POST /auth/verify-email` `{ token }` → 204
- `POST /auth/resend-verification` → 204
- `POST /auth/password-reset/request` `{ email }` → 202 (always)
- `POST /auth/password-reset/confirm` `{ token, newPassword }` → 204
- `GET  /me` → `{ user, activeOrganization: { id, name, role }, organizations: [...] }`
- `POST /session/organization` `{ organizationId }` → 200 `Me` (membership verified)

### Organization (Phase 1)
- `GET   /organization` · `PATCH /organization` `{ name?, timeZone?, expiringWindowDays?, reminderOffsetsDays?, remindersEnabled? }`
- `GET   /organization/members` · `PATCH /organization/members/{membershipId}` `{ role }` · `DELETE /organization/members/{membershipId}`
- `GET   /organization/invitations` · `POST /organization/invitations` `{ email, role }` · `DELETE /organization/invitations/{id}`
- `POST  /invitations/lookup` `{ token }` (public) · `POST /invitations/accept` `{ token, fullName?, password? }`

### Phase 1 contract details (authoritative for backend + frontend)

**Tokens never appear in URL paths or query strings** (they would leak into access logs and `Referer`). Email links
put them in the fragment: `{APP_BASE_URL}/verify-email#token=…`, `/reset-password#token=…`, `/invite#token=…`.
The page reads `location.hash`, clears it with `history.replaceState`, and POSTs the token.

Shapes (JSON, camelCase, ISO-8601 timestamps):
```ts
type Role = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
type Me = {
  user: { id: string; email: string; fullName: string; emailVerified: boolean };
  activeOrganization: { id: string; name: string; role: Role } | null;   // null => user belongs to no org
  organizations: { id: string; name: string; role: Role }[];             // sorted by name
};
type Organization = { id: string; name: string; timeZone: string; expiringWindowDays: number;
  reminderOffsetsDays: number[]; remindersEnabled: boolean; createdAt: string };
type Member = { membershipId: string; userId: string; fullName: string; email: string; role: Role; joinedAt: string };
type Invitation = { id: string; email: string; role: Exclude<Role,"OWNER">; expiresAt: string; createdAt: string;
  invitedBy: { fullName: string } | null };
type InvitationLookup = { organizationName: string; role: Role; email: string; accountExists: boolean };
```
Rules:
- `signup`: `email` (valid, ≤254), `password` (12–128 chars, not a common password, not equal to email),
  `fullName` (1–100), `organizationName` (1–120). Creates user + org + OWNER membership, sets active org, starts the
  session, enqueues a verification email. Duplicate email → **409** `title: "Email already registered"`.
  (Trade-off accepted: signup reveals registration; login/reset do not.)
- `login`: wrong email, wrong password and locked account all → **401** with identical body
  `detail: "Invalid email or password."`. Active org = `app_user.last_active_organization_id` if still a member, else
  first membership by org name, else null. Session id is rotated.
- `logout`: invalidates the server session, clears cookie. 204 even if not logged in.
- Unauthenticated access to protected endpoints → 401. Authenticated with no active org on a tenant endpoint → 403
  `title: "No active organization"`.
- `PATCH /organization` requires `ORG_SETTINGS_MANAGE` (OWNER, ADMIN). `timeZone` must be a valid IANA zone;
  `expiringWindowDays` 1–180; `reminderOffsetsDays` 1–5 unique values each 1–180.
- Members: list requires `MEMBERS_VIEW` (all roles). Role change / removal require `MEMBERS_MANAGE` (OWNER, ADMIN).
  ADMIN cannot grant, change or remove an OWNER. The last OWNER cannot be demoted or removed (**409**).
  Any member may remove **their own** membership (leave), except the last OWNER.
- Invitations: `MEMBERS_MANAGE`; inviter must have a verified email (**422** otherwise); role ∈ ADMIN/MEMBER/VIEWER
  (only OWNER may invite ADMIN); expires in 7 days; re-inviting the same pending email → **409**; inviting an existing
  member → **409**. Revoke = `DELETE` (204).
- `invitations/lookup`: invalid/expired/revoked/accepted token → **404** (same body for all).
- `invitations/accept`: if a session exists, the session user's email must equal the invitation email (case-insensitive)
  else **403**; joins the org and makes it active. If no session and no account: `fullName` + `password` required,
  account is created with `emailVerified=true` (the token proves mailbox ownership) and a session starts. If no session
  and the account exists: **409** `title: "Sign in to accept"`.
- Rate limits (per client IP, in-process): login 10/min, signup 5/min, password-reset request 5/min,
  invitation lookup/accept 20/min, resend-verification 3/min → **429** with `Retry-After`.

### Vendors (Phase 2)
- `GET    /vendors?q=&status=&compliance=&category=&page=&size=&sort=` → page of `VendorSummary` (incl. compliance)
- `POST   /vendors` → 201 `VendorDetail`
- `GET    /vendors/{id}` → `VendorDetail` (info + requirements with status + current documents)
- `PATCH  /vendors/{id}`
- `PUT    /vendors/{id}/requirements` `{ documentTypeIds: [...] }`
- `GET    /vendors/{id}/history` → audit events for vendor + its documents (paginated)
- `POST   /vendors/{id}/document-requests` `{ documentTypeId }` → 202 (Phase 6)

### Document types (Phase 3)
- `GET /document-types` · `POST /document-types` · `PATCH /document-types/{id}`

### Documents (Phase 3)
- `POST  /vendors/{vendorId}/documents` multipart `{ file, documentTypeId, issueDate?, expirationDate? }` → 201
- `GET   /vendors/{vendorId}/documents?includeHistory=true`
- `GET   /documents/{id}` · `PATCH /documents/{id}` `{ issueDate?, expirationDate? }`
- `POST  /documents/{id}/review` `{ decision: APPROVED|REJECTED, note? }`
- `POST  /documents/{id}/archive`
- `GET   /documents/{id}/download` → streamed file, `Content-Disposition: attachment`

### Dashboard (Phase 5)
- `GET /dashboard/summary` → counts
- `GET /dashboard/attention?page=&size=` → prioritized action items

### CSV (Phase 7)
- `GET  /vendors/export.csv`
- `POST /vendors/import/preview` multipart `{ file }` → `{ importId, rows: [...], errors: [...], summary }`
- `POST /vendors/import/{importId}/commit` → applies (only if preview had no errors)

### Billing (Phase 8)
- `GET  /billing/subscription`
- `POST /billing/checkout-session` → `{ url }`
- `POST /billing/portal-session` → `{ url }`
- `POST /webhooks/stripe` (no session, no CSRF; signature-verified)
