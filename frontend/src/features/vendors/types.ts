// Mirrors docs/API.md "Phase 2 + 3 contract details". Keep in sync with the backend DTOs.
import type { DocumentSummary } from "@/features/documents/types";

export type { DocumentType } from "@/features/documents/types";

export type VendorStatus = "ACTIVE" | "INACTIVE";

export interface VendorSummary {
  id: string;
  companyName: string;
  contactName: string | null;
  email: string | null;
  phone: string | null;
  category: string | null;
  status: VendorStatus;
  requirementCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface VendorRequirement {
  documentTypeId: string;
  code: string;
  name: string;
  hasExpiration: boolean;
  /** Phase 3: the CURRENT document for this requirement, or null when there is none. */
  currentDocument: DocumentSummary | null;
}

export interface VendorDetail extends VendorSummary {
  notes: string | null;
  createdBy: { fullName: string } | null;
  /** Ordered by document type sortOrder. */
  requirements: VendorRequirement[];
  /** Phase 3: CURRENT documents whose type is not a requirement of this vendor. */
  otherDocuments: DocumentSummary[];
}

/** Body of POST /vendors and PUT /vendors/{id}. */
export interface VendorInput {
  companyName: string;
  contactName?: string | null;
  email?: string | null;
  phone?: string | null;
  category?: string | null;
  notes?: string | null;
}

export interface HistoryEvent {
  id: string;
  action: string;
  actor: { fullName: string } | null;
  occurredAt: string;
  changes: Record<string, { before: unknown; after: unknown }> | null;
}
