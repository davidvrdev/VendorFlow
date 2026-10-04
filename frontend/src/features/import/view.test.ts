import { describe, expect, it } from "vitest";
import { validateCsvFile } from "./schemas";
import type { ImportPreviewRow } from "./types";
import { confirmQuestion, filterCount, filterRows, resultText, rowErrorsText } from "./view";

const row = (rowNumber: number, action: ImportPreviewRow["action"]): ImportPreviewRow => ({
  rowNumber,
  companyName: `Co ${rowNumber}`,
  action,
  changes: [],
  errors: action === "ERROR" ? [{ field: "email", message: "Invalid email" }] : [],
});
const rows = [row(1, "CREATE"), row(2, "UPDATE"), row(3, "ERROR"), row(4, "UNCHANGED"), row(5, "CREATE")];

describe("filterRows / filterCount", () => {
  it("filters by action", () => {
    expect(filterRows(rows, "ALL")).toHaveLength(5);
    expect(filterRows(rows, "ERROR").map((r) => r.rowNumber)).toEqual([3]);
    expect(filterRows(rows, "CREATE").map((r) => r.rowNumber)).toEqual([1, 5]);
    expect(filterRows(rows, "UPDATE").map((r) => r.rowNumber)).toEqual([2]);
  });
  it("reads counts from the summary", () => {
    const summary = { total: 5, create: 2, update: 1, unchanged: 1, error: 1 };
    expect(filterCount(summary, "ALL")).toBe(5);
    expect(filterCount(summary, "CREATE")).toBe(2);
    expect(filterCount(summary, "ERROR")).toBe(1);
  });
});

describe("summary text", () => {
  it("builds the confirmation question", () => {
    expect(confirmQuestion({ create: 2, update: 1 })).toBe("Create 2 vendors and update 1 vendor?");
    expect(confirmQuestion({ create: 1, update: 0 })).toBe("Create 1 vendor?");
    expect(confirmQuestion({ create: 0, update: 3 })).toBe("Update 3 vendors?");
  });
  it("builds the result text", () => {
    expect(resultText({ created: 2, updated: 1, unchanged: 4 })).toBe("2 vendors created, 1 vendor updated, 4 unchanged.");
  });
  it("names the column in row errors", () => {
    expect(rowErrorsText(row(3, "ERROR"))).toEqual(["email: Invalid email"]);
  });
});

describe("validateCsvFile", () => {
  it("accepts a small csv, case-insensitively", () => {
    expect(validateCsvFile({ name: "Vendors.CSV", size: 100 })).toBeNull();
  });
  it("rejects missing, empty, big and non-csv files", () => {
    expect(validateCsvFile(null)).toMatch(/Choose/);
    expect(validateCsvFile({ name: "a.csv", size: 0 })).toMatch(/empty/);
    expect(validateCsvFile({ name: "a.csv", size: 1024 * 1024 + 1 })).toMatch(/1 MB/);
    expect(validateCsvFile({ name: "a.xlsx", size: 10 })).toMatch(/\.csv/);
    expect(validateCsvFile({ name: "a.csv", size: 1024 * 1024 })).toBeNull();
  });
});
