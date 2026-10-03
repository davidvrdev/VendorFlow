import { describe, expect, it } from "vitest";
import {
  buildDatesSchema,
  buildUploadSchema,
  documentTypeFormSchema,
  FILE_TOO_LARGE_MESSAGE,
  FILE_TYPE_MESSAGE,
  isCalendarDate,
  MAX_FILE_BYTES,
  rejectNoteSchema,
  validateDates,
  validateFile,
} from "./schemas";

const types = [
  { id: "coi", hasExpiration: true },
  { id: "w9", hasExpiration: false },
];

const file = (name: string, size = 1000) => ({ name, size }) as File;

function issues(result: { success: boolean; error?: { issues: { path: PropertyKey[]; message: string }[] } }) {
  return Object.fromEntries((result.error?.issues ?? []).map((issue) => [String(issue.path[0]), issue.message]));
}

describe("validateFile", () => {
  it("accepts pdf/png/jpg/jpeg in any case", () => {
    for (const name of ["a.pdf", "b.PNG", "c.jpg", "d.JPEG", "my.insurance.v2.pdf"]) expect(validateFile(file(name))).toBeNull();
  });

  it("requires a file", () => {
    expect(validateFile(null)).toBe("Choose a file to upload.");
  });

  it("rejects empty files", () => {
    expect(validateFile(file("a.pdf", 0))).toBe("The selected file is empty.");
  });

  it("allows exactly 15 MB and rejects one byte more", () => {
    expect(validateFile(file("a.pdf", MAX_FILE_BYTES))).toBeNull();
    expect(validateFile(file("a.pdf", MAX_FILE_BYTES + 1))).toBe(FILE_TOO_LARGE_MESSAGE);
  });

  it("rejects other extensions and names without one", () => {
    for (const name of ["a.exe", "a.pdf.exe", "a.docx", "pdf", "a."]) expect(validateFile(file(name))).toBe(FILE_TYPE_MESSAGE);
  });
});

describe("dates", () => {
  it("recognises real calendar dates only", () => {
    expect(isCalendarDate("2030-02-28")).toBe(true);
    expect(isCalendarDate("2030-02-31")).toBe(false);
    expect(isCalendarDate("2030-2-1")).toBe(false);
    expect(isCalendarDate("")).toBe(false);
  });

  it("enforces the 1990-2100 range on both dates", () => {
    expect(validateDates("1989-12-31", "", false).issueDate).toMatch(/between 1990 and 2100/);
    expect(validateDates("", "2101-01-01", false).expirationDate).toMatch(/between 1990 and 2100/);
    expect(validateDates("1990-01-01", "2100-12-31", true)).toEqual({});
  });

  it("requires issue date <= expiration date", () => {
    expect(validateDates("2030-06-02", "2030-06-01", true).expirationDate).toBe("Expiration date must be on or after the issue date.");
    expect(validateDates("2030-06-01", "2030-06-01", true)).toEqual({});
  });

  it("requires an expiration date only when asked", () => {
    expect(validateDates("", "", true).expirationDate).toMatch(/Enter the expiration date/);
    expect(validateDates("", "", false)).toEqual({});
  });
});

describe("buildUploadSchema", () => {
  const base = { documentTypeId: "coi", file: file("coi.pdf"), issueDate: "2030-01-01", expirationDate: "2031-01-01" };

  it("accepts a complete upload and turns empty dates into null", () => {
    const parsed = buildUploadSchema(types).parse({ ...base, documentTypeId: "w9", issueDate: "", expirationDate: "" });
    expect(parsed.issueDate).toBeNull();
    expect(parsed.expirationDate).toBeNull();
    expect(parsed.documentTypeId).toBe("w9");
  });

  it("requires a type", () => {
    expect(issues(buildUploadSchema(types).safeParse({ ...base, documentTypeId: "" })).documentTypeId).toBe("Choose a document type.");
  });

  it("requires the expiration date iff the type has expiration", () => {
    expect(issues(buildUploadSchema(types).safeParse({ ...base, expirationDate: "" })).expirationDate).toMatch(/Enter the expiration date/);
    expect(buildUploadSchema(types).safeParse({ ...base, documentTypeId: "w9", expirationDate: "" }).success).toBe(true);
  });

  it("reports file problems on the file field", () => {
    expect(issues(buildUploadSchema(types).safeParse({ ...base, file: null })).file).toBe("Choose a file to upload.");
    expect(issues(buildUploadSchema(types).safeParse({ ...base, file: file("x.exe") })).file).toBe(FILE_TYPE_MESSAGE);
    expect(issues(buildUploadSchema(types).safeParse({ ...base, file: file("x.pdf", MAX_FILE_BYTES + 1) })).file).toBe(FILE_TOO_LARGE_MESSAGE);
  });

  it("checks the date order", () => {
    expect(issues(buildUploadSchema(types).safeParse({ ...base, issueDate: "2032-01-01" })).expirationDate).toMatch(/on or after the issue date/);
  });
});

describe("buildDatesSchema", () => {
  it("keeps the expiration date required for expiring types and allows clearing the issue date", () => {
    expect(buildDatesSchema(true).safeParse({ issueDate: "", expirationDate: "" }).success).toBe(false);
    expect(buildDatesSchema(true).parse({ issueDate: "", expirationDate: "2031-01-01" })).toEqual({ issueDate: null, expirationDate: "2031-01-01" });
  });
});

describe("rejectNoteSchema", () => {
  it("requires a trimmed note of 1-1000 characters", () => {
    expect(rejectNoteSchema.safeParse({ note: "   " }).success).toBe(false);
    expect(rejectNoteSchema.safeParse({ note: "x".repeat(1001) }).success).toBe(false);
    expect(rejectNoteSchema.parse({ note: "  Expired  " }).note).toBe("Expired");
  });
});

describe("documentTypeFormSchema", () => {
  it("requires a name and trims it", () => {
    expect(documentTypeFormSchema.safeParse({ name: " ", hasExpiration: false, requiredByDefault: false }).success).toBe(false);
    expect(documentTypeFormSchema.parse({ name: " Pollution Liability ", hasExpiration: true, requiredByDefault: false }).name).toBe("Pollution Liability");
  });
});
