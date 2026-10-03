import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";

/** Requirement statuses for which asking the vendor for a document makes sense. */
const REQUESTABLE = new Set(["MISSING", "EXPIRED", "EXPIRING"]);

export type RequestAvailability = "hidden" | "no-email" | "available";

export const NO_EMAIL_HELP = "Add the vendor's email to request documents";

/**
 * Whether (and how) to offer "Request from vendor". Cosmetic: the API enforces role, vendor state and email.
 * `vendorEmail === undefined` means "unknown to this view" (e.g. dashboard rows): offer it and let the API answer 422.
 */
export function requestAvailability(input: {
  status: string | undefined;
  role: Role | null | undefined;
  vendorEmail: string | null | undefined;
}): RequestAvailability {
  if (!input.status || !REQUESTABLE.has(input.status)) return "hidden";
  if (!can(input.role, "VENDORS_WRITE")) return "hidden";
  if (input.vendorEmail === null || input.vendorEmail?.trim() === "") return "no-email";
  return "available";
}

/** Form-level messages by HTTP status for POST /vendors/{id}/document-requests. */
export const REQUEST_ERROR_MESSAGES: Partial<Record<number, string>> = {
  400: "This document type cannot be requested.",
  403: "You do not have permission to request documents.",
  409: "Already requested today",
  422: "Vendor has no email",
  429: "Too many requests. Please wait a minute and try again.",
};

export function requestSentMessage(email: string): string {
  return `Request sent to ${email}`;
}
