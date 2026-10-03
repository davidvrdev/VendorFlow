import type { Role } from "./types";

/**
 * Cosmetic mirror of the role matrix in docs/SECURITY.md §3. The backend is the only authority;
 * this only decides what to show, hide or disable. If the two ever disagree, the backend wins.
 */
export type Permission =
  | "VENDORS_VIEW"
  | "DOCUMENTS_DOWNLOAD"
  | "VENDORS_WRITE"
  | "DOCUMENTS_REVIEW"
  | "ARCHIVE_AND_IMPORT"
  | "REQUIREMENTS_MANAGE"
  | "MEMBERS_VIEW"
  | "MEMBERS_MANAGE"
  | "ORG_SETTINGS_MANAGE"
  | "EMAIL_ACTIVITY_VIEW"
  | "BILLING_MANAGE"
  | "OWNER_GRANT";

const ALL: Role[] = ["OWNER", "ADMIN", "MEMBER", "VIEWER"];
const WRITERS: Role[] = ["OWNER", "ADMIN", "MEMBER"];
const MANAGERS: Role[] = ["OWNER", "ADMIN"];
const OWNER_ONLY: Role[] = ["OWNER"];

const MATRIX: Record<Permission, readonly Role[]> = {
  VENDORS_VIEW: ALL,
  DOCUMENTS_DOWNLOAD: ALL,
  VENDORS_WRITE: WRITERS,
  DOCUMENTS_REVIEW: WRITERS,
  ARCHIVE_AND_IMPORT: MANAGERS,
  REQUIREMENTS_MANAGE: MANAGERS,
  MEMBERS_VIEW: ALL,
  MEMBERS_MANAGE: MANAGERS,
  ORG_SETTINGS_MANAGE: MANAGERS,
  EMAIL_ACTIVITY_VIEW: MANAGERS,
  BILLING_MANAGE: OWNER_ONLY,
  OWNER_GRANT: OWNER_ONLY,
};

export function can(role: Role | null | undefined, permission: Permission): boolean {
  return role != null && MATRIX[permission].includes(role);
}

/** Roles an actor can pick when inviting: ADMIN invitations are OWNER-only (docs/API.md). */
export function invitableRoles(actor: Role | null | undefined): Exclude<Role, "OWNER">[] {
  if (!can(actor, "MEMBERS_MANAGE")) return [];
  return actor === "OWNER" ? ["ADMIN", "MEMBER", "VIEWER"] : ["MEMBER", "VIEWER"];
}

/**
 * Roles an actor can pick when changing a member's role (docs/API.md § Members): OWNER may assign any
 * role; ADMIN may only assign MEMBER or VIEWER.
 */
export function assignableRoles(actor: Role | null | undefined): Role[] {
  if (!can(actor, "MEMBERS_MANAGE")) return [];
  return actor === "OWNER" ? ["OWNER", "ADMIN", "MEMBER", "VIEWER"] : ["MEMBER", "VIEWER"];
}

/** Whether the actor may change/remove another member's row. ADMIN may only manage MEMBER/VIEWER rows. */
export function canManageMemberRow(actor: Role | null | undefined, target: Role): boolean {
  if (!can(actor, "MEMBERS_MANAGE")) return false;
  return actor === "OWNER" || target === "MEMBER" || target === "VIEWER";
}

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: "Owner",
  ADMIN: "Admin",
  MEMBER: "Member",
  VIEWER: "Viewer",
};
