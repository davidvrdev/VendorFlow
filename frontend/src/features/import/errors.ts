import { ApiError } from "@/lib/api/errors";
import { NETWORK_ERROR_MESSAGE } from "@/lib/forms/api-errors";
import { FILE_TOO_LARGE_MESSAGE, FILE_TYPE_MESSAGE } from "./schemas";

/** Preview failures by HTTP status. Where the server gives a specific detail (400, 422) we show it. */
export function describePreviewError(error: unknown): string {
  if (!(error instanceof ApiError)) return NETWORK_ERROR_MESSAGE;
  switch (error.status) {
    case 400:
      // Header problems (missing company_name, duplicate columns) come as field errors: name the columns, otherwise
      // the generic "One or more fields are invalid." would not tell the user what to fix in their file.
      if (error.errors?.length) {
        return `Fix the header row: ${error.errors.map((e) => `${e.field}: ${e.message}`).join("; ")}.`;
      }
      return error.detail ?? "The file could not be read. Check that it is a UTF-8 CSV file with a header row.";
    case 403:
      return "You do not have permission to import vendors.";
    case 413:
      return FILE_TOO_LARGE_MESSAGE;
    case 415:
      return FILE_TYPE_MESSAGE;
    case 422:
      return error.detail ?? "The file has too many rows. Import up to 2,000 vendors at a time.";
    case 429:
      return "Too many uploads. Please wait a minute and try again.";
    default:
      return error.detail ?? error.title;
  }
}

/** Why a commit can no longer succeed with this preview: the user has to upload the file again. */
export type CommitBlock = "expired" | "changed" | "committed";

export interface CommitFailure {
  message: string;
  /** Set when this preview is dead; null for transient/other errors where retrying may work. */
  block: CommitBlock | null;
}

export function describeCommitError(error: unknown): CommitFailure {
  if (!(error instanceof ApiError)) return { message: NETWORK_ERROR_MESSAGE, block: null };
  if (error.status === 410) {
    return { message: "This preview has expired. Upload the file again to get a new preview.", block: "expired" };
  }
  if (error.status === 409) {
    // The contract uses 409 for two cases; the title tells them apart.
    if (/data changed/i.test(error.title)) {
      return {
        message:
          "Your vendor data changed since this preview, so nothing was imported. Upload the file again to preview it against the current data.",
        block: "changed",
      };
    }
    return { message: "This import was already committed.", block: "committed" };
  }
  if (error.status === 403) return { message: "You do not have permission to import vendors.", block: null };
  if (error.status === 404) return { message: "This preview could not be found. Upload the file again.", block: "expired" };
  if (error.status === 422) return { message: "The file still has errors. Fix them and upload it again.", block: null };
  return { message: error.detail ?? error.title, block: null };
}
