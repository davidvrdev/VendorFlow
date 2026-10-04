import type { VendorListState } from "./list-state";

/** Server cap (docs/API.md Phase 7): more rows -> 422. A navigation download cannot show that error, so we pre-check. */
export const EXPORT_ROW_LIMIT = 10_000;

export const EXPORT_PATH = "/api/v1/vendors/export.csv";

/**
 * Export link carrying the CURRENT filters. Sort and page are deliberately omitted: the export is always
 * the whole filtered set, sorted by company name by the server.
 */
export function exportUrl(state: VendorListState): string {
  const params = new URLSearchParams();
  if (state.q) params.set("q", state.q);
  params.set("status", state.status);
  if (state.category) params.set("category", state.category);
  if (state.compliance) params.set("compliance", state.compliance);
  return `${EXPORT_PATH}?${params.toString()}`;
}

export function exportTooLarge(totalItems: number): boolean {
  return totalItems > EXPORT_ROW_LIMIT;
}
