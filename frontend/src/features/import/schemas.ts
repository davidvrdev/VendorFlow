// Client-side mirror of the import upload rules (docs/API.md Phase 7). UX only: the server is the authority.

export const MAX_CSV_BYTES = 1024 * 1024;
export const MAX_IMPORT_ROWS = 2000;
export const CSV_ACCEPT = ".csv";
export const FILE_TOO_LARGE_MESSAGE = "The file is larger than 1 MB.";
export const FILE_TYPE_MESSAGE = "Only .csv files are accepted.";

/** Null when the file looks acceptable, otherwise the message to show. */
export function validateCsvFile(file: { name: string; size: number } | null | undefined): string | null {
  if (!file) return "Choose a CSV file to upload.";
  if (file.size === 0) return "The selected file is empty.";
  if (file.size > MAX_CSV_BYTES) return FILE_TOO_LARGE_MESSAGE;
  if (!file.name.toLowerCase().endsWith(".csv")) return FILE_TYPE_MESSAGE;
  return null;
}
