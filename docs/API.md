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
- `GET   /invitations/{token}` (public: org name + role only) · `POST /invitations/accept` `{ token, fullName?, password? }`

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
