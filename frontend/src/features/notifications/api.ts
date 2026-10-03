import { apiFetch } from "@/lib/api/client";
import type { DocumentRequestResult } from "./types";

export const requestDocumentFromVendor = (vendorId: string, documentTypeId: string) =>
  apiFetch<DocumentRequestResult>(`/vendors/${encodeURIComponent(vendorId)}/document-requests`, {
    method: "POST",
    json: { documentTypeId },
  });
