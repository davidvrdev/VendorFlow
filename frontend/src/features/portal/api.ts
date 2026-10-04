import type { z } from "zod";
import { apiFetch } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { toApiPath } from "@/lib/api/path";
import {
  createdUploadLinkSchema,
  portalInfoSchema,
  portalUploadResultSchema,
  uploadLinkPageSchema,
  uploadLinkSchema,
  type CreatedUploadLink,
  type PortalInfo,
  type PortalUploadResult,
  type UploadLink,
  type UploadLinkPage,
} from "./schemas";

const enc = encodeURIComponent;
export const PORTAL_TOKEN_HEADER = "X-Portal-Token";

function parse<S extends z.ZodType>(schema: S, data: unknown): z.output<S> {
  const result = schema.safeParse(data);
  // Not an ApiError on purpose: callers show a generic "could not load" message, never raw parser output.
  if (!result.success) throw new Error("Unexpected response from the server.");
  return result.data;
}

/**
 * Public portal call. Deliberately NOT apiFetch: there is no session, so no cookies are sent (`omit`), no CSRF header
 * and no "subscription inactive" event (that is for signed-in staff). The token travels in a header, never in a URL.
 */
async function portalFetch(path: string, token: string, init: { method?: string; body?: FormData } = {}): Promise<unknown> {
  const response = await fetch(toApiPath(path), {
    method: init.method ?? "GET",
    credentials: "omit",
    cache: "no-store",
    referrerPolicy: "no-referrer",
    headers: { Accept: "application/json", [PORTAL_TOKEN_HEADER]: token },
    body: init.body,
  });
  if (!response.ok) throw await ApiError.fromResponse(response);
  return response.json();
}

export async function fetchPortalInfo(token: string): Promise<PortalInfo> {
  return parse(portalInfoSchema, await portalFetch("/portal/link", token));
}

export interface PortalUploadInput {
  documentTypeId: string;
  file: File;
  issueDate: string | null;
  expirationDate: string | null;
}

export async function uploadPortalDocument(token: string, input: PortalUploadInput): Promise<PortalUploadResult> {
  const form = new FormData();
  form.append("documentTypeId", input.documentTypeId);
  if (input.issueDate) form.append("issueDate", input.issueDate);
  if (input.expirationDate) form.append("expirationDate", input.expirationDate);
  // File last so the server sees the cheap fields first while streaming.
  form.append("file", input.file, input.file.name);
  return parse(portalUploadResultSchema, await portalFetch("/portal/link/documents", token, { method: "POST", body: form }));
}

// ---- Staff (session + CSRF via apiFetch) ----

export interface CreateUploadLinkInput {
  documentTypeIds: string[];
  expiresInDays: number;
  maxUploads: number;
  sendEmail: boolean;
}

export async function createUploadLink(vendorId: string, input: CreateUploadLinkInput): Promise<CreatedUploadLink> {
  return parse(createdUploadLinkSchema, await apiFetch<unknown>(`/vendors/${enc(vendorId)}/upload-links`, { method: "POST", json: input }));
}

export async function listUploadLinks(vendorId: string, page: number, size = 10): Promise<UploadLinkPage> {
  return parse(uploadLinkPageSchema, await apiFetch<unknown>(`/vendors/${enc(vendorId)}/upload-links?page=${page}&size=${size}`));
}

export async function revokeUploadLink(vendorId: string, linkId: string): Promise<UploadLink> {
  return parse(uploadLinkSchema, await apiFetch<unknown>(`/vendors/${enc(vendorId)}/upload-links/${enc(linkId)}/revoke`, { method: "POST" }));
}
