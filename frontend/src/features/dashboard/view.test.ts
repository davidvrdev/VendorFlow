import { describe, expect, it } from "vitest";
import type { Role } from "@/features/organization/types";
import type { AttentionItem, DashboardSummary } from "./types";
import { actionAriaLabel, attentionHref, dashboardState, documentsLine, parseAttentionPage, rowAction, summaryTiles } from "./view";

const summary = (over: Partial<DashboardSummary> = {}): DashboardSummary => ({
  today: "2030-05-01",
  expiringWindowDays: 30,
  vendors: { active: 2, compliant: 0, attention: 0, nonCompliant: 2, noRequirements: 0 },
  documents: { missing: 6, expired: 0, expiring: 0, reviewRequired: 0 },
  ...over,
});

describe("summaryTiles", () => {
  it("links every tile to the vendors list filtered like its count", () => {
    const tiles = summaryTiles(summary());
    expect(tiles.map((t) => [t.label, t.value, t.href])).toEqual([
      ["Active vendors", 2, "/vendors"],
      ["Compliant", 0, "/vendors?compliance=COMPLIANT"],
      ["Needs attention", 0, "/vendors?compliance=ATTENTION"],
      ["Non-compliant", 2, "/vendors?compliance=NON_COMPLIANT"],
    ]);
  });
});

describe("documentsLine", () => {
  it("lists only non-zero counts in urgency order with the window", () => {
    const line = documentsLine(summary({ documents: { missing: 6, expired: 1, expiring: 2, reviewRequired: 3 } }));
    expect(line).toBe("6 missing · 1 expired · 2 expiring in the next 30 days · 3 to review");
  });
  it("skips zeros and returns null when nothing needs action", () => {
    expect(documentsLine(summary({ documents: { missing: 0, expired: 0, expiring: 4, reviewRequired: 0 } }))).toBe("4 expiring in the next 30 days");
    expect(documentsLine(summary({ documents: { missing: 0, expired: 0, expiring: 0, reviewRequired: 0 } }))).toBeNull();
  });
});

describe("dashboardState", () => {
  it("picks the state from active vendors and the attention total", () => {
    const none = summary({ vendors: { active: 0, compliant: 0, attention: 0, nonCompliant: 0, noRequirements: 0 } });
    expect(dashboardState(none, 0)).toBe("no-vendors");
    expect(dashboardState(summary(), 0)).toBe("all-clear");
    expect(dashboardState(summary(), 3)).toBe("attention");
  });
});

describe("rowAction", () => {
  const cases: [AttentionItem["action"], string | null, Role, string][] = [
    ["UPLOAD", null, "MEMBER", "upload"],
    ["UPLOAD", null, "VIEWER", "link"],
    ["UPLOAD_RENEWAL", "d1", "ADMIN", "upload-renewal"],
    ["UPLOAD_RENEWAL", "d1", "VIEWER", "link"],
    ["REVIEW", "d1", "OWNER", "review"],
    ["REVIEW", "d1", "VIEWER", "link"],
    ["REVIEW", null, "OWNER", "link"],
  ];
  it.each(cases)("%s (doc %s) for %s -> %s", (action, documentId, role, kind) => {
    expect(rowAction({ action, documentId }, role).kind).toBe(kind);
  });
  it("builds descriptive accessible names", () => {
    const names = { vendorName: "Acme Plumbing", documentTypeName: "Certificate of Insurance" };
    expect(actionAriaLabel({ kind: "upload", label: "Upload" }, names)).toBe("Upload Certificate of Insurance for Acme Plumbing");
    expect(actionAriaLabel({ kind: "link", label: "View vendor" }, names)).toBe("View Acme Plumbing");
  });
});

describe("attention paging", () => {
  it("builds hrefs and parses safely", () => {
    expect(attentionHref(1)).toBe("/dashboard");
    expect(attentionHref(3)).toBe("/dashboard?attentionPage=3");
    expect(parseAttentionPage("2")).toBe(2);
    expect(parseAttentionPage("-1")).toBe(1);
    expect(parseAttentionPage("abc")).toBe(1);
    expect(parseAttentionPage(undefined)).toBe(1);
  });
});
