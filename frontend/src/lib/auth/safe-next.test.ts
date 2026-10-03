import { describe, expect, it } from "vitest";
import { safeNextPath } from "./safe-next";

describe("safeNextPath", () => {
  it.each(["/dashboard", "/settings/members", "/vendors?page=2", "/invite", "/a#frag"])("accepts %s", (p) => {
    expect(safeNextPath(p)).toBe(p);
  });

  it.each([
    "//evil.com",
    "///evil.com",
    "/\\evil.com",
    "\\\\evil.com",
    "\\/evil.com",
    "https://evil.com",
    "http://evil.com/x",
    "javascript:alert(1)",
    "evil.com",
    "dashboard",
    "/%2f/evil.com",
    "/%5Cevil.com",
    "/ok\nHeader: x",
    "/ with space",
    "/redirect?to=https://evil.com",
    "",
  ])("rejects %j", (p) => {
    expect(safeNextPath(p)).toBeNull();
  });

  it("rejects null/undefined/overlong", () => {
    expect(safeNextPath(null)).toBeNull();
    expect(safeNextPath(undefined)).toBeNull();
    expect(safeNextPath("/" + "a".repeat(3000))).toBeNull();
  });
});
