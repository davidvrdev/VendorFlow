// Mirrors docs/API.md "Phase 7 contract details — CSV". Keep in sync with the backend DTOs.

export type ImportRowAction = "CREATE" | "UPDATE" | "UNCHANGED" | "ERROR";

export interface ImportRowError {
  /** Column name. */
  field: string;
  message: string;
}

export interface ImportPreviewRow {
  /** 1-based data row (header excluded), as the user sees it in a spreadsheet. */
  rowNumber: number;
  companyName: string | null;
  action: ImportRowAction;
  /** UPDATE: names of the fields that will change. */
  changes: string[];
  errors: ImportRowError[];
}

export interface ImportSummary {
  total: number;
  create: number;
  update: number;
  unchanged: number;
  error: number;
}

export interface ImportPreview {
  importId: string;
  expiresAt: string;
  summary: ImportSummary;
  rows: ImportPreviewRow[];
}

export interface ImportResult {
  created: number;
  updated: number;
  unchanged: number;
}
