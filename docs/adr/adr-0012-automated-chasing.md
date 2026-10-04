# ADR-0012: Automated vendor chasing (scheduled follow-ups with a fresh portal link)

- Status: Accepted · Date: 2026-10-04 (Phase 15, backend + contract)

## Context
Staff should not have to remember to chase vendors. VendorFlow emails vendors that have missing, expired or soon-expiring
required documents, on a cadence, each email carrying a fresh portal upload link (ADR-0011), until the vendor is compliant
or the attempts are used up. It is email to third parties, so it is opt-in per organization and must be provably non-spammy.

## Decision
- **Opt-in, per organization.** `chasing_settings` (one optional row per org; no row = defaults, `enabled = false`).
  Per-vendor pause in `vendor_chasing`. Read-only (402) organizations are never chased (same rule as reminders).
- **Scheduler**: `@Scheduled` cron (minute 5 of every hour), `app.chasing.enabled`. Per organization the run takes the transaction-scoped
  advisory lock `pg_try_advisory_xact_lock(hashtextextended('chasing-org:' || id, 0))`: a concurrent tick or instance skips the org
  instead of waiting (ADR-0006, no ShedLock). No row lock on `organization` (review M1): a `FOR UPDATE` there blocks every
  child-table insert of the tenant (vendor, upload, opt-out take FOR KEY SHARE through their FKs) for the whole run; even
  `FOR NO KEY UPDATE` would block organization edits. The lock transaction only holds the lock; the work runs in its own transactions (see Batches). "At the org's sendHour" means *at or after* `sendHour` in the org time zone, so a missed tick is
  caught up later the same local day; idempotency makes that safe.
- **Idempotency**: ledger `vendor_chase` with `UNIQUE (vendor_id, local_date)`; the row is inserted first (`ON CONFLICT DO NOTHING`);
  0 rows = already chased today = nothing else happens. The outbox key `chase:{vendorId}:{localDate}` is a second guard.
- **Deficiency** comes from the shared `RequirementStatusSql.REQ_CTE`, with the window bind set to the org's chasing `leadDays`
  (so EXPIRING means "within leadDays"). Deficient = MISSING, EXPIRED, EXPIRING. REVIEW_REQUIRED is not deficient (the vendor already
  uploaded; the ball is with staff). One query per organization; no per-vendor deficiency queries.
- **Episode**: attempts are counted per episode. An episode ends (attempts and the "exhausted notified" flag reset) at the first tick
  at/after sendHour where the vendor has no deficient requirement (or is no longer ACTIVE).
- **Due** = vendor ACTIVE, has email, not paused, deficient, `attempts < maxAttempts`, and `lastChasedLocalDate + cadenceDays <= today`
  (the first chase of an episode is due immediately unless the vendor was chased within `cadenceDays`: the last chase date survives an
  episode reset, so cadence also spaces episodes).
- **Exhausted**: when a vendor with `attempts >= maxAttempts` is due again (a full cadence after the last attempt) and still
  deficient, staff are notified ONCE per episode (`exhausted_notified_at`) with one email per verified OWNER/ADMIN per day
  listing the vendors. The status API reports `EXHAUSTED` as soon as attempts reach the maximum.
- **One email per recipient per local day**: before chasing, one query loads the lower-cased recipients of `DOCUMENT_REQUEST` and
  `VENDOR_CHASE` notifications created in the org's current local day (manual document requests and portal-link emails included) and
  the same set is extended as chases are enqueued. A vendor whose address is in the set is skipped without consuming an attempt and
  is retried the next day. The inverse (a staff member manually requesting after the chase) is NOT blocked: it is an explicit human
  action (documented risk).
- **Links**: through `PortalLinkService.issueSystemLink` (no tenant context, `created_by_user_id` NULL, default budgets): types = the
  deficient ones, expiry = `cadenceDays + 7` days. The previous chase link of every chased vendor (`vendor_chasing.last_link_id`)
  is revoked in ONE statement per run (`PortalLinkService.revokeSystemLinks`), so a vendor has at most one live chase link. The
  audit row is `vendor.chased` (metadata: attempt, chase id, link id, superseded link id, type ids; never a token); there are no
  per-link audit rows, to keep a 500-vendor run to a handful of statements per vendor.
- **Opt-out (CAN-SPAM friendly)**: each chase has its own opt-out token (256-bit, only the SHA-256 stored in
  `vendor_chase.opt_out_token_hash`; valid 180 days from the chase, independent of the upload link, so an old email keeps working).
  The email links to the frontend page `/portal/unsubscribe#token=...` (fragment: never sent to servers or Referer). The page calls
  public `GET /api/v1/portal/chasing/opt-out` (info, no state change, safe for email scanners and prefetch) and
  `POST /api/v1/portal/chasing/opt-out` (pauses chasing with `paused_reason = OPT_OUT`). Token in header `X-Portal-Token`, like the portal.
  Bad/unknown/expired token: identical 404 `chasing-opt-out-invalid`. Repeating the POST is a 200 and writes no second audit row/notice.
  Opt-out works for read-only organizations and inactive vendors. Staff **cannot resume** an opted-out vendor through the API (422
  `vendor-opted-out`); the vendor can still receive manual requests from staff.
- **Staff notices** share one notification kind `CHASING_STAFF_NOTICE` (payload `event` = `SUMMARY` | `EXHAUSTED` | `OPTED_OUT`):
  `ccStaff = true` yields ONE daily summary per OWNER/ADMIN listing who was chased (names and types only, never a link or token: a
  copy of the raw link would be a second capability); `EXHAUSTED` and `OPTED_OUT` are always sent. The vendor mail is `VENDOR_CHASE`.
- **Secrets**: the raw portal token and the raw opt-out token live in the outbox payload (`token`, `optOutToken`) until the row is
  SENT/DEAD (both keys scrubbed, also by the undelivered-token sweep). Never audited or logged.
- **Audit** (actor NULL for system events): `chasing.settings.updated`, `vendor.chasing.paused`, `vendor.chasing.resumed`,
  `vendor.chased`, `vendor.chasing.opted_out`.
- **Package** `com.vendorflow.chasing` (api/application). It uses JDBC (`JdbcClient`) for its three tables, like the reminder job, and
  calls portal, vendor, organization, billing, notification and audit through their application services. No other feature depends
  on it, so no cycle. The vendor detail endpoint is NOT extended with chasing state (vendor -> chasing -> portal -> vendor
  would be a cycle): the state is `GET /vendors/{id}/chasing`.

## Alternatives rejected
- ShedLock/Quartz: ADR-0006 already chose row locks.
- Chasing from `ReminderService`: different cadence/recipients, and the reminder ledger is per document threshold.
- Stateless HMAC opt-out token: needs a signing secret and cannot be revoked/expired per chase without a table anyway.
- Opt-out via GET link: email scanners and link prefetchers would unsubscribe vendors silently.
- Copying staff on the vendor email: would duplicate the upload capability into more mailboxes.

## Consequences
Migration V12. No new external dependency. Frontend (separate task): settings page, vendor chasing card, activity list,
public `/portal/unsubscribe` page. The email sender gained optional custom headers (see Review hardening).

## Review hardening (security review of the first implementation; no critical/high, all medium/low fixed)
Supersedes the corresponding parts above where they differ.
- **M1 locking**: advisory lock instead of `FOR UPDATE SKIP LOCKED` on `organization` (see Decision). Tested: while the lock is held, another run of the organization is skipped, and a vendor insert, a `vendor_chasing` insert and an `organization` update from other connections complete.
- **M2 batches**: after one read-only preparation transaction, due vendors are processed in batches of `app.chasing.batch-size` (50), one transaction per batch (state lock + re-check, ledger claim, link, outbox, state, audit, ONE revoke statement). A batch that throws is rolled back as a whole and retried vendor by vendor; a vendor that still fails is logged (`Chasing vendor failed: organizationId=.. vendorId=.. error=<class>`), counted (`vendorflow.chasing.vendor.failures`, Micrometer, actuator is present) and skipped; the others stay committed (poison-vendor test with a DB trigger). Savepoints were rejected: Hibernate's persistence context keeps the failed entities after a rollback to a savepoint. Staff summary/exhausted notices run in a final transaction; if the JVM dies between batches those notices for the already committed vendors are lost (idempotency keys suppress a second summary the same day). Accepted.
- **M3 suppression and caps**: global table `email_suppression` (SHA-256 of the lower-cased address, written on opt-out with the address the chase was sent to, `vendor_chase.recipient_hash`). Checked by the scheduler (bulk), manual document requests (422 `address-unsubscribed`), portal-link emails (link created, no email, `emailSkippedReason`), and by the dispatcher (DEAD). Caps: per organization per local day (`app.chasing.max-per-org-per-day`, 200) and per recipient one chase per 20 h (and per UTC day, unique index) across organizations (bulk check per run + unique index `(recipient_hash, UTC day)`: the index, not a per-vendor read, closes the race, so reads per run stay constant). Enabling chasing needs a verified OWNER/ADMIN (422 `email-not-verified`). Footer: "sent by VendorFlow on behalf of <org>".
- **M4 CAN-SPAM**: postal address from `vendorflow.mail.postal-address`; chosen safe rule: while blank no chase is sent (one WARN per process, `RunResult.blocked = postal-address-missing`) instead of a ProfileGuard startup refusal, because chasing is opt-in per organization and a hard startup failure would take the whole API down for a mail setting. Reply-To = first verified OWNER/ADMIN by address (organizations have no contact address; with none, no chase: `no-staff-contact`). `EmailMessage`/`RenderedEmail` carry optional headers; `ResendEmailSender` maps them to Resend's `headers` object. Chase mails set `List-Unsubscribe: <{base-url}/api/v1/portal/chasing/one-click/{token}>` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (RFC 8058) and that POST-only endpoint (GET = 405) has the same identical 404, the `chasing-opt-out` rate rule and a CSRF exemption limited to the exact POST pattern. The token is in the path: our code does not log URIs and masks the segment in problem bodies; a reverse proxy may still log it (documented in SECURITY.md).
- **L1**: before the ledger claim the vendor state row is created if missing and locked (`INSERT .. ON CONFLICT DO UPDATE .. RETURNING paused`, one statement). Pause and opt-out set the vendor's PENDING/FAILED `VENDOR_CHASE` rows DEAD and drop their tokens (the payload now carries `vendorId`); the dispatcher consults `DeliveryGuard` beans (chasing implements one: vendor paused), so notification does not depend on chasing.
- **L2**: vendors chased within the last 20 h (`last_chased_at`) are skipped regardless of the local-date cadence.
- **L3**: `EmailTemplates.oneLine` also strips U+2028/2029, LRM/RLM/ALM and the bidi embeddings/overrides/isolates, and caps at 300 characters (surrogate-safe).
- **L4**: failure counter and a stable log line; alert documented in docs/RUNBOOKS.md section 6.
- Risks accepted: the opt-out suppression hash is unsalted (guessable for known addresses; table is internal); a pause committed after the dispatcher's guard check and before the provider call can still send one email; a one-click token logged by a proxy can unsubscribe that one address (180 days).

## Final review fixes
- Rate limit: RFC 8058 one-click POST has its own rule `chasing-one-click` (500/min per IP); header-token GET/POST stay `chasing-opt-out` (20/min).
- Per-vendor lock (`ChasingStore.lockState`) is one statement that also share-locks the vendor row and returns status + email; the chase is skipped when the vendor is no longer ACTIVE or its email hash differs from the candidate's.
- Suppression no longer reveals its cause cross-tenant: document requests answer 422 `address-not-deliverable` ("This address can't receive emails from VendorFlow"); portal link creation reports `emailSkippedReason: EMAIL_NOT_DELIVERABLE`.
- `OutboxDispatcher.process`: a guard that throws marks that row failed (retry/backoff) and the batch continues.
- V12 (unreleased, edited in place): `vendor_chasing_link_fk` and `vendor_chase_link_fk` are `ON DELETE SET NULL (column)` (`vendor_chase.link_id` is nullable), so deleting an upload link never blocks on chasing history; vendor delete still cascades chasing rows.
