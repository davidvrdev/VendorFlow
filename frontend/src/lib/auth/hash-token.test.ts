import { describe, expect, it } from "vitest";
import { extractTokenFromHash } from "./hash-token";

describe("extractTokenFromHash", () => {
  it("reads token from a fragment", () => {
    expect(extractTokenFromHash("#token=abc123")).toBe("abc123");
    expect(extractTokenFromHash("token=abc123")).toBe("abc123");
  });
  it("decodes url-encoded values and ignores other params", () => {
    expect(extractTokenFromHash("#x=1&token=a%2Bb")).toBe("a+b");
  });
  it("returns null when missing, empty or absurdly long", () => {
    expect(extractTokenFromHash("")).toBeNull();
    expect(extractTokenFromHash("#")).toBeNull();
    expect(extractTokenFromHash("#token=")).toBeNull();
    expect(extractTokenFromHash("#other=1")).toBeNull();
    expect(extractTokenFromHash("#token=" + "a".repeat(600))).toBeNull();
  });
});
