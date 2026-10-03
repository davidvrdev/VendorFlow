import { FILE_TOO_LARGE_MESSAGE, FILE_TYPE_MESSAGE } from "./schemas";

/**
 * Form-level messages for upload failures, keyed by HTTP status (docs/API.md Phase 3 validation order).
 * 400 is deliberately absent: it carries per-field errors that applyApiError maps onto the form.
 */
export const UPLOAD_STATUS_MESSAGES: Partial<Record<number, string>> = {
  403: "You do not have permission to upload documents.",
  413: FILE_TOO_LARGE_MESSAGE,
  415: FILE_TYPE_MESSAGE,
  422: "File rejected.",
  429: "Too many uploads. Please wait a minute and try again.",
};

export const UPLOAD_FIELDS = ["file", "documentTypeId", "issueDate", "expirationDate"] as const;
