import { describe, expect, it } from "vitest";
import type { Role } from "@/features/organization/types";
import { primaryAction } from "./actions";
import type { RequirementStatus } from "./types";

describe("primaryAction", () => {
  const matrix: [RequirementStatus, Role, ReturnType<typeof primaryAction>][] = [
    ["MISSING", "OWNER", "upload"],
    ["MISSING", "ADMIN", "upload"],
    ["MISSING", "MEMBER", "upload"],
    ["MISSING", "VIEWER", null],
    ["EXPIRED", "MEMBER", "upload"],
    ["EXPIRED", "VIEWER", null],
    ["EXPIRING", "OWNER", "upload-renewal"],
    ["EXPIRING", "VIEWER", null],
    ["REVIEW_REQUIRED", "OWNER", "review"],
    ["REVIEW_REQUIRED", "ADMIN", "review"],
    ["OK", "OWNER", null],
    ["OK", "VIEWER", null],
  ];
  it.each(matrix)("%s as %s -> %s", (status, role, expected) => {
    expect(primaryAction(status, role)).toBe(expected);
  });
  it("offers nothing without a role", () => {
    expect(primaryAction("MISSING", null)).toBeNull();
  });
});
