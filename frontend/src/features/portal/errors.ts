import { ApiError } from "@/lib/api/errors";
import { FILE_TOO_LARGE_MESSAGE, FILE_TYPE_MESSAGE } from "@/features/documents/schemas";

export const INVALID_LINK_MESSAGE = "This link is invalid or has expired. Ask the company that sent it for a new one.";
export const NO_TOKEN_MESSAGE = "Open the link from your email again. For your security this page does not remember the link.";
export const NOT_ACCEPTING_MESSAGE = "This link is not accepting uploads right now. Contact the company that sent it.";
export const LIMIT_MESSAGE = "This link's upload limit has been reached. Ask the company that sent it for a new link.";

/** Why a portal call failed, reduced to what the page has to do about it. */
export type PortalFailure = "invalid" | "unavailable" | "limit" | "other";

export function classifyPortalError(error: unknown): PortalFailure {
  if (!(error instanceof ApiError)) return "other";
  if (error.status === 404) return "invalid";
  if (error.status === 402) return "unavailable";
  if (error.status === 422 && error.code === "portal-upload-limit") return "limit";
  return "other";
}

/** Form-level messages by HTTP status for an upload (400 carries field errors and is mapped onto the form). */
export function portalUploadStatusMessages(error: unknown): Partial<Record<number, string>> {
  const limit = error instanceof ApiError && error.code === "portal-upload-limit";
  return {
    402: NOT_ACCEPTING_MESSAGE,
    413: FILE_TOO_LARGE_MESSAGE,
    415: FILE_TYPE_MESSAGE,
    422: limit ? LIMIT_MESSAGE : "This file was rejected. Check the file and try another one.",
    429: "Too many requests. Please wait a minute and try again.",
    503: "We cannot check files right now. Please try again in a few minutes.",
  };
}

export const PORTAL_UPLOAD_FIELDS = ["file", "issueDate", "expirationDate"] as const;

/** Staff create-link failures (400 carries a field error for `documentTypeIds`). */
export function createLinkStatusMessages(error: unknown): Partial<Record<number, string>> {
  const code = error instanceof ApiError ? error.code : undefined;
  return {
    402: "Your subscription is inactive. Subscribe to send upload links.",
    403: "You do not have permission to send upload links.",
    422:
      code === "vendor-no-email"
        ? "This vendor has no email address. Add one, or untick the email option and share the link yourself."
        : code === "vendor-inactive"
          ? "This vendor is inactive. Reactivate it to send an upload link."
          : "The upload link could not be created.",
    429: "Too many links created. Please wait a minute and try again.",
  };
}
