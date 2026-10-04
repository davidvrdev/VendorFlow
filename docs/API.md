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
- `GET  /auth/csrf` → 204, sets `XSRF-TOKEN` **(implemented)**
- `POST /auth/signup` `{ email, password, fullName, organizationName }` → 201 `Me` (session started) **(implemented)**
- `POST /auth/login` `{ email, password }` → 200 `Me` **(implemented)**
- `POST /auth/logout` → 204 **(implemented)**
- `POST /auth/verify-email` `{ token }` → 204 **(implemented)**
- `POST /auth/resend-verification` → 204 **(implemented)**
- `POST /auth/password-reset/request` `{ email }` → 202 (always) **(implemented)**
- `POST /auth/password-reset/confirm` `{ token, newPassword }` → 204 **(implemented)**
- `POST /me/password` `{ currentPassword, newPassword }` → 204 **(implemented)**. Session user only (never an id from the body). Wrong current password or locked account: 400 "The current password is incorrect." (counts toward lockout); new password fails policy (12-128 chars, not the email, not in the top-100k list, not equal to the current one): 400 with `errors[].field = newPassword`; rate limit `password-change` 5/min per user: 429. Success revokes all other sessions, rotates this one (new `VF_SESSION` and `XSRF-TOKEN`: the client re-reads the cookie), writes audit `user.password_changed`, and queues a "password changed" email (also sent after a password reset).
- `GET  /me` → `{ user, activeOrganization: { id, name, role }, organizations: [...] }` **(implemented)**
- `POST /session/organization` `{ organizationId }` → 200 `Me` (membership verified) **(implemented)**

### Organization (Phase 1)
- `GET   /organization` · `PATCH /organization` `{ name?, timeZone?, expiringWindowDays?, reminderOffsetsDays?, remindersEnabled? }` **(implemented)**
- `GET   /organization/members` → page of `Member` · `PATCH /organization/members/{membershipId}` `{ role }` → 200 `Member` · `DELETE /organization/members/{membershipId}` → 204 **(implemented)**
- `GET   /organization/invitations` → page of `Invitation` (pending only) · `POST /organization/invitations` `{ email, role }` → 201 `Invitation` · `DELETE /organization/invitations/{id}` → 204 **(implemented)**
- `POST  /invitations/lookup` `{ token }` (public) → `InvitationLookup` · `POST /invitations/accept` `{ token, fullName?, password? }` (public; session optional) → 200 `Me` **(implemented)**

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
- `signup`: `email` (valid, ≤254), `password` (12–128 chars **and at most 72 UTF-8 bytes** — the bcrypt limit; not a common password, not equal to email),
  `fullName` (1–100), `organizationName` (1–120). Creates user + org + OWNER membership, sets active org, starts the
  session, enqueues a verification email. Duplicate email → **409** `title: "Email already registered"`.
  (Trade-off accepted: signup reveals registration; login/reset do not.)
- `login`: wrong email, wrong password and locked account all → **401** with identical body
  `detail: "Invalid email or password."`. Active org = `app_user.last_active_organization_id` if still a member, else
  first membership by org name, else null. Session id is rotated.
- `logout`: invalidates the server session, clears cookie. 204 even if not logged in.
- `POST /session/organization` for an org the user is not a member of (or nonexistent) → **404**.
- CSRF: every unsafe request (including login/signup) needs `X-XSRF-TOKEN` = `XSRF-TOKEN` cookie. If the cookie is
  missing, the client first calls `GET /auth/csrf`. Responses that rotate the token set a fresh `XSRF-TOKEN` cookie;
  the client always reads the cookie right before each request. Missing/invalid token → **403**.
- Session cookie: `VF_SESSION` (HttpOnly, SameSite=Lax, Secure in prod), idle timeout 8h.
- Unauthenticated access to protected endpoints → 401. Authenticated with no active org on a tenant endpoint → 403
  `title: "No active organization"`.
- `PATCH /organization` requires `ORG_SETTINGS_MANAGE` (OWNER, ADMIN). `timeZone` must be a valid IANA zone;
  `expiringWindowDays` 1–180; `reminderOffsetsDays` 1–5 unique values each 1–180.
- Members (precise rules, enforced in `MembershipService`; the membership must belong to the active org, else **404**):
  - List: `MEMBERS_VIEW` (all roles), sorted by `fullName` (case-insensitive), default size 50, max 100.
  - **OWNER** may set any role (including OWNER) and remove anyone, subject to the last-owner rule.
  - **ADMIN** may only act on MEMBER/VIEWER memberships and only assign MEMBER or VIEWER. Granting ADMIN/OWNER, or
    changing/removing an ADMIN or OWNER membership → **403**.
  - **MEMBER / VIEWER**: role changes → **403**; `DELETE` of someone else's membership → **403**; `DELETE` of their own is allowed.
  - **Nobody can change their own role** (**403**), not even an owner: another owner has to do it.
  - **Leaving** (`DELETE` of your own membership) is allowed for every role except the last OWNER.
  - **Last owner**: demoting or removing the only OWNER → **409** `title: "Last owner"`. Under concurrency the org's
    OWNER rows are locked `FOR UPDATE` first, so two owners cannot demote/remove each other and leave zero owners
    (the loser gets 403 or 409). `PATCH` with the member's current role is a no-op 200.
  - After leaving/removal the user's `last_active_organization_id` is cleared if it was that org, and their next request
    has no tenant context (403 `No active organization`) — the frontend should refresh `/me` and switch org.
- Invitations: `MEMBERS_MANAGE`; inviter must have a verified email (**422** otherwise); role ∈ ADMIN/MEMBER/VIEWER
  (only OWNER may invite ADMIN); expires in 7 days; re-inviting the same pending email → **409**; inviting an existing
  member → **409**. Revoke = `DELETE` (204).
- `invitations/lookup`: invalid/expired/revoked/accepted token → **404** (same body for all).
- `invitations/accept`: if a session exists, the session user's email must equal the invitation email (case-insensitive)
  else **403**; joins the org and makes it active. If no session and no account: `fullName` + `password` required,
  account is created with `emailVerified=true` (the token proves mailbox ownership) and a session starts. If no session
  and the account exists: **409** `title: "Sign in to accept"`.
- Rate limits (per client IP, in-process): login 10/min, signup 5/min, password-reset request 5/min,
  invitation lookup/accept 20/min, resend-verification 3/min, token redemption (`verify-email` + `password-reset/confirm`, shared) 20/min → **429** with `Retry-After`; **413** `.../file-too-large` when the declared `Content-Length` exceeds the multipart request limit (17 MB).
  A servlet filter (`PortalUploadGuardFilter`) answers the 404 (missing / not 43-char base64url / unknown / unusable token) and the 413 BEFORE the multipart body is parsed, so nothing is buffered or written for a bad request; Tomcat `max-part-count` = 10.

**Implemented in Phase 1 / B1 (auth, session, org settings) — exact client flow and details:**
1. App start / before the first unsafe request: if there is no `XSRF-TOKEN` cookie, `GET /api/v1/auth/csrf` (204; sets
   `XSRF-TOKEN`, readable by JS, SameSite=Lax).
2. Every unsafe request (POST/PATCH/PUT/DELETE, **including login, signup and logout**): send header `X-XSRF-TOKEN` with
   the CURRENT value of the `XSRF-TOKEN` cookie (read it right before sending). The token is a plain string, not masked.
3. `POST /auth/login` and `POST /auth/signup` **rotate** both cookies: the response sets a new `VF_SESSION` (HttpOnly,
   never readable by JS) and a new `XSRF-TOKEN`. Always re-read the cookie after these calls; a token read before login
   is rejected (403) afterwards.
4. Logout clears `VF_SESSION`; `XSRF-TOKEN` stays valid. A 401 on any call means the session is gone → go to login.
- Login failure → 401 `{ title: "Authentication failed", detail: "Invalid email or password." }` (also when locked).
  Lockout: 5 consecutive failures → 15 minutes.
- Signup/field errors → 400 with `errors: [{ field, message }]`; password problems use `field: "password"`
  (length, > 72 bytes, equals email, common password). **Deviation from the earlier draft: a password may be at most 72
  UTF-8 bytes** (bcrypt limit) in addition to 12–128 characters; the frontend should cap input at 72 characters.
- Duplicate email → 409 `title: "Email already registered"`.
- Tenant endpoint without an active org → 403 `title: "No active organization"`; without permission → 403
  `title: "Access denied"`; unauthenticated → 401 `title: "Authentication required"`.
- `PATCH /organization`: all fields optional (null/absent = unchanged); unknown fields (e.g. `organizationId`) are ignored.
  Field errors: `name` (blank / > 120), `timeZone` (not an IANA id), `expiringWindowDays`, `reminderOffsetsDays`
  (size 1–5, each 1–180, duplicates).
- `POST /auth/resend-verification` requires a logged-in session (it takes no body); the other `/auth/*` endpoints
  listed above are public. Only `csrf`, `signup`, `login`, `logout`, `verify-email`, `password-reset/**` are public.

**Implemented in Phase 1 / B2 (verification, reset, members, invitations) — details the client needs:**
- Emailed links: `{APP_BASE_URL}/verify-email#token=…` (24 h), `/reset-password#token=…` (30 min), `/invite#token=…` (7 days).
  Tokens are single use (verify/reset); a new link of the same kind invalidates the older ones.
- `verify-email` (public) / `password-reset/confirm` (public): any bad token (unknown, expired, used, wrong kind) →
  **400** `title: "Invalid or expired link"`, identical body. A valid token for an already verified user → 204.
  `confirm` field errors (length, > 72 bytes, common, equals email) are on **`newPassword`**; a weak password does NOT
  consume the token. `confirm` does not log in; it revokes all sessions of the user (other devices get 401) and clears
  the lockout.
- `resend-verification` needs a session (401 otherwise); verified user → 204 no-op; 3/min per IP → 429.
- `password-reset/request`: always 202 with an empty body (also for unknown addresses); a
  syntactically invalid email → 400 on `email`.
- Page lists (`members`, `invitations`): `?page=0&size=50`; `size` is clamped to 1..100 and a negative page to 0 (no 400).
  `Member.joinedAt` = membership creation time. `Invitation` never contains the token.
- Invitations `POST`: role `OWNER` or unknown → 400 (field `role`); `ADMIN` by a non-owner → 403; unverified inviter →
  **422** `Email not verified`; existing member (case-insensitive) → **409** `Already a member`; open invitation for the
  email → **409** `Invitation already pending` (an expired, unrevoked one is replaced silently).
- Invitations `DELETE`: pending or expired-but-unrevoked → 204; foreign, unknown, already accepted or already revoked → **404**.
- `invitations/lookup`: 200 `InvitationLookup`, else **404** (same body for unknown/expired/revoked/accepted).
- `invitations/accept` (public, CSRF required like every POST; send the session cookie if the user is logged in):
  - invalid/expired/revoked/accepted token → **404** (same body as lookup).
  - session user whose email differs → **403** `title: "Wrong account"` (the invitation stays pending).
  - session user matches → joins (if already a member the existing role is kept, the invitation is just consumed),
    active organization is switched to the inviting org → **200** `Me`.
  - no session, account exists → **409** `title: "Sign in to accept"` (nothing is changed; sign in, then call accept again with the same token).
  - no session, no account: `fullName` (1–100) and `password` required, else **400** with field errors `fullName` /
    `password` (policy errors on `password`); creates a verified account, joins, starts a session (new `VF_SESSION` and a
    rotated `XSRF-TOKEN` — re-read the cookie) → **200** `Me` (not 201).
  - Two simultaneous accepts of one link: one 200, the other 404; never two memberships.
- Rate limit `invitation` (20/min/IP) covers lookup + accept.
- **E2E only (profile `e2e`, never prod):** `GET /api/test/mailbox?to={email}` (no auth) → `[ { kind, to, subject, links[],
  receivedAt } ]`, oldest first, last 200 messages. Absent (401/404) in every other profile.

### Vendors (Phase 2)
- `GET    /vendors?q=&status=&category=&page=&size=&sort=` → page of `VendorSummary` **(implemented)**
  (+ `compliance` filter, sorts `compliance`/`nextExpiration` and `compliance` summary field: Phase 4, **implemented**)
- `GET    /vendors/categories` → `string[]` distinct non-empty categories of the org, sorted (for the filter) **(implemented)**
- `POST   /vendors` → 201 `VendorDetail` **(implemented)**
- `GET    /vendors/{id}` → `VendorDetail` **(implemented)**
- `PUT    /vendors/{id}` → 200 `VendorDetail` (full replacement of the editable fields) **(implemented)**
- `POST   /vendors/{id}/deactivate` · `POST /vendors/{id}/reactivate` → 200 `VendorDetail` **(implemented)**
- `PUT    /vendors/{id}/requirements` `{ documentTypeIds: [...] }` → 200 `VendorDetail` **(implemented)**
- `GET    /vendors/{id}/history?page=&size=` → page of `HistoryEvent` (vendor events; Phase 3 adds its documents' events) **(implemented)**
- `POST   /vendors/{id}/document-requests` `{ documentTypeId }` → 202 `DocumentRequestResult` **(implemented, Phase 6)**

### Document types (Phase 2: read · Phase 3: manage)
- `GET /document-types` → `DocumentType[]` (active types, by `sortOrder`) — Phase 2 **(implemented)**
- `POST /document-types` · `PATCH /document-types/{id}` · `GET /document-types?includeInactive=true` — Phase 3 **(implemented)**

### Phase 2 contract details (authoritative for backend + frontend)
```ts
type VendorStatus = "ACTIVE" | "INACTIVE";
type DocumentType = { id: string; code: string; name: string; hasExpiration: boolean; requiredByDefault: boolean;
  sortOrder: number };
type VendorSummary = { id: string; companyName: string; contactName: string | null; email: string | null;
  phone: string | null; category: string | null; status: VendorStatus; requirementCount: number;
  createdAt: string; updatedAt: string };
type VendorDetail = VendorSummary & { notes: string | null; createdBy: { fullName: string } | null;
  requirements: { documentTypeId: string; code: string; name: string; hasExpiration: boolean }[] };  // by sortOrder
type VendorInput = { companyName: string; contactName?: string | null; email?: string | null; phone?: string | null;
  category?: string | null; notes?: string | null };              // body of POST /vendors and PUT /vendors/{id}
type HistoryEvent = { id: string; action: string; actor: { fullName: string } | null; occurredAt: string;
  changes: Record<string, { before: unknown; after: unknown }> | null };
```
Rules:
- Permissions (SECURITY.md §3): list/detail/history/categories/document-types → `VENDORS_VIEW` (all roles);
  create + edit → `VENDORS_WRITE` (OWNER/ADMIN/MEMBER); deactivate/reactivate → `ARCHIVE_AND_IMPORT` (OWNER/ADMIN);
  requirements → `REQUIREMENTS_MANAGE` (OWNER/ADMIN).
- Validation (strings trimmed; empty string → null for optional fields; control/bidi characters rejected):
  `companyName` 1–200; `contactName` ≤120; `email` valid ≤254; `phone` ≤40 chars of `[0-9+().\- x]` and "ext";
  `category` ≤60; `notes` ≤5000. Unknown JSON fields are ignored (never bound to the entity).
- `companyName` is unique per organization, case-insensitive (after trim) → **409** `title: "Vendor already exists"`.
- `POST /vendors` attaches the org's active document types with `requiredByDefault=true` as requirements.
- `PUT /vendors/{id}/requirements`: every id must be an **active document type of the active org**, otherwise **400**
  field error on `documentTypeIds` (same response for foreign and nonexistent ids). Duplicates ignored. Empty list allowed.
- Deactivating an INACTIVE vendor (or reactivating an ACTIVE one) is a no-op 200.
- List: `q` matches company name, contact name or email (case-insensitive substring; `%`/`_` escaped);
  `status` = `ACTIVE` (default) | `INACTIVE` | `ALL`; `category` exact (case-insensitive);
  `sort` ∈ `companyName` (default), `createdAt`, `updatedAt`, each `,asc|,desc`; anything else → 400.
  `size` default 25, max 100.
- Foreign or nonexistent vendor id → **404** on every `/vendors/{id}…` endpoint.
- Audit actions: `vendor.created`, `vendor.updated` (before/after of changed fields), `vendor.deactivated`,
  `vendor.reactivated`, `vendor.requirements_changed` (`added`/`removed` type codes).
- New organizations get the 8 default document types (DATABASE.md § document_type); existing orgs are backfilled by
  the Phase 2 migration.

**Implementation notes (Phase 2 backend; where behavior is more specific than, or differs from, the text above):**
- Permission names in code: `VENDORS_VIEW` = `DATA_VIEW`, `VENDORS_WRITE` = `CONTENT_WRITE` (existing Phase 1 enum).
- A malformed (non-UUID) id in the path → **400** (Spring's type-mismatch handling, same as membership/invitation ids);
  a well-formed id that is foreign or nonexistent → 404.
- `notes` may contain line breaks (LF, CR, TAB); every other control/format/bidi character is rejected there too.
  The other text fields reject all control/format characters (a trailing/leading newline is stripped, not rejected).
- `phone`: `[0-9+().\- ]`, `x` and `ext` (case-insensitive), ≤ 40 chars; validation runs after stripping.
- Validation error `field` names: `companyName`, `contactName`, `email`, `phone`, `category`, `notes`, `documentTypeIds`,
  `sort`, `status`. Requirement errors are a single `documentTypeIds` violation (same body for foreign, unknown, inactive).
- `PUT /vendors/{id}` that changes nothing → 200 with the current vendor, no audit event, `updatedAt` unchanged.
  `PUT …/requirements` with the same set → same (no event). A requirement whose type was later deactivated is dropped
  when absent from `documentTypeIds` (inactive ids cannot be sent), so clients should send the ids they display.
- List: `q` is stripped; blank `q`/`category` = no filter; `sort` without direction = `asc`, `sort=` (blank) = default;
  `companyName` sorts case-insensitively; every sort ends with `id` as tie-break. `size` is **clamped** to 1..100
  (default 25), negative `page` → 0 (no 400), consistent with Phase 1 lists. `requirementCount` counts all stored
  requirements. LIKE escaping uses `!` as the escape character (`%`, `_`, `!` are literal).
- `POST /vendors` returns `VendorDetail` with `requirements` in `sortOrder`; `requirementCount` = `requirements.length`.
- Audit rows (`entity_type = "vendor"`) and the History `changes` mapping:
  - `vendor.created` metadata `{companyName, requirements: [codes]}` → `changes: null`.
  - `vendor.updated` metadata `{changes: {field: {before, after}}}` (changed fields only) → passed through.
  - `vendor.deactivated` / `vendor.reactivated` metadata `{changes: {status: {before, after}}}` → passed through.
  - `vendor.requirements_changed` metadata `{added: [codes], removed: [codes]}` → exposed as
    `changes: { added: {before: null, after: [codes]}, removed: {before: [codes], after: null} }` (a key is omitted when
    its list is empty), so every `changes` value keeps the `{before, after}` shape.
  - Phase 1 `organization.updated` already used `{changes: {field: {before, after}}}`; membership events use a flat
    `{before, after}` and are not vendor history.
  - `actor` is null when the actor is unknown. Events are newest first (`createdAt desc, id desc`).

### Documents (Phase 3) **(implemented)**
- `POST  /vendors/{vendorId}/documents` multipart `{ file, documentTypeId, issueDate?, expirationDate? }` → 201
- `GET   /vendors/{vendorId}/documents?includeHistory=true`
- `GET   /documents/{id}` · `PATCH /documents/{id}` `{ issueDate?, expirationDate? }`
- `POST  /documents/{id}/review` `{ decision: APPROVED|REJECTED, note? }`
- `POST  /documents/{id}/archive`
- `GET   /documents/{id}/download` → streamed file, `Content-Disposition: attachment`


### Phase 3 contract details (authoritative for backend + frontend)
```ts
type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";
type DocumentState = "CURRENT" | "CANDIDATE" | "SUPERSEDED" | "ARCHIVED";   // CANDIDATE: Phase 14 portal upload awaiting review
type DocumentSummary = { id: string; vendorId: string; documentType: { id: string; code: string; name: string;
  hasExpiration: boolean }; state: DocumentState; reviewStatus: ReviewStatus; issueDate: string | null;   // yyyy-MM-dd
  expirationDate: string | null; originalFilename: string; mimeType: string; sizeBytes: number;
  uploadedBy: { fullName: string } | null; uploadedAt: string; reviewedBy: { fullName: string } | null;
  reviewedAt: string | null; reviewNote: string | null };
type DocumentTypeAdmin = DocumentType & { active: boolean };          // GET /document-types?includeInactive=true
// VendorDetail.requirements[i] gains: currentDocument: DocumentSummary | null
// VendorDetail gains: otherDocuments: DocumentSummary[]   // CURRENT docs whose type is not a requirement
```
Endpoints:
- `POST /vendors/{vendorId}/documents` multipart: `file`, `documentTypeId`, `issueDate?`, `expirationDate?` → 201 `DocumentSummary`
- `GET  /vendors/{vendorId}/documents?includeHistory=false|true` → `DocumentSummary[]` (CURRENT, plus the CANDIDATE awaiting review if any, by default;
  history adds SUPERSEDED + ARCHIVED; ordered by type sortOrder, then uploadedAt desc). Bounded: ≤ 500 rows.
- `GET  /documents/{id}` → `DocumentSummary`
- `PATCH /documents/{id}` `{ issueDate?, expirationDate? }` (explicit `null` clears issueDate; expirationDate cannot be
  cleared when the type has expiration) → 200 `DocumentSummary`
- `POST /documents/{id}/review` `{ decision: "APPROVED"|"REJECTED", note?: string }` → 200
- `POST /documents/{id}/archive` → 200
- `GET  /documents/{id}/download` → file stream
- `GET  /document-types?includeInactive=true` (REQUIREMENTS_MANAGE for inactive) · `POST /document-types`
  `{ name, hasExpiration, requiredByDefault }` → 201 · `PATCH /document-types/{id}` `{ name?, hasExpiration?,
  requiredByDefault?, active?, sortOrder? }` → 200

Rules:
- Permissions: upload + PATCH → VENDORS_WRITE; review → DOCUMENTS_REVIEW; archive → ARCHIVE_AND_IMPORT;
  list/get/download → DOCUMENTS_DOWNLOAD / VENDORS_VIEW (all roles); document-type management → REQUIREMENTS_MANAGE.
- Every id is resolved within the active org: document → its vendor → org. Foreign/nonexistent → 404.
- Upload validation, in this order (cheap first):
  1. multipart part `file` present and non-empty → else 400 field `file`.
  2. size ≤ 15 MB (configurable `app.documents.max-size`) → else **413**.
     Organization storage quota (`app.documents.org-quota`, default 5 GB, counts every document state) → else **413**
     problem `title: "Storage quota exceeded"`, type `.../storage-quota-exceeded` (checked again with the streamed
     byte count inside the transaction).
  3. extension of the sanitized original filename ∈ {pdf, png, jpg, jpeg} → else **415**.
  4. magic bytes match the extension family (PDF `%PDF-`; PNG `89 50 4E 47 0D 0A 1A 0A`; JPEG `FF D8 FF`) → else **415**.
     Client `Content-Type` is ignored; stored `mime_type` is the detected one.
  5. `documentTypeId` = active type of the org → else 400 field `documentTypeId`.
  6. dates `yyyy-MM-dd`, within 1990-01-01..2100-12-31; `issueDate ≤ expirationDate`; `expirationDate` required iff
     the type `hasExpiration` → else 400 field errors.
- Filename sanitizing: take the last path segment (both `/` and `\`), strip control/bidi chars, collapse whitespace,
  max 255 chars keeping the extension, fallback `document.<ext>`. Display only; never used in storage paths.
- Storage key `org/{orgId}/doc/{documentId}` (server-generated). Object written BEFORE the DB transaction; if the
  transaction fails the object is deleted (best effort, logged). SHA-256 computed while streaming.
- A new upload for (vendor, type) supersedes the CURRENT one in the same transaction (vendor row locked
  `FOR UPDATE` to serialize concurrent uploads; partial unique index is the backstop). New docs start `PENDING`.
- Review: only CURRENT or CANDIDATE documents (else 409 "Document is not current"). Approving a CANDIDATE supersedes the old CURRENT document and promotes the candidate in one transaction (audit `document.superseded` with `source`); rejecting it (note required) leaves the old one CURRENT and archives the candidate. A candidate cannot have its dates edited.
  `VendorDetail.requirements[].pendingReplacement: DocumentSummary | null` = the candidate; status/summary are computed from `currentDocument` only. `REJECTED` requires a note (1–1000 chars).
- Archive: CURRENT or SUPERSEDED → ARCHIVED (idempotent); archiving the CURRENT doc does not promote an older one.
- PATCH dates on SUPERSEDED/ARCHIVED documents → 409. Changing dates does not change review status; audited.
- Download: authorization → audit `document.downloaded` → stream with `Content-Type` = stored mime,
  `Content-Disposition: attachment; filename="<ascii fallback>"; filename*=UTF-8''<encoded>`,
  `X-Content-Type-Options: nosniff`, `Cache-Control: private, no-store`, `Content-Security-Policy: sandbox`.
- Rate limit: uploads 30/min per client IP AND 30/min per authenticated user (**429** problem + `Retry-After`).
- Audit: `document.uploaded` (metadata includes `vendorId`, type code, filename, size), `document.superseded`,
  `document.reviewed` (decision, note), `document.dates_changed` (before/after), `document.archived`,
  `document.downloaded`; `document_type.created/updated`. Vendor history includes events whose
  `metadata.vendorId` = the vendor.
- Document types: code for custom types = `CUSTOM_` + uppercase slug of the name + short suffix if taken;
  names unique per org (case-insensitive) → 409. Deactivated types are hidden from pickers; existing requirements
  of inactive types are ignored by compliance (Phase 4) and shown greyed in the UI.
- Storage abstraction: `ObjectStorage` (put stream, get stream, delete, exists); `FilesystemObjectStorage` (root from
  `app.storage.filesystem.root`; resolves key → normalized path and asserts it stays under root; atomic write via temp
  file + move). `S3ObjectStorage` comes before Phase 11 (ADR-0007). `FileScanner` interface with no-op implementation
  called before storing (hook for malware scanning).
- Next.js rewrite must pass 15 MB multipart bodies (verify Next 16 proxy body limits; configure if needed).

**Implementation notes (Phase 3 backend; differences/additions vs the contract above):**
- `POST /vendors/{id}/documents`: multipart fields are read as text so the validation order holds; malformed/unknown/foreign/inactive `documentTypeId` -> 400 field `documentTypeId` (same body). Blank dates = absent. 403 (role) and 404 (vendor) come before any file validation. Non-multipart body -> 415. Scanner rejection -> 422 title "File rejected".
- Container multipart limit is 16 MB/file (17 MB request), above `app.documents.max-size` (15 MB); both give 413 problem+json (`title: "File too large"`).
- `expirationDate` is accepted (and stored) for types without expiration; it may be cleared there.
- PATCH body is read as a raw JSON object: absent key = unchanged, `null` clears; empty body/no change = 200, no audit. 409 check precedes validation. Non-string date -> 400.
- Review: `note` optional for APPROVED (stored); `decision` other than APPROVED/REJECTED -> 400. Re-review of a CURRENT document is allowed.
- Archive returns 200 `DocumentSummary`; already archived = no-op 200.
- Only `GET` is served on download; `HEAD` -> 405 (`Allow: GET`), no audit row. CSRF token is read from `X-XSRF-TOKEN` only (a `_csrf` form field is ignored: 403).
- Download works for CURRENT/SUPERSEDED/ARCHIVED; missing stored object -> 404 problem (no audit, ERROR log). No Range support.
- `GET /document-types` default shape is unchanged (no `active`); `includeInactive=true` returns `DocumentTypeAdmin[]` (403 without REQUIREMENTS_MANAGE). POST: name 1-100, unique per org case-insensitive incl. inactive and defaults -> 409 "Document type already exists"; `code` = `CUSTOM_<SLUG>[_XXXX]`, `sortOrder` = max+10, `active` true (all server-decided). PATCH: all fields optional, `code` immutable.
- `VendorDetail.requirements[i].currentDocument` (null if none) and `otherDocuments` added. `HistoryEvent` gains additive `detail: string | null` ("<type name> - <filename>" for document events). Document events in history: `document.uploaded|superseded|reviewed|dates_changed|archived|downloaded`; `reviewed` -> `changes.reviewStatus`, `archived` -> `changes.state`, `dates_changed` -> `changes.issueDate/expirationDate`.
- Rate limit rule `document-upload` (30/min/IP) matches `POST /api/v1/vendors/{anything}/documents`.

### Phase 4 contract details — compliance engine (authoritative for backend + frontend)

**Requirement status** (per vendor requirement whose document type is ACTIVE; requirements on inactive types are
ignored everywhere). Evaluate top to bottom, first match wins. `today` = current date in the organization's time
zone (`organization.time_zone`); `window` = `organization.expiring_window_days`.

| # | Status | Condition |
|---|---|---|
| 1 | `MISSING` | no CURRENT document for (vendor, type), or the CURRENT document is `REJECTED` |
| 2 | `EXPIRED` | type `hasExpiration` and `expirationDate < today` |
| 3 | `REVIEW_REQUIRED` | document `reviewStatus = PENDING`, **or** type `hasExpiration` and `expirationDate` is null (type changed after upload) |
| 4 | `EXPIRING` | type `hasExpiration` and `expirationDate − today ≤ window` (so expiring today = EXPIRING, 0 days left) |
| 5 | `OK` | otherwise |

Notes: an APPROVED but expired document is EXPIRED (rule 2 before 3). A PENDING expired document is EXPIRED
(expired is the more urgent action). Types without expiration never become EXPIRED/EXPIRING.

**Vendor compliance**: `NON_COMPLIANT` if any requirement is MISSING or EXPIRED; else `ATTENTION` if any is
REVIEW_REQUIRED or EXPIRING; else `COMPLIANT`. A vendor with zero (active) requirements is `COMPLIANT` with
`requirementCount = 0` (UI shows "No requirements set"). INACTIVE vendors still get a computed status but are excluded
from dashboard counts (Phase 5) and reminders (Phase 6).

**Single source of truth**: `ComplianceCalculator` (pure Java, no Spring) defines the rules. List/dashboard queries
compute the same in SQL. A shared table of test vectors (`compliance-vectors.csv` in test resources: hasExpiration,
reviewStatus|none, expirationDate offset, window → expected status) runs against BOTH the Java calculator and the SQL
(insert fixtures, query, compare). Any divergence fails the build.

```ts
type RequirementStatus = "MISSING" | "OK" | "EXPIRING" | "EXPIRED" | "REVIEW_REQUIRED";
type VendorCompliance = "COMPLIANT" | "ATTENTION" | "NON_COMPLIANT";
type ComplianceSummary = { status: VendorCompliance; missing: number; expired: number; expiring: number;
  reviewRequired: number; ok: number; nextExpiration: string | null };   // earliest expirationDate among CURRENT,
                                                                        // non-rejected docs of active requirement types
// VendorSummary gains: compliance: ComplianceSummary          (requirementCount = active requirements only)
// VendorDetail gains:  compliance: ComplianceSummary
// VendorDetail.requirements[i] gains: status: RequirementStatus; daysUntilExpiration: number | null; active: boolean
```
List additions: `GET /vendors?compliance=COMPLIANT|ATTENTION|NON_COMPLIANT` (invalid → 400 field `compliance`);
`sort` adds `compliance` (NON_COMPLIANT → ATTENTION → COMPLIANT, then companyName) and `nextExpiration`
(nulls last). Still one page query + one count query (N+1 guard test extended).
Org setting changes (`expiringWindowDays`, `timeZone`) apply immediately (nothing stored).

**Implementation notes (Phase 4 backend) — IMPLEMENTED; differences/additions vs the contract above:**
- Shared vectors live in `backend/src/test/resources/compliance/vectors.csv` (28 requirement rows; the contract text said
  `compliance-vectors.csv`) and `vendor-vectors.csv` (10 vendor-aggregation rows). `ComplianceCalculatorVectorsTest` runs
  them through `ComplianceCalculator`; `ComplianceListSqlTest` inserts each row in Postgres and compares `GET /vendors`.
- "Today" is `LocalDate.now(clock.withZone(ZoneId.of(org.timeZone)))` (`ComplianceContextService`); it and the window are
  bind parameters of the SQL (never `now()`). The list costs one extra statement (org settings): tenant + org + page + count.
- `nextExpiration` = earliest `expirationDate` of a CURRENT, non-rejected document whose type `hasExpiration` (expired dates
  count, so it can be in the past); no-expiration types never contribute even if their document has dates.
- `requirementCount` (list and detail) = ACTIVE requirements only. `compliance` is present on every list item and on detail.
- `VendorDetail.requirements` still lists requirements on INACTIVE types (`active: false`); they get a computed
  `status`/`daysUntilExpiration` (so the UI can grey them) but are NOT counted in `compliance` or `requirementCount`.
- `daysUntilExpiration` = `expirationDate - today` (negative when expired); null when the type has no expiration, there is
  no CURRENT document, the document is REJECTED, or the date is null.
- `sort=compliance[,asc|desc]`: asc = NON_COMPLIANT, ATTENTION, COMPLIANT (desc reverses the rank); ties by companyName
  ascending then id. `sort=nextExpiration[,asc|desc]`: vendors with no date are always last; tie-break id. An invalid `sort`
  message now lists the five fields. Invalid `compliance` -> 400 `errors[0].field = "compliance"`; filter is case-sensitive.
- The `compliance` filter is applied to the computed status, so `totalItems`/`totalPages` reflect it (same CTEs for page and count).
- No new index/migration: the aggregation reads `vendor_requirement` and CURRENT documents by organization using existing
  indexes (`document_current_uq`, `vendor_requirement_vendor_type_uq`); revisit with EXPLAIN when org size warrants.

### Dashboard (Phase 5)
- `GET /dashboard/summary` → `DashboardSummary`
- `GET /dashboard/attention?page=&size=` → page of `AttentionItem` (prioritized action list)

### Phase 5 contract details — dashboard (authoritative for backend + frontend)
Answers in one screen: how many vendors, how many compliant, what is missing/expired/expiring, what needs action today.
Scope: **ACTIVE vendors** and **active document types** only. Same rules and same "today" (org time zone) as Phase 4
— reuse the Phase 4 SQL (extract the per-requirement CTE into one shared builder used by the vendor list AND the
dashboard; never duplicate the status CASE expression).
```ts
type DashboardSummary = {
  today: string;                     // yyyy-MM-dd in the org time zone (what "today" meant for these numbers)
  expiringWindowDays: number;
  vendors: { active: number; compliant: number; attention: number; nonCompliant: number; noRequirements: number };
  documents: { missing: number; expired: number; expiring: number; reviewRequired: number };   // requirement counts
};
type AttentionAction = "UPLOAD" | "UPLOAD_RENEWAL" | "REVIEW";
type AttentionItem = {
  vendorId: string; vendorName: string;
  documentTypeId: string; documentTypeName: string;
  status: "MISSING" | "EXPIRED" | "EXPIRING" | "REVIEW_REQUIRED";
  documentId: string | null;                 // CURRENT document if any (null for MISSING without a document)
  expirationDate: string | null; daysUntilExpiration: number | null;
  action: AttentionAction;                   // MISSING/EXPIRED → UPLOAD, EXPIRING → UPLOAD_RENEWAL, REVIEW_REQUIRED → REVIEW
};
```
Rules:
- `vendors.compliant + attention + nonCompliant = active` (vendors with no requirements count as compliant AND in
  `noRequirements`).
- Attention order (most urgent first): EXPIRED (most overdue first) → MISSING (vendor name, then type sortOrder) →
  EXPIRING (soonest first) → REVIEW_REQUIRED (oldest upload first); tie-break vendor id, type id. Stable paging.
  Default size 10, max 100. OK requirements never appear.
- Permission: `VENDORS_VIEW` (all roles). Tenant-scoped like everything else.
- Performance: both endpoints are one query each (+ count for the page); a test seeds 500 vendors × 6 requirements
  (≈3,000 documents) and asserts bounded statement count and a generous latency budget.

**Implementation notes (Phase 5 backend) — IMPLEMENTED; no differences from the contract above except these details:**
- The per-requirement CTE lives once in `compliance.infrastructure.RequirementStatusSql.REQ_CTE`; `VendorSearchRepository`
  and `dashboard.infrastructure.DashboardRepository` both build on it (the status CASE exists nowhere else).
- `today` in the response = org-time-zone date used for the numbers; `expiringWindowDays` = the window used. Both come from
  `ComplianceContextService` (statements per request: tenant + org settings + 1 query; the attention page adds 1 count).
- `GET /dashboard/attention` paging follows the shared convention: `page` (0-based, negative → 0), `size` default **10**,
  clamped to 1..100 (never 400). Envelope = the usual `{ items, page, size, totalItems, totalPages }`.
- Vendors with zero (active) requirements count as `compliant` AND `noRequirements`; a vendor whose only requirements are on
  inactive types is therefore `noRequirements` too. `documents.*` count requirements (one per vendor × active type), not files.
- `MISSING` item with a REJECTED CURRENT document: `documentId` = that rejected document, `expirationDate` and
  `daysUntilExpiration` = null. `MISSING` without any document: `documentId` null. `REVIEW_REQUIRED`: `expirationDate` is
  the document's date when the type has expiration (may be null), `daysUntilExpiration` derived from it (null if no date).
  `daysUntilExpiration` is negative for EXPIRED, 0 for expiring today.
- Ordering details: EXPIRED/EXPIRING by `expirationDate` ascending; MISSING by `lower(vendorName)` then type `sortOrder`;
  REVIEW_REQUIRED by the CURRENT document's `createdAt` (upload time) ascending; then vendor id, then type id (uuid order).
- Permission: `DATA_VIEW` (every role). Anonymous 401; no active organization 403.
- Performance evidence (500 vendors, 3,000 requirements, 2,852 CURRENT documents, local Postgres 17): summary 102 ms
  end-to-end / 3 statements (query execution 29 ms); attention `size=100` 162 ms / 4 statements (13 ms in Postgres). The
  plans are hash joins over the existing org-scoped indexes (`vendor_requirement_org_type_idx`); at this size the whole
  org fits in a few hundred shared buffers, so **no new index/migration** was added. Revisit with `EXPLAIN (ANALYZE, BUFFERS)`
  (the test prints both plans) if an organization grows by 10x or more.
- Tests: `DashboardTest` (invariants, exclusions, ordering/tie-breaks, paging, permissions, tenant isolation, time zone),
  `ComplianceDashboardSqlTest` (shared `vectors.csv` parity vs the dashboard), `DashboardPerformanceTest` (500 vendors).
- **Additive (Phase 6): `AttentionItem.vendorEmail: string | null`** = `vendor.email` (for the "Request document" action); selected in the same attention query (the status CASE is still only in `RequirementStatusSql`). Tested in `DashboardTest`.

### Notifications (Phase 6)
- `POST /vendors/{id}/document-requests` `{ documentTypeId }` → 202 `DocumentRequestResult` **(implemented)**
- `GET  /notifications?page=&size=` → page of `NotificationView` (email activity; OWNER/ADMIN) **(implemented)**

### Phase 6 contract details — notifications (authoritative for backend + frontend)
**Email delivery (Resend).** `ResendEmailSender` implements the existing `EmailSender` port with a plain HTTP client
(no SDK): `POST https://api.resend.com/emails`, `Authorization: Bearer ${RESEND_API_KEY}`, `Idempotency-Key` = the
notification id, JSON `{from, to, subject, html, text, reply_to?}` — verify the exact request/response/error format
in Resend's official docs before coding. Selected when `app.email.provider=resend`; `LoggingEmailSender` otherwise
(local/test). Result classification for the outbox: 2xx → SENT (store provider id); 429 / 5xx / network error →
retryable (existing backoff); other 4xx → permanent failure → DEAD immediately (no retries), with a sanitized error.
Prod startup refuses `provider=logging` (ProfileGuard) — a production app that silently doesn't email is a bug.
The real-provider path stays **unverified until the owner supplies a Resend API key and a verified sending domain**.

**Expiry reminders (ledger).** A scheduled job (every 15 min, `app.reminders.enabled`) processes each organization
with `reminders_enabled = true` once per org-local day, after 07:00 org time:
- Candidates: CURRENT, non-REJECTED documents of ACTIVE vendors whose type is active and `has_expiration`.
- EXPIRING: for each configured offset `o` in `reminder_offsets_days` with `0 ≤ expiration_date − today ≤ o`, insert a
  `reminder` row `(document_id, 'EXPIRING', o, expiration_date)` — ON CONFLICT DO NOTHING.
- EXPIRED: `expiration_date < today` → `(document_id, 'EXPIRED', 0, expiration_date)` — once per expiration date.
- New ledger rows (inserted in this run) feed the digest; a renewed document (new expiration date) naturally restarts
  the thresholds. Re-runs and concurrent instances never duplicate (unique constraint).
- Reuse `RequirementStatusSql` / `ComplianceContextService` — no second implementation of "today" or of statuses.

**Daily digest.** After the ledger step, if the org has new ledger rows not yet digested, enqueue ONE
`COMPLIANCE_DIGEST` per recipient: OWNER/ADMIN members with a verified email. Idempotency key
`digest:{orgId}:{userId}:{orgLocalDate}`. Content: newly expired documents, newly crossed expiring thresholds (each
document listed once, with vendor, type, expiration date, days left), the current count of missing documents, and a
link to `{APP_BASE_URL}/dashboard`. Nothing is sent when there is nothing new. Ledger rows record `digested_at`.
Members opting in to digests is deferred (needs a preference model).

**Document request.** `POST /vendors/{id}/document-requests` (`VENDORS_WRITE`): vendor ACTIVE with an `email`
(else 422 `title: "Vendor has no email"`), `documentTypeId` = active type of the org (else 400 field). Enqueues
`DOCUMENT_REQUEST` to the vendor contact with `reply_to` = the requesting user's email; subject
"{Org name} requests your {Document type}". At most one request per (vendor, type) per org-local day: idempotency
key `docreq:{vendorId}:{typeId}:{orgLocalDate}`; a repeat → 409 `title: "Already requested today"`. Audit
`vendor.document_requested` (shows in vendor history). Rate limit 30/min per user. Response:
`{ requestedAt: string; recipientEmail: string }`. No upload link yet (vendor portal is Phase 14).

**Email activity.** `GET /notifications` (OWNER/ADMIN; tenant-scoped; newest first; default 25, max 100):
`NotificationView = { id; kind; recipientEmail; status: "PENDING"|"SENDING"|"SENT"|"FAILED"|"DEAD"; attempts;
lastError: string | null; createdAt; sentAt: string | null }`. Account emails (verification/reset — no org) never
appear. Never expose payloads or tokens.

**Migrations.** `reminder` table per DATABASE.md plus `digested_at timestamptz NULL`; any other column needed (e.g.
`organization.last_reminder_run_date`) — add and document in DATABASE.md.

**Implementation notes (Phase 6 backend) — IMPLEMENTED; differences/additions vs the contract above:**
- **Resend facts** (official docs, checked 2026-10-04): `POST https://api.resend.com/emails`, `Authorization: Bearer`, optional `Idempotency-Key`
  (1..256 chars, kept 24 h, replay returns the same response), body `from`, `to` (string or array), `subject`, `html`, `text`, `reply_to`
  (string or array); success **HTTP 200 `{ "id": "..." }`**. Errors: 400 `validation_error`/`invalid_idempotency_key`, 401 `missing_api_key`,
  403 (restricted/suspended key, unverified domain), 409 `concurrent_idempotent_requests` / `invalid_idempotent_request`, 422
  `invalid_parameter`/`missing_required_field`, 429 `rate_limit_exceeded`/`daily_quota_exceeded`/`monthly_quota_exceeded` (default 10 req/s per team),
  500 `application_error`, 503 `service_unavailable`. The docs do not publish an error-body schema; we only read an allowlisted `name` field.
  Classification: 2xx SENT (id stored in `provider_message_id`); 429, 5xx, 408, 409 concurrent (or unreadable 409), timeouts/network = retryable
  (existing backoff); every other 4xx (incl. 401/403/409 invalid_idempotent_request) = permanent, DEAD at once (`attempts = 1`). Timeouts: connect 5 s, read 10 s.
  `Idempotency-Key` = the notification id. Config: `EMAIL_PROVIDER=logging|resend` (`app.email.provider`, default logging), `RESEND_API_KEY`, `EMAIL_FROM`;
  prod refuses `logging`; the sender refuses to start without key/from. `EmailSender` is unchanged; permanent failures are signalled with `EmailDeliveryException(permanent=true)`.
  Never verified against the real service (needs the owner's key + domain).
- **Reminders**: scheduler every 15 min (`app.reminders.enabled`, `fixed-delay-ms`), per org once per org-local day at/after 07:00 (`organization.last_reminder_run_date`, see DATABASE.md V6).
  Candidates come from `RequirementStatusSql.REQ_CTE` (so they need a vendor requirement; PENDING-review documents are included, REJECTED excluded). EXPIRED rows use `req_status = 'EXPIRED'`.
  A digest lists only rows that still describe reality (document still CURRENT with that expiration date, not rejected, vendor active); a document appears once (EXPIRED wins over EXPIRING).
  If an org has no verified OWNER/ADMIN the rows are marked digested without sending. Subject: `VendorFlow: 2 documents expired, 3 expiring soon` / `VendorFlow: 3 documents expiring soon` (singular `1 document expired`).
  Digest idempotency key `digest:{orgId}:{userId}:{orgLocalDate}`; a second digest the same day (only possible via the e2e forced run) gets `...:2`.
  Digest payload (stored in `notification.payload`, no secrets): `{ organizationName, expired: [{vendorName, documentType, expirationDate}], expiring: [{vendorName, documentType, expirationDate, daysLeft}], missingCount }`.
- **Document request**: permission = `CONTENT_WRITE` (MEMBER, ADMIN, OWNER; VIEWER 403) — the contract's `VENDORS_WRITE` does not exist. Check order: 403, 429 (rate rule `document-request-user`, 30/min/user, counted first), 404 vendor, 400 `documentTypeId` (unknown/foreign/inactive/missing), 422 `Vendor is inactive`, 422 `Vendor has no email`, 409 `Already requested today`. Success 202 `{ requestedAt, recipientEmail }`.
  Email: `reply_to` = requester's email; subject `{Org name} requests your {Document type}`.
  Audit `vendor.document_requested` (entity `vendor`) metadata: `{ documentTypeId, typeCode, typeName, recipientEmail }`. **Vendor history** exposes it as
  `changes: { typeCode: {before:null, after:"W9"}, typeName: {before:null, after:"W-9"}, recipientEmail: {before:null, after:"a@b.com"} }` and `detail = "{typeName} - {recipientEmail}"`.
- **GET /notifications**: permission = OWNER/ADMIN (`MEMBERS_MANAGE`), others 403. Page envelope (`page` default 0, `size` default 25, clamped 1..100). Excludes EMAIL_VERIFICATION/PASSWORD_RESET (also by kind). INVITATION and all other org emails appear. `lastError` is e.g. `EmailDeliveryException: Resend HTTP 429 rate_limit_exceeded` (sanitized).
- **E2E only**: `POST /api/test/reminders/run` (profile `e2e`, loopback only, logged in, OWNER/ADMIN, CSRF header) runs the job now for the active org ignoring the 07:00/once-per-day gates → `{ ran, newLedgerRows, digestsEnqueued }`. Absent (404) in every other profile. The e2e mailbox message JSON is now
  `{ kind, to, subject, text, replyTo, links, receivedAt }` (`text` = plain-text body, `replyTo` nullable); `links` are still the URLs found in the text body (digest: `<APP_BASE_URL>/dashboard`). The e2e profile sets `app.reminders.enabled=false`.
### CSV (Phase 7)
- `GET  /vendors/export.csv?status=&compliance=&q=&category=` → `text/csv` download
- `GET  /vendors/import/template.csv` → header row + one example row
- `POST /vendors/import/preview` multipart `{ file }` → 200 `ImportPreview`
- `POST /vendors/import/{importId}/commit` → 200 `ImportResult`

### Phase 7 contract details — CSV (authoritative for backend + frontend)
**Library**: Apache Commons CSV (Apache-2.0, maintained) for RFC 4180 parsing/printing — quoted fields, embedded
commas/newlines and BOM are where hand-rolled parsers break. Log it in DECISIONS.md.

**Columns** (header names case-insensitive, trimmed; order free; unknown columns → preview error on row 1):
`company_name` (required), `contact_name`, `email`, `phone`, `category`, `notes`, `status` (`ACTIVE|INACTIVE`,
case-insensitive). Export adds read-only compliance columns: `compliance_status`, `missing`, `expired`, `expiring`,
`review_required`, `next_expiration` (ignored on import if present, so an export can be re-imported).

**Export** (`VENDORS_VIEW`, all roles): same filters as `GET /vendors` (default status=ACTIVE; `ALL` allowed), sorted
by company name, cap 10,000 rows (more → 422 asking to filter). UTF-8 **with BOM** (Excel), CRLF line endings,
`Content-Disposition: attachment; filename="vendors-YYYY-MM-DD.csv"`, `Cache-Control: no-store`. **CSV/formula
injection**: any cell whose first character is `=`, `+`, `-`, `@`, TAB or CR is prefixed with `'`. Audit
`vendor.exported` (filters + row count, no data). One query (reuse the vendor list SQL; no N+1).

**Import preview** (`ARCHIVE_AND_IMPORT` = OWNER/ADMIN): multipart `file`, ≤ 1 MB (413), extension `.csv` and content
must decode as UTF-8 (BOM optional) else 415/400; ≤ 2,000 data rows (else 422). Every row is validated with the SAME
rules as the vendor API (lengths, email, phone, `@PlainText`, notes may contain line breaks). Duplicate
`company_name` within the file (case-insensitive, trimmed) → error on every duplicate row. Matching by
`company_name` (case-insensitive) against the org's vendors decides the action:
- `CREATE` — no existing vendor; empty cells → null; default requirements attached (same as `POST /vendors`).
- `UPDATE` — existing vendor and at least one non-empty cell differs; **empty cells leave the existing value
  unchanged** (an import never erases data); `status` applied only when present.
- `UNCHANGED` — existing vendor, nothing differs.
- `ERROR` — any validation problem; `errors: [{ field, message }]` (field = column name).
The preview is stored server-side (`vendor_import` table: id, organization_id, created_by_user_id, status
`PREVIEWED|COMMITTED|EXPIRED`, parsed rows jsonb, summary, created_at, expires_at = +1 h). Nothing touches vendors.
```ts
type ImportRowAction = "CREATE" | "UPDATE" | "UNCHANGED" | "ERROR";
type ImportPreview = { importId: string; expiresAt: string;
  summary: { total: number; create: number; update: number; unchanged: number; error: number };
  rows: { rowNumber: number;            // 1-based data row (header excluded), as the user sees it in a spreadsheet
          companyName: string | null; action: ImportRowAction;
          changes: string[];            // UPDATE: names of fields that will change
          errors: { field: string; message: string }[] }[] };
type ImportResult = { created: number; updated: number; unchanged: number };
```
**Commit** (`ARCHIVE_AND_IMPORT`): import must belong to the active org (else 404), be `PREVIEWED`, not expired
(410 `title: "Import expired"`), and have zero ERROR rows (422). Already committed → 409. In ONE transaction:
re-check every row against the CURRENT database (a vendor created/renamed since the preview changes the outcome →
409 `title: "Data changed since preview"`, nothing applied — the user previews again), apply creates/updates, mark
`COMMITTED`. Audit one `vendor.created`/`vendor.updated` per affected vendor (metadata `source: "csv_import"`,
`importId`) plus one `vendor.imported` summary event. Rate limit: preview 10/min per user.
Expired previews are deleted by a periodic cleanup (keep it simple: during preview creation or a scheduled job).

**Implementation notes (Phase 7 backend) — IMPLEMENTED; differences/additions vs the contract above:**
- **Phase 7 security-fix additions**: export is limited to **10 per 10 minutes per user** (rule `vendor-export-user`, counted before the query): 429 `rate-limited` + `Retry-After`.  A cell longer than 10,000 characters makes its row an ERROR (`Value too long`; the value is dropped, never stored or echoed); ERROR rows keep each value truncated to  its column maximum. Unknown-column errors are capped at 20 on row 1 plus one summary error `{field:"header", message:"and N more unknown columns"}`; echoed column  names are cut to 50 characters. Commit re-validates every stored row with the vendor rules (any violation: 422 `Import has errors`, nothing applied), row-locks the  matched vendors before re-resolving, and after success clears the stored rows (keeps the summary).
- **Permissions**: the contract's names do not exist; real ones are `DATA_VIEW` (export: OWNER/ADMIN/MEMBER/VIEWER) and `ARCHIVE_AND_IMPORT`
  (preview, commit and **template**: OWNER/ADMIN; MEMBER/VIEWER get 403). The template needs the import permission because it is only useful there.
- **Export**: `GET /vendors/export.csv?status=&compliance=&q=&category=` uses the list endpoint's own parsing/defaults/SQL (bad filter values = 400
  `errors[{field}]` like the list). Sorted by company name, no `sort`/paging params. Columns, in order: `company_name, contact_name, email, phone, category,
  notes, status, compliance_status, missing, expired, expiring, review_required, next_expiration` (status = `ACTIVE|INACTIVE`, compliance_status =
  `COMPLIANT|ATTENTION|NON_COMPLIANT`, counts are integers, next_expiration = ISO date or empty). Over 10,000 rows: 422 `title: "Too many vendors to export"`.
  Response `Content-Type: text/csv; charset=utf-8`, `Content-Disposition: attachment; filename="vendors-YYYY-MM-DD.csv"` (date in the ORGANIZATION time zone),
  `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. Built in memory (a few MB at most; the DB connection is released before the download starts and
  errors can still be a normal problem response). Audit `vendor.exported` (entity `vendor`, no id) metadata `{ rowCount, status, compliance, category, q }`.
- **Template**: `GET /vendors/import/template.csv` -> BOM + CRLF, header `company_name,contact_name,email,phone,category,notes,status` and one example row;
  `Content-Disposition: attachment; filename="vendor-import-template.csv"`.
- **Formula-injection round trip (addition)**: export prefixes `'` to cells starting with `= + - @ TAB CR`; **import removes a leading `'` only when it is
  followed by one of those characters**, so `+1 (555) 010-0100` survives export -> edit -> import unchanged. Any other leading apostrophe is real data.
- **Preview response**: exactly the contract's `ImportPreview`. `expiresAt` = creation + 1 h. `rows` contains every non-empty data row (blank lines / rows of only commas
  are skipped but still numbered, so `rowNumber` matches the spreadsheet). `errors[].field` is the column name; two pseudo-fields exist: `"row"` (data in a cell with no
  header name or beyond the header width) and an unknown header name itself (see below).
- **File-level failures (no preview is stored)**, all RFC 9457 problems with `requestId`:
  | Case | Status | Notes |
  |---|---|---|
  | no `file` part / empty file | 400 `errors[{field:"file"}]` | |
  | extension not `.csv` (case-insensitive; the sanitized name is used) | 415 `title: "Unsupported file type"` | |
  | > 1 MB (declared or real length) | 413 `title: "File too large"` | |
  | not valid UTF-8 (strict decoder, never lenient) | 400 `title: "Invalid CSV file"`, detail says the file is not valid UTF-8 and how to save as CSV UTF-8 | BOM optional |
  | unparseable CSV (e.g. unterminated quote), empty file, header only, no data rows | 400 `title: "Invalid CSV file"` | detail never echoes file content |
  | header with more than 30 columns | 400 `title: "Invalid CSV file"`, detail names the limit | checked before any per-column work (a 1 MB header of unique names is cheap to reject) |
  | header has no `company_name`, or a duplicate column (case-insensitive) | 400 `title: "Validation failed"` with `errors[{field: <column>, message}]` | **deviation/choice**: the contract only specified unknown columns |
  | > 2,000 data rows | 422 `title: "Too many rows"` | exactly 2,000 is accepted |
  | rate limit: 10 previews/min per user (rule `vendor-import-preview-user`) | 429 + `Retry-After` | counted after authz, before any file work |
- **Unknown columns** (neither import nor read-only): reported as one error per unknown column on the FIRST row of `rows` (`errors[{field: <lower-cased header name>, message: "Unknown column"}]`),
  which makes that row an ERROR and blocks commit; the other rows keep their own action. Header names are trimmed and case-insensitive; order is free; the six read-only
  export columns are accepted and ignored.
- **Row rules** are the vendor API's: the CSV cells are put through `VendorRequest` (the same record, strip/blank-to-null normalization and Bean Validation annotations) so lengths,
  e-mail, phone, `@PlainText` and `notes` line-break rules cannot drift. Notes line breaks are normalized to LF. `status` (case-insensitive) must be `ACTIVE|INACTIVE`.
  Messages are the Bean Validation messages (e.g. `must not be blank`, `size must be between 0 and 120`) except status (`must be ACTIVE or INACTIVE`) and duplicates
  (`Appears more than once in this file (rows 2, 5)`, on every duplicate row).
- **Actions**: matching is by trimmed, case-insensitive `company_name` against the organization's vendors (ALL statuses). A difference in letter case of the name only IS an UPDATE
  (`changes: ["company_name"]`). `changes` lists column names (`company_name, contact_name, email, phone, category, notes, status`, in that order). Line-break style in notes is not a change.
- **Commit**: order of checks: 403, 404 (foreign or unknown id), 409 `title: "Import already committed"` (checked before expiry), 410 `title: "Import expired"`,
  422 `title: "Import has errors"`, 409 `title: "Data changed since preview"` (every row is re-resolved against the current data under the import-row lock; action, matched vendor
  or the list of changed columns differing from the preview = nothing applied; a vendor created by someone else between the re-check and the insert gives the same 409).
  A malformed id (not a UUID) is 400. Any OWNER/ADMIN of the organization may commit a preview made by another one. Creates use the same code as `POST /vendors`
  (default requirements, audit `vendor.created`); updates the same code as `PUT /vendors/{id}` plus `status` in the same `vendor.updated` event (`changes.status` before/after, so the
  vendor history shows it). Audit metadata of both gets `source: "csv_import"`, `importId`; the summary event is `vendor.imported` (entity `vendor_import`, id = import id) with
  `{ total, created, updated, unchanged }`. UNCHANGED rows write nothing.
- **Retention**: previews hold vendor contact data. Rows are cleared when committed; expired previews are deleted 24 h after expiry (so "expired" can answer 410 for a day) when any new
  preview is created and by an hourly scheduled purge. The `EXPIRED` status value exists in the CHECK but is not written (expiry is derived from `expires_at`).
- **Frontend-visible differences from the contract text**: (1) header problems (missing `company_name`, duplicate column) are a 400 problem with `errors[]`, not a preview;
  (2) permission names (above); (3) template is import-permission only; (4) the `'` un-prefixing on import. Everything else matches the contract types.

### Billing (Phase 8)
Billing always concerns the caller's ACTIVE organization (from `TenantContext`; there is no organization id in any URL, and one sent in a body/query is ignored).

- `GET /billing/subscription` (any member) →
  `{ status: "TRIALING"|"ACTIVE"|"PAST_DUE"|"CANCELED"|"INCOMPLETE"|"UNPAID", plan: "standard", trialEndsAt: ISO|null, currentPeriodEnd: ISO|null, cancelAtPeriodEnd: bool, readOnly: bool, canManage: bool }`.
  `canManage` = the caller may use checkout/portal (OWNER). `readOnly` is computed per request by the single pure rule `SubscriptionAccess`:
  ACTIVE and PAST_DUE (grace) are writable; TRIALING is writable only while `trialEndsAt` is in the future (app clock; missing date = read-only); CANCELED, UNPAID, INCOMPLETE are read-only.
  `readOnly` is always `false` when `vendorflow.billing.enabled=false` (local/test default).
- `POST /billing/checkout-session` → `{ url }` (Stripe Checkout, mode subscription, price `STRIPE_PRICE_ID`; success/cancel = `APP_BASE_URL/settings/billing?checkout=success|canceled`).
  OWNER only (`BILLING_MANAGE`; ADMIN is the lowest denied role, 403). 409 `title: "Already subscribed"` when the organization already has an ACTIVE/PAST_DUE/TRIALING Stripe subscription (use the portal).
- `POST /billing/portal-session` → `{ url }` (Stripe Customer Portal, return URL `APP_BASE_URL/settings/billing`). OWNER only.
  Both create the Stripe customer lazily (idempotency key per organization) and store `stripe_customer_id`.
  Order of checks: 401/403 (authz) → 503 → 409 → provider call. 503 `title: "Billing not configured"` when billing is disabled or `STRIPE_SECRET_KEY`/`STRIPE_PRICE_ID` are missing (never a fake URL).
  502 `title: "Billing provider unavailable"` when Stripe cannot be reached (no provider detail in the body).
- `POST /webhooks/stripe` — no session, CSRF-exempt, rate limit rule `stripe-webhook` (600/min/IP). Raw body (max `vendorflow.billing.webhook-max-body-bytes`, 256 KiB → 413) verified with
  `Stripe-Signature` (HMAC, tolerance 300 s, past timestamps only as in the SDK). Responses: 200 `{ "received": true }` (also for duplicates and ignored/unknown-customer events),
  400 `title: "Invalid webhook"` (bad/missing signature, stale timestamp, unparsable body; never says which), 503 `title: "Billing not configured"` (no `STRIPE_WEBHOOK_SECRET`),
  500 (processing failed: Stripe retries; the event row is FAILED and is reprocessed by the retry).
  Handled: `checkout.session.completed`, `customer.subscription.created|updated|deleted`, `invoice.payment_failed`; every other type is acknowledged and recorded.
  Organization is resolved only from the stored `stripe_customer_id` (a completed checkout whose customer is not stored yet may match an existing row WITHOUT a customer through `client_reference_id`);
  state is always re-read from Stripe by subscription id (`BillingGateway.retrieveSubscription`), so out-of-order and replayed events converge. A late event about an old ended subscription never overrides a newer one.
  Audit: `billing.trial.started`, `billing.customer.created`, `billing.checkout.started`, `billing.portal.opened`, `billing.checkout.completed`, `billing.subscription.created|updated|deleted`, `billing.payment.failed`
  (webhook events carry `{stripeEventId, before, after}`, no actor).
- **Read-only mode (402)**: while `readOnly`, every mutating request (POST/PUT/PATCH/DELETE) on `/api/v1/**` answers
  `402 { type: ".../problems/subscription-inactive", title: "Subscription inactive", status: 402, detail, requestId }` (one MVC interceptor, `ReadOnlyGuardInterceptor`, runs before the controller, so also before body validation and role checks).
  Always allowed: all GETs (lists, downloads, CSV export/template), `/auth/**`, `/billing/**`, `/webhooks/**`, `/session/**` (switch organization), `/invitations/**` (accept), `/me/**`, and `DELETE /organization/members/{id}` (leave/offboard).
  Requests without an active organization are not blocked by this guard. Scheduled reminders/digests skip read-only organizations (they resume when it is active again).

### Vendor portal (Phase 14) **(implemented, backend)** — ADR-0011
Vendors upload through a link; no vendor account. Staff create and revoke links; the public endpoints below take the raw token in the request header `X-Portal-Token` (never in the URL, so it cannot reach proxy/platform access logs). A missing or malformed header is the same 404 as an unknown token; a token in a query string or path segment is ignored.

**Staff endpoints** (session + CSRF as usual; tenant from the session; a vendor/link of another organization is 404):
- `POST /vendors/{vendorId}/upload-links` — `CONTENT_WRITE` (MEMBER+; VIEWER 403). Body
  `{ documentTypeIds: uuid[1..20], expiresInDays?: 1..30 (default 14), maxUploads?: 1..50 (default 20), maxTotalMb?: 1..500 (default 100; total bytes the link may upload), sendEmail?: boolean (default false) }` → **201**
  `{ link: UploadLink, url: string, emailQueued: boolean, emailSkippedReason: "EMAIL_NOT_DELIVERABLE"|null }`. `url` = `{APP_BASE_URL}/portal#token=<raw token>` and is the ONLY time the raw token is ever returned
  (response is `no-store`; it is not stored, audited or logged; lost link = create a new one). Rules: every id must be an ACTIVE document type that is a requirement of this vendor, else 400
  `errors[].field = documentTypeIds`; vendor must be ACTIVE (422 `vendor-inactive`); `sendEmail=true` needs a vendor email (422 `vendor-no-email`) and queues the existing `DOCUMENT_REQUEST` email with the portal link
  (idempotency key `portallink:{linkId}`). Rate limit `portal-link-create-user` 30/min per user. Audit `portal_link.created` (entity `vendor_upload_link`; metadata vendorId, documentTypeIds, expiresAt, maxUploads, emailed; never the token).
- `GET /vendors/{vendorId}/upload-links?page=0&size=25` — `DATA_VIEW` (VIEWER+) → page of `UploadLink`, newest first.
- `POST /vendors/{vendorId}/upload-links/{linkId}/revoke` — `CONTENT_WRITE` → 200 `UploadLink`. Idempotent (a second revoke returns the same link and writes no second audit row).
  Works while the organization is read-only (security action, excluded from the 402 guard like member removal). Audit `portal_link.revoked`.
```ts
type UploadLink = { id: string; vendorId: string; documentTypes: { id: string; name: string }[];
  status: "ACTIVE" | "EXPIRED" | "REVOKED" | "EXHAUSTED";   // precedence: REVOKED > EXPIRED > EXHAUSTED > ACTIVE
  createdBy: { fullName: string } | null; createdAt: string; expiresAt: string; revokedAt: string | null;
  lastUsedAt: string | null; useCount: number; maxUploads: number;
  maxTotalBytes: number; usedBytes: number };
```

**Public endpoints** (exactly `GET /portal/link` and `POST /portal/link/documents`; no session, no cookies read; CSRF-exempt only for the upload POST; `Cache-Control: no-store`, `Referrer-Policy: no-referrer`):
- `GET /portal/link` (header `X-Portal-Token: <raw token>`) → 200
  ```ts
  type PortalInfo = { organizationName: string; vendorName: string; expiresAt: string; remainingUploads: number;
    acceptingUploads: boolean;   // false while the organization is read-only (subscription inactive) or remainingUploads is 0
    documentTypes: { id: string; name: string; hasExpiration: boolean;
      status: "MISSING" | "OK" | "EXPIRING" | "EXPIRED" | "REVIEW_REQUIRED"; expirationDate: string | null }[] };
  ```
  Nothing else is exposed (no ids of organization/vendor, no other vendors/types/documents/staff). `REVIEW_REQUIRED` = received, waiting for staff approval.
- `POST /portal/link/documents` (header `X-Portal-Token`) multipart `file`, `documentTypeId`, `issueDate?`, `expirationDate?` → **201**
  `PortalUploadResult = { documentType: { id, name }, originalFilename, status: "PENDING_REVIEW", uploadedAt, remainingUploads }`.
  Same pipeline and validation order as the staff upload (413 size/quota, 415 extension/magic bytes, 400 type/dates, 422 `file-rejected`, 503 scanner unavailable = fail closed); `documentTypeId` must be in the link's set and still a requirement of the vendor, else 400 `errors[].field = documentTypeId`.
  The document is review `PENDING`, `source = PORTAL`, no uploader user; a vendor cannot approve. Audit `document.uploaded` (metadata `source: "PORTAL"`, `linkId`).
  **Never displaces an approved document (M1):** if the requirement's CURRENT document is `APPROVED` and not expired, the upload is stored as state `CANDIDATE` (audit metadata `candidate: true`): the approved document stays CURRENT and keeps counting for compliance, the vendor still receives `PENDING_REVIEW`. Otherwise (no current, `PENDING`, `REJECTED`, or approved-but-expired) the new document becomes CURRENT and supersedes the old one as for any upload (audit `document.superseded` with `source: "PORTAL"`). A newer upload (portal or staff) supersedes a pending candidate: at most one candidate per (vendor, type).
- Errors: **404** `type .../portal-link-invalid`, detail "This link is invalid or has expired." for unknown, malformed, expired, revoked links AND links of inactive vendors (identical body apart from requestId/instance);
  **402** `.../portal-uploads-unavailable` (organization read-only; GET still works); **422** `.../portal-upload-limit` (the link's `maxUploads` or its byte budget `maxTotalBytes` is spent); **429** with `Retry-After`; **413** `.../file-too-large` when the declared `Content-Length` exceeds the multipart request limit (17 MB).
  A servlet filter (`PortalUploadGuardFilter`) answers the 404 (missing / not 43-char base64url / unknown / unusable token) and the 413 BEFORE the multipart body is parsed, so nothing is buffered or written for a bad request; Tomcat `max-part-count` = 10.
- Rate limits: per IP `portal-view` (GET) 60/min, `portal-upload` (POST) 20/min; per link `portal-link` 60/min (GET+POST together) and `portal-link-upload` 10/min (upload ATTEMPTS, successful or not, counted once the token validated).
- Staff e-mail: each upload enqueues `PORTAL_UPLOAD` to every verified OWNER/ADMIN, at most one per recipient per link per UTC day.
- `DocumentSummary` (staff API) gains `source: "STAFF" | "PORTAL"`; `uploadedBy` is `null` for portal uploads.
- Frontend (later task): public page `/portal` reads `location.hash` (`#token=...`), clears it with `history.replaceState`, calls the endpoints above; the vendor detail page gets "Send upload link" + link list.

### Automated chasing (Phase 15) **(backend implemented)** — ADR-0012

Org settings (organization-scoped, from `TenantContext`; no org id in the URL):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/api/v1/organization/chasing` | `DATA_VIEW` (any role) | defaults when never saved |
| PUT | `/api/v1/organization/chasing` | `ORG_SETTINGS_MANAGE` (OWNER/ADMIN) | full replace; 402 in read-only orgs |

`ChasingSettings` = `{ enabled: bool (default false), cadenceDays: int 3..30 (7), maxAttempts: int 1..10 (4), leadDays: int 7..90 (30), sendHourLocal: int 0..23 (9, org time zone), ccStaff: bool (false) }`.
PUT requires ALL fields (400 `validation-error` with field violations otherwise). Audit `chasing.settings.updated` with before/after of the changed fields (only when something changed).

Per vendor (the vendor must belong to the caller's org, else 404):

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/vendors/{id}/chasing` | `DATA_VIEW` |
| PUT | `/api/v1/vendors/{id}/chasing` body `{ "paused": bool }` | `CONTENT_WRITE` (MEMBER+) |
| GET | `/api/v1/vendors/{id}/chases?page&size` | `DATA_VIEW` |

`VendorChasingState` = `{ paused: bool, pausedReason: "MANUAL"|"OPT_OUT"|null, lastChasedAt: instant|null, attempts: int, maxAttempts: int, nextChaseAt: instant|null, status: "IDLE"|"ACTIVE"|"EXHAUSTED"|"PAUSED"|"NO_EMAIL" }`.
Status precedence: PAUSED, NO_EMAIL, then IDLE unless (vendor ACTIVE, org chasing enabled, >= 1 deficient requirement), then EXHAUSTED when `attempts >= maxAttempts`, else ACTIVE.
`attempts` is the current episode's count (0 when nothing is deficient). `nextChaseAt` is non-null only for ACTIVE: `max(now, max(today, lastChasedLocalDate + cadenceDays) at sendHourLocal in the org zone)`.
PUT returns the new state. `paused:false` on a vendor with `pausedReason = OPT_OUT` -> 422 `vendor-opted-out`. Audit `vendor.chasing.paused` / `vendor.chasing.resumed` (only on a real change; repeating is a 200 no-op).
Deviation from the first draft: the state is NOT embedded in `VendorDetail` (it would create a vendor -> chasing -> portal -> vendor package cycle); the frontend calls `GET /vendors/{id}/chasing`.

`GET /vendors/{id}/chases` -> `PageResponse<Chase>`, newest first (max size 100): `Chase` = `{ id, date: "yyyy-MM-dd" (org-local), attempt: int, types: [{ id, name, status: MISSING|EXPIRED|EXPIRING }], createdAt, linkStatus: ACTIVE|EXHAUSTED|EXPIRED|REVOKED|null, emailStatus: PENDING|SENT|FAILED|DEAD|null }`.
Never contains a token, link URL or recipient address.

Public opt-out (no session, no cookies read; the token is in header `X-Portal-Token`, like the portal; per-IP rate rule `chasing-opt-out` 20/min; CSRF-exempt for POST only because no ambient credential is used):

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/v1/portal/chasing/opt-out` | NEVER changes state. 200 `{ organizationName, vendorName, optedOut: bool }` |
| POST | `/api/v1/portal/chasing/opt-out` (no body) | Pauses chasing for the vendor (`pausedReason = OPT_OUT`), notifies staff once, audits `vendor.chasing.opted_out`. 200 `{ organizationName, vendorName, optedOut: true }`; a repeat is the same 200 with no second audit row or notice |

RFC 8058 one-click target of the `List-Unsubscribe` header (token in the PATH, because a header can only carry a URL; own per-IP rule `chasing-one-click` 500/min (mail providers share IPs; header-token GET/POST keep 20/min), same identical 404, `no-store`):

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/v1/portal/chasing/one-click/{token}` (body `List-Unsubscribe=One-Click` ignored) | Same effect and response as the header-token POST (opt-out, global suppression, queued chase cancelled, staff notified once). CSRF-exempt: exact POST pattern, no cookie or session read |
| GET (and every other method) | same path | **405**, never changes state (link scanners/prefetch) |

The problem `instance` of 404/405/429 on this path shows `/api/v1/portal/chasing/one-click/{token}` (masked). A reverse proxy may log the path: see docs/SECURITY.md (proxy caveat).

Bad, unknown, malformed or expired (180 days) token: identical 404 `https://vendorflow.app/problems/chasing-opt-out-invalid`. Responses are `no-store`, `no-referrer`. The email links to `{app.base-url}/portal/unsubscribe#token=...` (frontend page, to be built).

Hardening (security review): chasing can only be switched ON by a verified OWNER/ADMIN (PUT `/organization/chasing` with `enabled:true` -> 422 `email-not-verified` for an unverified user). Pausing a vendor or an opt-out cancels that vendor's queued `VENDOR_CHASE` emails (DEAD, tokens scrubbed). An opted-out address is globally suppressed: manual `POST /vendors/{id}/document-requests` -> 422 `address-unsubscribed`; `POST /vendors/{id}/upload-links` with `sendEmail:true` -> 201 with `emailQueued:false` and `emailSkippedReason:"ADDRESS_UNSUBSCRIBED"` (the link exists; `emailSkippedReason` is null otherwise). Limits: 200 chases per organization per local day (`app.chasing.max-per-org-per-day`), 1 chase per address per 24 h across organizations. No chase is sent while `vendorflow.mail.postal-address` is blank.

Emails (all through the outbox; tokens scrubbed after SENT/DEAD): `VENDOR_CHASE` to the vendor (types with status/date, "reminder n of max", upload link `{base}/portal#token=...`, opt-out link) with footer "sent by VendorFlow on behalf of <org>" + postal address, Reply-To = first verified OWNER/ADMIN, headers `List-Unsubscribe` / `List-Unsubscribe-Post` and `CHASING_STAFF_NOTICE` to verified OWNER/ADMIN (`SUMMARY` when ccStaff, `EXHAUSTED`, `OPTED_OUT`). Both appear in `GET /notifications` activity.
