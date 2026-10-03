# VendorFlow — Product Specification

## One-liner
**"Upload your vendor list. VendorFlow tells you what's missing, what's expiring, and who you need to chase."**

Mental model shown everywhere in the UI: **Vendor → Required documents → Current status → Expiration → Action.**

## Problem
Property managers, HOA/community association managers and facilities managers track vendor compliance documents
(COIs, licenses, W-9s, contracts) in spreadsheets, inboxes and folders. Certificates expire unnoticed, documents go
missing, follow-up is manual, and nobody can answer "which vendors are actually compliant right now?".

## ICP (initial, US market)
1. Property management companies
2. HOA / community association management companies
3. Facilities management companies

10–100 active vendors, recurring documentation, 1–10 internal users. Buyer: operations manager / owner.

## Non-goals (explicitly)
- Not an ERP, not accounts payable, not a generic document management system.
- No vendor marketplace, no vendor onboarding workflows beyond documents (until Phase 14).
- No AI in the MVP. No PMS integrations in the MVP.

## MVP scope

### Organizations & users
- Sign up creates a user + organization (user becomes OWNER), 14-day trial subscription.
- Invite users by email with a role (ADMIN, MEMBER, VIEWER). Owners can promote to OWNER.
- A user can belong to several organizations and switch between them.

### Vendors
- Create/edit/deactivate vendors: company name (required, unique in org), contact name, email, phone, category, notes, status.
- On creation, the org's default required document types are attached; editable per vendor.
- List with search (name), filter (status, compliance status, category), sort, pagination; compliance badge per vendor.

### Documents
- Upload PDF/PNG/JPG (≤ 15 MB) for a vendor + document type, with issue and expiration dates.
- New upload for the same requirement supersedes the previous one (history preserved).
- Review: PENDING → APPROVED / REJECTED (with note). Download via authorized link. Archive.
- Configurable document types per org (rename, add, deactivate, "has expiration", "required by default").

### Compliance status (per requirement)
| Status | Rule (first match wins) |
|---|---|
| MISSING | No CURRENT document for the required type, or the current one is REJECTED |
| EXPIRED | Type has expiration and `expiration_date < today` |
| REVIEW_REQUIRED | Current document `review_status = PENDING` (and not expired) |
| EXPIRING | `expiration_date − today ≤ expiring_window_days` (default 30) |
| OK | Otherwise |

Vendor status = worst requirement status (MISSING/EXPIRED → *Non-compliant*, EXPIRING/REVIEW_REQUIRED → *Attention*,
all OK → *Compliant*). A vendor with no requirements is *Compliant* but flagged "No requirements set".
"Today" is computed in the organization's time zone.

### Dashboard — answers in one glance
- Total active vendors · Compliant · Non-compliant (missing/expired) · Expiring soon · Pending review
- **"Needs attention" action list** sorted by urgency: expired → missing → expiring soonest → pending review.
  Each row: vendor, document type, status, date, and the next action (Upload / Review / Request from vendor).
- No decorative charts.

### Notifications (Resend)
- Daily compliance digest to OWNER/ADMIN (and members who opt in): new threshold crossings (30/14/7/1 days before
  expiry, and expired), plus missing-document count. One email per user per day max; nothing sent if nothing changed.
- "Request document" action: emails the vendor contact asking for the specific document (manual trigger, rate-limited).
- Invitation, email verification and password reset emails.
- Every email is logged in the outbox with status.

### CSV
- Export vendors (+ compliance summary) to CSV.
- Import vendors: upload CSV → server validates **all rows** → preview with per-row errors → confirm → applied in one
  transaction (create new, update existing by company name). Nothing changes if validation fails.

### Billing (Stripe)
- 14-day trial, no card required. One paid plan in MVP (price configured in Stripe; plan limits TBD).
- Stripe Checkout to subscribe; Stripe Billing Portal to manage/cancel.
- Subscription state driven exclusively by verified webhooks.
- When trial ends without payment or subscription is canceled: org becomes **read-only** (can view/export, cannot
  upload or create), never data loss.

## Post-MVP (designed for, not built)
AI extraction (Phase 13), vendor upload portal with tokenized links (14), automated vendor chasing (15),
integrations (16), category-based requirement rulesets.

## Success metrics (beta)
- Time from signup to first vendor list imported < 10 minutes.
- % of orgs with ≥ 1 document uploaded in first week.
- Weekly active orgs opening the dashboard.
- Reminder emails → document uploaded within 14 days.
