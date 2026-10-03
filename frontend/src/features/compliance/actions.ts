import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import type { RequirementStatus } from "./types";

export type PrimaryAction = "upload" | "review" | "upload-renewal";

export const PRIMARY_ACTION_LABELS: Record<PrimaryAction, string> = {
  upload: "Upload",
  review: "Review",
  "upload-renewal": "Upload renewal",
};

/** The one action each status implies; null when OK or the role cannot perform it. Cosmetic: the API enforces roles. */
export function primaryAction(status: RequirementStatus, role: Role | null | undefined): PrimaryAction | null {
  switch (status) {
    case "MISSING":
    case "EXPIRED":
      return can(role, "VENDORS_WRITE") ? "upload" : null;
    case "EXPIRING":
      return can(role, "VENDORS_WRITE") ? "upload-renewal" : null;
    case "REVIEW_REQUIRED":
      return can(role, "DOCUMENTS_REVIEW") ? "review" : null;
    default:
      return null;
  }
}
