import { describe, expect, it } from "vitest";
import { assignableRoles, can, canManageMemberRow, invitableRoles, type Permission } from "./permissions";
import type { Role } from "./types";

// Expected matrix, transcribed independently from docs/SECURITY.md §3.
const EXPECTED: Record<Permission, Role[]> = {
  VENDORS_VIEW: ["OWNER", "ADMIN", "MEMBER", "VIEWER"],
  DOCUMENTS_DOWNLOAD: ["OWNER", "ADMIN", "MEMBER", "VIEWER"],
  VENDORS_WRITE: ["OWNER", "ADMIN", "MEMBER"],
  DOCUMENTS_REVIEW: ["OWNER", "ADMIN", "MEMBER"],
  ARCHIVE_AND_IMPORT: ["OWNER", "ADMIN"],
  REQUIREMENTS_MANAGE: ["OWNER", "ADMIN"],
  MEMBERS_VIEW: ["OWNER", "ADMIN", "MEMBER", "VIEWER"],
  MEMBERS_MANAGE: ["OWNER", "ADMIN"],
  ORG_SETTINGS_MANAGE: ["OWNER", "ADMIN"],
  BILLING_MANAGE: ["OWNER"],
  OWNER_GRANT: ["OWNER"],
};
const ROLES: Role[] = ["OWNER", "ADMIN", "MEMBER", "VIEWER"];

describe("can", () => {
  for (const [permission, allowed] of Object.entries(EXPECTED) as [Permission, Role[]][]) {
    it(`${permission} is allowed for exactly ${allowed.join(", ")}`, () => {
      for (const role of ROLES) expect(can(role, permission)).toBe(allowed.includes(role));
    });
  }
  it("denies when there is no role", () => {
    expect(can(null, "VENDORS_VIEW")).toBe(false);
    expect(can(undefined, "MEMBERS_VIEW")).toBe(false);
  });
});

describe("role pickers", () => {
  it("only OWNER can invite ADMIN; nobody invites OWNER", () => {
    expect(invitableRoles("OWNER")).toEqual(["ADMIN", "MEMBER", "VIEWER"]);
    expect(invitableRoles("ADMIN")).toEqual(["MEMBER", "VIEWER"]);
    expect(invitableRoles("MEMBER")).toEqual([]);
    expect(invitableRoles("VIEWER")).toEqual([]);
  });
  it("ADMIN never sees OWNER when changing roles", () => {
    expect(assignableRoles("OWNER")).toContain("OWNER");
    expect(assignableRoles("ADMIN")).not.toContain("OWNER");
    expect(assignableRoles("ADMIN")).toEqual(["MEMBER", "VIEWER"]);
    expect(assignableRoles("MEMBER")).toEqual([]);
  });
  it("ADMIN cannot manage OWNER rows", () => {
    expect(canManageMemberRow("ADMIN", "OWNER")).toBe(false);
    expect(canManageMemberRow("ADMIN", "MEMBER")).toBe(true);
    expect(canManageMemberRow("ADMIN", "VIEWER")).toBe(true);
    expect(canManageMemberRow("ADMIN", "ADMIN")).toBe(false);
    expect(canManageMemberRow("OWNER", "ADMIN")).toBe(true);
    expect(canManageMemberRow("OWNER", "OWNER")).toBe(true);
    expect(canManageMemberRow("MEMBER", "VIEWER")).toBe(false);
  });
});
