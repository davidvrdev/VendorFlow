// Mirrors docs/API.md "Phase 2 contract details". Keep in sync with the backend DTOs.
export type VendorStatus = "ACTIVE" | "INACTIVE";

export interface DocumentType {
  id: string;
  code: string;
  name: string;
  hasExpiration: boolean;
  requiredByDefault: boolean;
  sortOrder: number;
}

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
}

export interface VendorDetail extends VendorSummary {
  notes: string | null;
  createdBy: { fullName: string } | null;
  /** Ordered by document type sortOrder. */
  requirements: VendorRequirement[];
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
