// Mirrors docs/API.md "Phase 3 contract details" (authoritative). Keep in sync with the backend DTOs.
export type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";
/** CANDIDATE (Phase 14): a portal upload waiting for review next to an approved CURRENT document. */
export type DocumentState = "CURRENT" | "CANDIDATE" | "SUPERSEDED" | "ARCHIVED";
export type ReviewDecision = "APPROVED" | "REJECTED";

export interface DocumentType {
  id: string;
  code: string;
  name: string;
  hasExpiration: boolean;
  requiredByDefault: boolean;
  sortOrder: number;
}

/** GET /document-types?includeInactive=true */
export interface DocumentTypeAdmin extends DocumentType {
  active: boolean;
}

export interface DocumentSummary {
  id: string;
  vendorId: string;
  documentType: { id: string; code: string; name: string; hasExpiration: boolean };
  state: DocumentState;
  reviewStatus: ReviewStatus;
  /** yyyy-MM-dd */
  issueDate: string | null;
  /** yyyy-MM-dd */
  expirationDate: string | null;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  uploadedBy: { fullName: string } | null;
  uploadedAt: string;
  reviewedBy: { fullName: string } | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  /** Phase 14: PORTAL = uploaded by the vendor through an upload link (no uploader user). */
  source?: "STAFF" | "PORTAL";
}

/** Body of POST /document-types. */
export interface DocumentTypeCreateInput {
  name: string;
  hasExpiration: boolean;
  requiredByDefault: boolean;
}

/** Body of PATCH /document-types/{id}; every field optional. */
export type DocumentTypeUpdateInput = Partial<DocumentTypeCreateInput & { active: boolean; sortOrder: number }>;

/** Body of PATCH /documents/{id}. An explicit null clears issueDate. */
export interface DocumentDatesInput {
  issueDate?: string | null;
  expirationDate?: string | null;
}
