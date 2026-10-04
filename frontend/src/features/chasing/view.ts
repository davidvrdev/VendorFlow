import type { Chase, VendorChasingState } from "./schemas";

/** Pure display rules shared by the badge and the card (and unit-tested). */
export function chasingStatusLabel(state: Pick<VendorChasingState, "status" | "pausedReason">): string {
  if (state.status === "PAUSED") return state.pausedReason === "OPT_OUT" ? "Vendor unsubscribed" : "Paused";
  switch (state.status) {
    case "ACTIVE":
      return "Active";
    case "EXHAUSTED":
      return "Follow-ups used up";
    case "NO_EMAIL":
      return "No email address";
    default:
      return "Nothing to chase";
  }
}

export function chasingStatusHint(state: VendorChasingState): string {
  switch (state.status) {
    case "ACTIVE":
      return "The vendor is emailed on schedule until its documents are in order.";
    case "EXHAUSTED":
      return "The maximum number of follow-ups was sent. Contact the vendor yourself.";
    case "NO_EMAIL":
      return "Add an email address to the vendor to enable automatic follow-ups.";
    case "PAUSED":
      return state.pausedReason === "OPT_OUT"
        ? "The vendor used the unsubscribe link in a reminder. Automatic follow-ups cannot be turned back on. You can still request documents manually."
        : "Automatic follow-ups are paused for this vendor.";
    default:
      return "All required documents are in order, or the vendor is inactive.";
  }
}

export const LINK_STATUS_LABELS: Record<NonNullable<Chase["linkStatus"]>, string> = {
  ACTIVE: "Link active",
  EXHAUSTED: "Link used up",
  EXPIRED: "Link expired",
  REVOKED: "Link replaced",
};

export const EMAIL_STATUS_LABELS: Record<NonNullable<Chase["emailStatus"]>, string> = {
  PENDING: "Email queued",
  SENT: "Email sent",
  FAILED: "Email failed, retrying",
  DEAD: "Email not delivered",
};

/** The API sends the org-local calendar day as yyyy-MM-dd; anchor it to UTC midnight for formatDate (which pins UTC). */
export function chaseDateIso(date: string): string {
  return `${date}T00:00:00Z`;
}
