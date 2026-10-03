import type { DocumentSummary } from "./types";

export const doc = (overrides: Partial<DocumentSummary> = {}): DocumentSummary => ({
  id: "d1",
  vendorId: "v1",
  documentType: { id: "t1", code: "COI", name: "Certificate of Insurance", hasExpiration: true },
  state: "CURRENT",
  reviewStatus: "PENDING",
  issueDate: null,
  expirationDate: "2031-01-01",
  originalFilename: "coi.pdf",
  mimeType: "application/pdf",
  sizeBytes: 1000,
  uploadedBy: null,
  uploadedAt: "2030-01-01T00:00:00Z",
  reviewedBy: null,
  reviewedAt: null,
  reviewNote: null,
  ...overrides,
});
