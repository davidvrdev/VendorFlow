import type { RowFilter } from "./import-state";
import type { ImportPreviewRow, ImportResult, ImportRowAction, ImportSummary } from "./types";

export const FILTERS: { value: RowFilter; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "ERROR", label: "Errors" },
  { value: "CREATE", label: "Create" },
  { value: "UPDATE", label: "Update" },
];

export const ACTION_LABELS: Record<ImportRowAction, string> = {
  CREATE: "Create",
  UPDATE: "Update",
  UNCHANGED: "Unchanged",
  ERROR: "Error",
};

export function filterRows(rows: ImportPreviewRow[], filter: RowFilter): ImportPreviewRow[] {
  return filter === "ALL" ? rows : rows.filter((row) => row.action === filter);
}

export function filterCount(summary: ImportSummary, filter: RowFilter): number {
  switch (filter) {
    case "ALL":
      return summary.total;
    case "ERROR":
      return summary.error;
    case "CREATE":
      return summary.create;
    case "UPDATE":
      return summary.update;
    case "UNCHANGED":
      return summary.unchanged;
  }
}

const vendors = (count: number) => `${count} ${count === 1 ? "vendor" : "vendors"}`;

/** "Create 2 vendors and update 1 vendor?" — a zero part is omitted. */
export function confirmQuestion(summary: Pick<ImportSummary, "create" | "update">): string {
  const parts: string[] = [];
  if (summary.create > 0) parts.push(`Create ${vendors(summary.create)}`);
  if (summary.update > 0) parts.push(`${parts.length ? "update" : "Update"} ${vendors(summary.update)}`);
  return `${parts.join(" and ")}?`;
}

export function resultText(result: ImportResult): string {
  return `${vendors(result.created)} created, ${vendors(result.updated)} updated, ${result.unchanged} unchanged.`;
}

/** Fields are shown with their column names; a friendlier label would hide what to fix in the file. */
export function rowErrorsText(row: ImportPreviewRow): string[] {
  return row.errors.map((error) => `${error.field}: ${error.message}`);
}
