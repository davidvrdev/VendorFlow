/** Per-requirement compliance status, derived by the backend (see docs/PRODUCT_SPEC.md). */
export const COMPLIANCE_STATUSES = ["MISSING", "OK", "EXPIRING", "EXPIRED", "REVIEW_REQUIRED"] as const;

export type ComplianceStatus = (typeof COMPLIANCE_STATUSES)[number];
