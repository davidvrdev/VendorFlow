// Mirrors docs/API.md "Phase 5 contract details" (authoritative).
import type { RequirementStatus } from "@/features/compliance/types";

export interface DashboardSummary {
  /** yyyy-MM-dd in the organization's time zone: what "today" meant for these numbers. */
  today: string;
  expiringWindowDays: number;
  vendors: { active: number; compliant: number; attention: number; nonCompliant: number; noRequirements: number };
  documents: { missing: number; expired: number; expiring: number; reviewRequired: number };
}

export type AttentionAction = "UPLOAD" | "UPLOAD_RENEWAL" | "REVIEW";

export interface AttentionItem {
  vendorId: string;
  vendorName: string;
  documentTypeId: string;
  documentTypeName: string;
  status: Exclude<RequirementStatus, "OK">;
  documentId: string | null;
  expirationDate: string | null;
  daysUntilExpiration: number | null;
  action: AttentionAction;
}
