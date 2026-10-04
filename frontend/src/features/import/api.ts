import { apiFetch } from "@/lib/api/client";
import type { ImportPreview, ImportResult } from "./types";

// Browser-side calls (CSRF header added by apiFetch). Nothing is parsed here: the server parses the CSV.
export const TEMPLATE_URL = "/api/v1/vendors/import/template.csv";

/** Multipart upload; apiFetch must not set Content-Type for FormData (the browser adds the boundary). */
export function previewImport(file: File) {
  const form = new FormData();
  form.append("file", file, file.name);
  return apiFetch<ImportPreview>("/vendors/import/preview", { method: "POST", body: form });
}

export const commitImport = (importId: string) =>
  apiFetch<ImportResult>(`/vendors/import/${encodeURIComponent(importId)}/commit`, { method: "POST" });
