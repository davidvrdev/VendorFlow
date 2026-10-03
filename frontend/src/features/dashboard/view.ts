// Pure display logic for the dashboard (unit-tested). Numbers and day counts always come from the server.
import type { VendorCompliance } from "@/features/compliance/types";
import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import type { AttentionAction, AttentionItem, DashboardSummary } from "./types";

export interface TileSpec {
  key: "active" | VendorCompliance;
  label: string;
  value: number;
  href: string;
}

/** Four tiles, each a link into the vendors list filtered the same way the number was counted. */
export function summaryTiles(summary: DashboardSummary): TileSpec[] {
  const { vendors } = summary;
  return [
    { key: "active", label: "Active vendors", value: vendors.active, href: "/vendors" },
    { key: "COMPLIANT", label: "Compliant", value: vendors.compliant, href: "/vendors?compliance=COMPLIANT" },
    { key: "ATTENTION", label: "Needs attention", value: vendors.attention, href: "/vendors?compliance=ATTENTION" },
    { key: "NON_COMPLIANT", label: "Non-compliant", value: vendors.nonCompliant, href: "/vendors?compliance=NON_COMPLIANT" },
  ];
}

/** "6 missing · 1 expired · 2 expiring in the next 30 days · 1 to review"; non-zero parts only, null when none. */
export function documentsLine(summary: DashboardSummary): string | null {
  const { documents, expiringWindowDays } = summary;
  const parts: [number, string][] = [
    [documents.missing, "missing"],
    [documents.expired, "expired"],
    [documents.expiring, `expiring in the next ${expiringWindowDays} days`],
    [documents.reviewRequired, "to review"],
  ];
  const text = parts.filter(([n]) => n > 0).map(([n, label]) => `${n} ${label}`);
  return text.length ? text.join(" · ") : null;
}

export type DashboardState = "no-vendors" | "all-clear" | "attention";

/** What the main area shows. The attention total (not the vendor counts) decides, so a stale tile never hides work. */
export function dashboardState(summary: DashboardSummary, attentionTotal: number): DashboardState {
  if (summary.vendors.active === 0) return "no-vendors";
  return attentionTotal === 0 ? "all-clear" : "attention";
}

export type RowAction = { kind: "upload" | "upload-renewal" | "review" | "link"; label: string };

const ACTION_LABEL: Record<AttentionAction, string> = { UPLOAD: "Upload", UPLOAD_RENEWAL: "Upload renewal", REVIEW: "Review" };

/** The one control per row. Roles that cannot perform the action (cosmetic; the API enforces) get a link to the vendor. */
export function rowAction(item: Pick<AttentionItem, "action" | "documentId">, role: Role | null | undefined): RowAction {
  const label = ACTION_LABEL[item.action];
  if (item.action === "REVIEW") {
    // Review needs a document to act on; without one fall back to the vendor page.
    return can(role, "DOCUMENTS_REVIEW") && item.documentId ? { kind: "review", label } : { kind: "link", label: "View vendor" };
  }
  if (!can(role, "VENDORS_WRITE")) return { kind: "link", label: "View vendor" };
  return { kind: item.action === "UPLOAD" ? "upload" : "upload-renewal", label };
}

/** Accessible name that says what and for whom: "Upload Certificate of Insurance for Acme Plumbing". */
export function actionAriaLabel(action: RowAction, item: Pick<AttentionItem, "vendorName" | "documentTypeName">): string {
  return action.kind === "link" ? `View ${item.vendorName}` : `${action.label} ${item.documentTypeName} for ${item.vendorName}`;
}

export function attentionHref(page: number): string {
  return page > 1 ? `/dashboard?attentionPage=${page}` : "/dashboard";
}

export function parseAttentionPage(value: string | string[] | undefined): number {
  const n = Number.parseInt((Array.isArray(value) ? value[0] : value) ?? "", 10);
  return Number.isFinite(n) && n >= 1 ? n : 1;
}
