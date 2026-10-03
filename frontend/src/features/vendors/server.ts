import "server-only";
import { notFound } from "next/navigation";
import type { Page } from "@/features/organization/types";
import { ApiError } from "@/lib/api/errors";
import { serverApiFetch } from "@/lib/api/server";
import { toApiQuery, type VendorListState } from "./list-state";
import type { DocumentType, HistoryEvent, VendorDetail, VendorSummary } from "./types";

// Server Component reads (cookies forwarded by serverApiFetch).
export const fetchVendors = (state: VendorListState) => serverApiFetch<Page<VendorSummary>>(`/vendors${toApiQuery(state)}`);

export const fetchCategories = () => serverApiFetch<string[]>("/vendors/categories");

export const fetchDocumentTypes = () => serverApiFetch<DocumentType[]>("/document-types");

export const HISTORY_PAGE_SIZE = 10;

export const fetchVendorHistory = (id: string, page: number) =>
  serverApiFetch<Page<HistoryEvent>>(`/vendors/${encodeURIComponent(id)}/history?page=${page - 1}&size=${HISTORY_PAGE_SIZE}`);

/** Foreign and missing vendors both answer 404 (tenant isolation): render the same "Vendor not found". */
export async function fetchVendorOrNotFound(id: string): Promise<VendorDetail> {
  try {
    return await serverApiFetch<VendorDetail>(`/vendors/${encodeURIComponent(id)}`);
  } catch (error) {
    throw asNotFound(error);
  }
}

/** Map a 404 (or 400 for an id the backend cannot parse) to Next's notFound(); rethrow anything else. */
export function asNotFound(error: unknown): unknown {
  if (error instanceof ApiError && (error.status === 404 || error.status === 400)) notFound();
  return error;
}
