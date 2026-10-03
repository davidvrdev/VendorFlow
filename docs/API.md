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
  invitation lookup/accept 20/min, resend-verification 3/min → **429** with `Retry-After`.

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
- `POST   /vendors/{id}/document-requests` `{ documentTypeId }` → 202 (Phase 6)

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
type DocumentState = "CURRENT" | "SUPERSEDED" | "ARCHIVED";
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
- `GET  /vendors/{vendorId}/documents?includeHistory=false|true` → `DocumentSummary[]` (CURRENT only by default;
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
- Review: only CURRENT documents (else 409 "Document is not current"). `REJECTED` requires a note (1–1000 chars).
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

### CSV (Phase 7)
- `GET  /vendors/export.csv`
- `POST /vendors/import/preview` multipart `{ file }` → `{ importId, rows: [...], errors: [...], summary }`
- `POST /vendors/import/{importId}/commit` → applies (only if preview had no errors)

### Billing (Phase 8)
- `GET  /billing/subscription`
- `POST /billing/checkout-session` → `{ url }`
- `POST /billing/portal-session` → `{ url }`
- `POST /webhooks/stripe` (no session, no CSRF; signature-verified)
