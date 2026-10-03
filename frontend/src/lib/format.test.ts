import { describe, expect, it } from "vitest";
import { formatCalendarDate, formatFileSize, truncateFilename } from "./format";

describe("truncateFilename", () => {
  it("leaves short names alone", () => {
    expect(truncateFilename("coi.pdf")).toBe("coi.pdf");
  });

  it("shortens long names but keeps the extension", () => {
    const name = `${"very-long-insurance-certificate-".repeat(4)}final.pdf`;
    const short = truncateFilename(name, 30);
    expect(short.length).toBeLessThanOrEqual(30);
    expect(short.endsWith("….pdf")).toBe(true);
    expect(short.startsWith("very-long")).toBe(true);
  });

  it("handles names without an extension", () => {
    expect(truncateFilename("x".repeat(50), 20)).toBe(`${"x".repeat(19)}…`);
  });
});

describe("formatFileSize", () => {
  it("uses B, KB and MB", () => {
    expect(formatFileSize(512)).toBe("512 B");
    expect(formatFileSize(2048)).toBe("2 KB");
    expect(formatFileSize(1.5 * 1024 * 1024)).toBe("1.5 MB");
    expect(formatFileSize(15 * 1024 * 1024)).toBe("15 MB");
  });
});

describe("formatCalendarDate", () => {
  it("formats yyyy-MM-dd without shifting the day", () => {
    expect(formatCalendarDate("2030-01-05")).toBe("Jan 5, 2030");
    expect(formatCalendarDate(null)).toBe("");
  });
});
