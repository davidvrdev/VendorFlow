# ADR-0011: Vendor portal with tokenized upload links (no vendor account)

- Status: Accepted · Date: 2026-10-04 (Phase 14, backend + contract)

## Context
Vendors should be able to send a requested document without creating an account. Staff create a link scoped to ONE
vendor and a subset of that vendor's required document types; the vendor opens it from an email and uploads files.
The link is the only credential, so it must be unguessable, short-lived, revocable, rate-limited and must expose
nothing beyond what the vendor needs.

## Decision
- **Capability URL, not an account.** `vendor_upload_link` stores only `SHA-256(token)` (256-bit URL-safe random
  token from `TokenGenerator`, same as invitations). The raw token exists in (a) the creation response shown once to
  the staff member who created it, (b) the outbox payload until the email is SENT (the dispatcher scrubs the `token`
  key, as for every other emailed token), (c) the vendor's email/browser. It is never persisted elsewhere, never audited, never logged.
- **Link lifetime**: default 14 days, max 30. `max_uploads` default 20 (max 50): the count of successful uploads. Byte budget `max_total_bytes` default 100 MB (max 500 MB), `used_bytes` claimed in the same atomic UPDATE (staff set it with `maxTotalMb`).
- **Public API under `/api/v1/portal/link`** (`GET`, and `POST .../documents`): the raw token travels in the request header `X-Portal-Token`, never in the URL path or query, so it cannot appear in reverse-proxy, Render or Next.js access logs (revised from an earlier path-token design after the director's follow-up). The user-facing URL keeps the token in the fragment (`/portal#token=...`, never sent to servers or Referer); the page reads it and sends the header. No session and no cookies are read or needed. Request headers are not logged by the application (a test captures all log output and asserts the token is absent).
- **No tenant context.** The organization comes from the verified link row (the capability determines the tenant, the same
  pattern as invitation accept). Every query after the lookup is scoped by that organization id. The portal ignores any
  session cookie a staff member's browser may send, and the read-only (402) interceptor is excluded for these paths because it
  would otherwise apply the staff member's organization to a vendor's request; the portal does its own read-only check on the link's organization.
- **No oracle.** Unknown, malformed, expired, revoked links and links of inactive vendors answer the same `404`
  (`portal-link-invalid`). A valid link whose upload budget is spent answers 422 (the holder may know that).
- **CSRF**: exempt only for `POST /api/v1/portal/*/documents`. The endpoints use no ambient credentials (no cookie), so
  there is nothing for a cross-site request to ride on.
- **Same upload pipeline.** `DocumentUploadService` was refactored into one `process(...)` used by the staff endpoint and the
  portal: size, extension allowlist, magic bytes, document type (must be in the link's set AND still a requirement of the vendor),
  dates, quota, scanner (fail closed), server-generated key, supersede, audit. The document is created `CURRENT` + review `PENDING`
  (a vendor can never approve), `uploaded_by_user_id` NULL, `document.source = 'PORTAL'`, `upload_link_id` = the link.
  The link use is claimed with one atomic `UPDATE ... WHERE use_count < max_uploads AND revoked_at IS NULL AND expires_at > now` inside
  the same transaction as the document insert, so a revoked/expired link or an exhausted budget can not slip a document in
  and a failed upload does not consume budget.
- **Rate limits**: per IP (`portal-view` 60/min, `portal-upload` 20/min, by `RateLimitFilter`) and per link (`portal-link`
  60/min over view+upload, keyed by link id and only after a successful lookup so unknown tokens cannot grow the map);
  staff creation `portal-link-create-user` 30/min per user.
- **Staff notification**: one `PORTAL_UPLOAD` email per recipient (verified OWNER/ADMIN) per link per day (idempotency key
  `portalupload:{link}:{user}:{yyyy-MM-dd}`), enqueued in the upload transaction. Not one per file: a vendor uploading 10 files must not send 10 emails.
- **Email**: the existing `DOCUMENT_REQUEST` kind is extended: when the payload carries `portal: true` (and `token`), the template renders the
  portal link and the requested types instead of "reply with the attachment".
- **No displacement of approved documents (revised after security review, M1)**: a portal upload over a CURRENT document that is
  APPROVED and not expired is stored as a new document state `CANDIDATE` (review PENDING). The approved document stays CURRENT and
  keeps counting; compliance SQL and calculator read `state = 'CURRENT'` only, so no compliance code changed. Staff approval of the
  candidate supersedes the old document and promotes the candidate atomically (vendor row locked first, like uploads); rejection leaves
  the old one CURRENT and archives the candidate. If the current document is missing/PENDING/REJECTED/expired the upload supersedes
  it as before (audit `document.superseded` carries `source: PORTAL`). At most one candidate per (vendor, type): a newer upload
  supersedes it. The staff API exposes the candidate as `DocumentSummary.state = CANDIDATE` and `VendorDetail.requirements[].pendingReplacement`.
- **Pre-parse guard (M3)**: `PortalUploadGuardFilter` (before Spring Security, after the per-IP limiter) rejects bad tokens (404, identical
  body) and oversized declared bodies (413) before multipart parsing; Tomcat `max-part-count` pinned. Per-link `portal-link-upload` 10/min counts failed attempts too (L1).
- **Email variant (L4)**: the portal DOCUMENT_REQUEST is selected by payload flag `portal: true`, not by the presence of `token`.

## Alternatives rejected
- Vendor accounts/magic-link login: more friction, a second identity system, out of scope for the MVP.
- Token in a header only: would need a custom client and cannot be a simple link-to-API proxy; fragment + path is the common pattern.
- Storing the raw token (to re-show the link): turns a DB read leak into an upload capability. Staff can create a new link instead.
- Link types as `uuid[]`: no composite foreign keys. A child table `vendor_upload_link_type` keeps the tenant FK rule.
- Notification per uploaded file: spam vector.

## Consequences
New feature package `com.vendorflow.portal`; new dependencies on identity (TokenGenerator), vendor, document, compliance,
organization, billing, notification, audit (no cycle: nothing depends on portal). Migration V11. Frontend: public page `/portal`
(reads the fragment, GET the info, uploads) and a "Send upload link" action on the vendor page: separate task.
