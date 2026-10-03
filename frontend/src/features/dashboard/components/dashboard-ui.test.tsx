import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { AttentionItem, DashboardSummary } from "../types";
import { AttentionList } from "./attention-list";
import { SummaryTiles } from "./summary-tiles";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const items: AttentionItem[] = [
  { vendorId: "v1", vendorName: "Acme Plumbing", documentTypeId: "t1", documentTypeName: "Certificate of Insurance", status: "EXPIRED", documentId: "d1", expirationDate: "2030-04-28", daysUntilExpiration: -3, action: "UPLOAD" },
  { vendorId: "v2", vendorName: "Bolt Electric", documentTypeId: "t2", documentTypeName: "W-9", status: "MISSING", documentId: null, expirationDate: null, daysUntilExpiration: null, action: "UPLOAD" },
  { vendorId: "v3", vendorName: "Cedar Roofing", documentTypeId: "t1", documentTypeName: "Certificate of Insurance", status: "REVIEW_REQUIRED", documentId: "d3", expirationDate: "2030-09-01", daysUntilExpiration: 123, action: "REVIEW" },
];

describe("AttentionList", () => {
  it("renders a list with status, server-provided expiry text and one action per row", () => {
    render(<AttentionList items={items} role="MEMBER" documentTypes={[]} />);
    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByRole("link", { name: "Acme Plumbing" })).toHaveAttribute("href", "/vendors/v1");
    expect(within(rows[0]).getByText("Expired")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Expired 3 days ago")).toBeInTheDocument();
    expect(within(rows[0]).getByRole("button", { name: "Upload Certificate of Insurance for Acme Plumbing" })).toBeInTheDocument();
    expect(within(rows[1]).getByText("Missing")).toBeInTheDocument();
    expect(within(rows[2]).getByRole("button", { name: "Review Certificate of Insurance for Cedar Roofing" })).toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(3);
  });

  it("gives viewers links instead of buttons", () => {
    render(<AttentionList items={items} role="VIEWER" documentTypes={[]} />);
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(screen.getByRole("link", { name: "View Bolt Electric" })).toHaveAttribute("href", "/vendors/v2");
  });
});

describe("SummaryTiles", () => {
  const summary: DashboardSummary = {
    today: "2030-05-01",
    expiringWindowDays: 30,
    vendors: { active: 2, compliant: 0, attention: 0, nonCompliant: 2, noRequirements: 0 },
    documents: { missing: 6, expired: 0, expiring: 0, reviewRequired: 0 },
  };
  it("renders linked tiles, the documents line and the as-of date", () => {
    render(<SummaryTiles summary={summary} />);
    const tiles = screen.getAllByRole("listitem");
    expect(tiles).toHaveLength(4);
    expect(within(tiles[3]).getByRole("link")).toHaveAttribute("href", "/vendors?compliance=NON_COMPLIANT");
    expect(within(tiles[3]).getByText("2")).toBeInTheDocument();
    expect(screen.getByText("Documents: 6 missing")).toBeInTheDocument();
    expect(screen.getByText("As of 2030-05-01")).toBeInTheDocument();
  });
});
