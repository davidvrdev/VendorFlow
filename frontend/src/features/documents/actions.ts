import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import type { DocumentSummary } from "./types";

export interface RowActions {
  upload: boolean;
  replace: boolean;
  download: boolean;
  approve: boolean;
  reject: boolean;
  editDates: boolean;
  archive: boolean;
}

/**
 * Which row actions to offer. Cosmetic only (the API enforces roles and state rules). State rules mirror
 * docs/API.md: review and date edits need a CURRENT document; archive needs CURRENT or SUPERSEDED;
 * inactive types cannot receive uploads (400), so upload/replace are hidden for them.
 */
export function rowActions(role: Role | null | undefined, document: DocumentSummary | null, typeActive = true): RowActions {
  const writer = can(role, "VENDORS_WRITE");
  // A portal CANDIDATE is reviewed like a current document (approving it supersedes the old one).
  const current = document?.state === "CURRENT" || document?.state === "CANDIDATE";
  const reviewer = can(role, "DOCUMENTS_REVIEW");
  return {
    upload: document === null && writer && typeActive,
    replace: document !== null && writer && typeActive,
    download: document !== null && can(role, "DOCUMENTS_DOWNLOAD"),
    approve: current && reviewer && document.reviewStatus !== "APPROVED",
    reject: current && reviewer && document.reviewStatus !== "REJECTED",
    editDates: document?.state === "CURRENT" && writer,
    archive: document !== null && document.state !== "ARCHIVED" && can(role, "ARCHIVE_AND_IMPORT"),
  };
}

export const hasAnyAction = (actions: RowActions) => Object.values(actions).some(Boolean);
