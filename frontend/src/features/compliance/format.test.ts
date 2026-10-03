import { describe, expect, it } from "vitest";
import { compactCounts, complianceSummaryText, expirationText, nextExpirationLabel, relativeDays } from "./format";
import type { ComplianceSummary } from "./types";

const summary = (o: Partial<ComplianceSummary> = {}): ComplianceSummary => ({
  status: "NON_COMPLIANT", missing: 0, expired: 0, expiring: 0, reviewRequired: 0, ok: 0, nextExpiration: null, daysUntilNextExpiration: null, ...o,
});

describe("relative days", () => {
  it("handles zero, singular, plural and negative", () => {
    expect(relativeDays(0)).toBe("today");
    expect(relativeDays(1)).toBe("in 1 day");
    expect(relativeDays(12)).toBe("in 12 days");
    expect(relativeDays(-1)).toBe("1 day ago");
    expect(relativeDays(-3)).toBe("3 days ago");
  });

  it("formats requirement expiration text", () => {
    expect(expirationText(12)).toBe("Expires in 12 days");
    expect(expirationText(1)).toBe("Expires in 1 day");
    expect(expirationText(0)).toBe("Expires today");
    expect(expirationText(-3)).toBe("Expired 3 days ago");
    expect(expirationText(null)).toBeNull();
  });

  it("labels the next expiration from the server-provided day count (org time zone)", () => {
    expect(nextExpirationLabel(10)).toBe("in 10 days");
    expect(nextExpirationLabel(-4)).toBe("4 days ago");
    expect(nextExpirationLabel(0)).toBe("today");
    expect(nextExpirationLabel(null)).toBeNull();
  });
});

describe("compact counts", () => {
  it("shows only non-zero counts, at most two, most urgent first", () => {
    expect(compactCounts(summary({ missing: 2, expiring: 1 }))).toEqual(["2 missing", "1 expiring"]);
    expect(compactCounts(summary({ missing: 2, expired: 1, expiring: 4 }))).toEqual(["2 missing", "1 expired"]);
    expect(compactCounts(summary({ ok: 3 }))).toEqual([]);
  });

  it("builds the full header line", () => {
    expect(complianceSummaryText(summary({ missing: 1, expiring: 2, ok: 3 }))).toBe("1 missing · 2 expiring · 3 OK");
  });
});
