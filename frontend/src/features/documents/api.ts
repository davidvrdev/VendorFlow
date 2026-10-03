import { apiFetch } from "@/lib/api/client";
import type {
  DocumentDatesInput,
  DocumentSummary,
  DocumentTypeAdmin,
  DocumentTypeCreateInput,
  DocumentTypeUpdateInput,
  ReviewDecision,
} from "./types";

// Browser-side calls (CSRF header added by apiFetch). Page data is read in Server Components (server.ts).
const enc = encodeURIComponent;

export interface UploadInput {
  documentTypeId: string;
  file: File;
  issueDate: string | null;
  expirationDate: string | null;
}

/**
 * Multipart upload. A FormData body makes the browser set `Content-Type: multipart/form-data; boundary=...`
 * itself; apiFetch never sets Content-Type for non-JSON bodies, so do not add one here.
 */
export function uploadDocument(vendorId: string, input: UploadInput) {
  const form = new FormData();
  form.append("documentTypeId", input.documentTypeId);
  if (input.issueDate) form.append("issueDate", input.issueDate);
  if (input.expirationDate) form.append("expirationDate", input.expirationDate);
  // The file goes last so the server can see the cheap fields first while streaming.
  form.append("file", input.file, input.file.name);
  return apiFetch<DocumentSummary>(`/vendors/${enc(vendorId)}/documents`, { method: "POST", body: form });
}

/** On-demand read for the history disclosure (everything else is read server-side). */
export const listVendorDocuments = (vendorId: string, includeHistory: boolean) =>
  apiFetch<DocumentSummary[]>(`/vendors/${enc(vendorId)}/documents?includeHistory=${includeHistory}`);

export const updateDocumentDates = (documentId: string, input: DocumentDatesInput) =>
  apiFetch<DocumentSummary>(`/documents/${enc(documentId)}`, { method: "PATCH", json: input });

export const reviewDocument = (documentId: string, decision: ReviewDecision, note?: string) =>
  apiFetch<DocumentSummary>(`/documents/${enc(documentId)}/review`, {
    method: "POST",
    json: note ? { decision, note } : { decision },
  });

export const archiveDocument = (documentId: string) => apiFetch<DocumentSummary>(`/documents/${enc(documentId)}/archive`, { method: "POST" });

/** Same-origin link; the session cookie travels and the backend answers with an attachment (no JS blob handling). */
export const downloadHref = (documentId: string) => `/api/v1/documents/${enc(documentId)}/download`;

export const createDocumentType = (input: DocumentTypeCreateInput) =>
  apiFetch<DocumentTypeAdmin>("/document-types", { method: "POST", json: input });

export const updateDocumentType = (typeId: string, input: DocumentTypeUpdateInput) =>
  apiFetch<DocumentTypeAdmin>(`/document-types/${enc(typeId)}`, { method: "PATCH", json: input });
