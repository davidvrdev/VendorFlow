import { describe, expect, it } from "vitest";
import { describeHistoryEvent } from "./history";
import type { HistoryEvent } from "./types";

const event = (action: string, changes: HistoryEvent["changes"] = null): HistoryEvent => ({
  id: "e1",
  action,
  actor: null,
  occurredAt: "2030-01-01T00:00:00Z",
  changes,
});

describe("describeHistoryEvent", () => {
  it("labels simple lifecycle events", () => {
    expect(describeHistoryEvent(event("vendor.created")).label).toBe("Vendor created");
    expect(describeHistoryEvent(event("vendor.deactivated")).label).toBe("Vendor deactivated");
    expect(describeHistoryEvent(event("vendor.reactivated")).label).toBe("Vendor reactivated");
  });

  it("lists changed fields with before and after, naming empty values", () => {
    const result = describeHistoryEvent(
      event("vendor.updated", {
        contactName: { before: null, after: "Dana" },
        phone: { before: "555-0100", after: "" },
        somethingNew: { before: 1, after: 2 },
      }),
    );
    expect(result.label).toBe("Details updated");
    expect(result.details).toEqual(["Contact name: empty → Dana", "Phone: 555-0100 → empty", "somethingNew: 1 → 2"]);
  });

  it("handles an update without recorded changes", () => {
    expect(describeHistoryEvent(event("vendor.updated")).details).toEqual([]);
  });

  it("truncates very long values", () => {
    const [line] = describeHistoryEvent(event("vendor.updated", { notes: { before: "x".repeat(500), after: "y" } })).details;
    expect(line.length).toBeLessThan(120);
    expect(line).toContain("…");
  });

  it("describes requirement changes using type names when known, codes otherwise", () => {
    const changes = { added: { before: null, after: ["GENERAL_LIABILITY", "UNKNOWN_CODE"] }, removed: { before: ["W9"], after: null } };
    expect(describeHistoryEvent(event("vendor.requirements_changed", changes), { GENERAL_LIABILITY: "General Liability", W9: "W-9" }).label).toBe(
      "Requirements changed: added General Liability, UNKNOWN_CODE, removed W-9",
    );
  });

  it("accepts bare arrays and before/after snapshots for requirement changes", () => {
    expect(describeHistoryEvent(event("vendor.requirements_changed", { added: ["A"] } as unknown as HistoryEvent["changes"])).label).toBe(
      "Requirements changed: added A",
    );
    expect(describeHistoryEvent(event("vendor.requirements_changed", { requirements: { before: ["A", "B"], after: ["B", "C"] } })).label).toBe(
      "Requirements changed: added C, removed A",
    );
  });

  it("falls back to a plain label when the requirement delta is unknown", () => {
    expect(describeHistoryEvent(event("vendor.requirements_changed")).label).toBe("Requirements changed");
  });

  it("renders unknown actions generically", () => {
    expect(describeHistoryEvent(event("vendor.teleported")).label).toBe("Activity recorded");
  });
});
