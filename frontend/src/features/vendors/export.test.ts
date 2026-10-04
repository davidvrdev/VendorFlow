import { describe, expect, it } from "vitest";
import { exportTooLarge, exportUrl } from "./export";
import { DEFAULT_LIST_STATE } from "./list-state";

describe("exportUrl", () => {
  it("always sends the status and omits sort and page", () => {
    expect(exportUrl({ ...DEFAULT_LIST_STATE, sort: "createdAt,desc", page: 3 })).toBe("/api/v1/vendors/export.csv?status=ACTIVE");
  });
  it("carries the current filters, encoded", () => {
    const url = exportUrl({ ...DEFAULT_LIST_STATE, q: "a&b c", status: "ALL", category: "Lawn care", compliance: "NON_COMPLIANT" });
    const params = new URL(url, "http://x").searchParams;
    expect(params.get("q")).toBe("a&b c");
    expect(params.get("status")).toBe("ALL");
    expect(params.get("category")).toBe("Lawn care");
    expect(params.get("compliance")).toBe("NON_COMPLIANT");
  });
  it("flags more than 10,000 rows", () => {
    expect(exportTooLarge(10_000)).toBe(false);
    expect(exportTooLarge(10_001)).toBe(true);
  });
});
