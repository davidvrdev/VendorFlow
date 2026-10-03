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

describe("describeHistoryEvent: document events", () => {
  const names = { COI: "Certificate of Insurance" };
  const meta = (value: unknown) => value as HistoryEvent["changes"];

  it("labels each document action even without metadata", () => {
    expect(describeHistoryEvent(event("document.uploaded")).label).toBe("Document uploaded");
    expect(describeHistoryEvent(event("document.superseded")).label).toBe("Document replaced by a newer upload");
    expect(describeHistoryEvent(event("document.archived")).label).toBe("Document archived");
    expect(describeHistoryEvent(event("document.downloaded")).label).toBe("Document downloaded");
    expect(describeHistoryEvent(event("document.reviewed")).label).toBe("Document reviewed");
    expect(describeHistoryEvent(event("document.dates_changed")).label).toBe("Document dates changed");
  });

  it("names the document type and file when the metadata has them (flat or before/after shape)", () => {
    const flat = meta({ filename: "coi.pdf", typeCode: "COI" });
    expect(describeHistoryEvent(event("document.uploaded", flat), names).details).toEqual(["Certificate of Insurance: coi.pdf"]);
    const wrapped = { originalFilename: { before: null, after: "coi.pdf" } };
    expect(describeHistoryEvent(event("document.uploaded", wrapped)).details).toEqual(["coi.pdf"]);
  });

  it("shows approve/reject decisions and the rejection note", () => {
    expect(describeHistoryEvent(event("document.reviewed", meta({ decision: "APPROVED" }))).label).toBe("Document approved");
    const rejected = describeHistoryEvent(event("document.reviewed", meta({ decision: { before: null, after: "REJECTED" }, note: "Illegible scan" })));
    expect(rejected.label).toBe("Document rejected");
    expect(rejected.details).toContain("Note: Illegible scan");
  });

  it("lists date changes", () => {
    const result = describeHistoryEvent(
      event("document.dates_changed", {
        expirationDate: { before: "2030-01-01", after: "2031-01-01" },
        issueDate: { before: null, after: "2030-01-01" },
      }),
    );
    expect(result.details).toEqual(["Issue date: empty → 2030-01-01", "Expiration date: 2030-01-01 → 2031-01-01"]);
  });

  it("never throws on unexpected shapes", () => {
    const weird = meta({ decision: 42, note: {}, filename: ["x"] });
    expect(describeHistoryEvent(event("document.reviewed", weird)).label).toBe("Document reviewed");
  });
});
