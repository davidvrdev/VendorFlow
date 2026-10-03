/** Per-requirement compliance status, derived by the backend (see docs/PRODUCT_SPEC.md). */
export const COMPLIANCE_STATUSES = ["MISSING", "OK", "EXPIRING", "EXPIRED", "REVIEW_REQUIRED"] as const;

export type ComplianceStatus = (typeof COMPLIANCE_STATUSES)[number];

export const VENDOR_COMPLIANCES = ["NON_COMPLIANT", "ATTENTION", "COMPLIANT"] as const;
export type VendorCompliance = (typeof VENDOR_COMPLIANCES)[number];

/** Phase 4 contract name (docs/API.md); same type as `ComplianceStatus`. */
export type RequirementStatus = ComplianceStatus;

export interface ComplianceSummary {
  status: VendorCompliance;
  missing: number;
  expired: number;
  expiring: number;
  reviewRequired: number;
  ok: number;
  /** yyyy-MM-dd: earliest expirationDate among CURRENT, non-rejected docs of active requirement types. */
  nextExpiration: string | null;
  daysUntilNextExpiration: number | null; // relative to the org's today; negative when already expired
}
