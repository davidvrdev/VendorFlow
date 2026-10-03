import { describe, expect, it } from "vitest";
import { EMPTY_VENDOR_FORM, toVendorInput, vendorFormSchema } from "./schemas";

const ok = { ...EMPTY_VENDOR_FORM, companyName: "Acme Plumbing" };
const parse = (overrides: Partial<typeof ok>) => vendorFormSchema.safeParse({ ...ok, ...overrides });

describe("vendorFormSchema", () => {
  it("accepts just a company name and turns empty optional fields into null", () => {
    const result = parse({});
    expect(result.success).toBe(true);
    expect(result.data).toEqual({
      companyName: "Acme Plumbing",
      contactName: null,
      email: null,
      phone: null,
      category: null,
      notes: null,
    });
  });

  it("trims strings and treats whitespace-only optional fields as null", () => {
    const result = parse({ companyName: "  Acme  ", contactName: "   ", category: " Roofing " });
    expect(result.data).toMatchObject({ companyName: "Acme", contactName: null, category: "Roofing" });
  });

  it("requires a company name of at most 200 characters", () => {
    expect(parse({ companyName: "   " }).success).toBe(false);
    expect(parse({ companyName: "x".repeat(200) }).success).toBe(true);
    expect(parse({ companyName: "x".repeat(201) }).success).toBe(false);
  });

  it("enforces the other length limits", () => {
    expect(parse({ contactName: "x".repeat(120) }).success).toBe(true);
    expect(parse({ contactName: "x".repeat(121) }).success).toBe(false);
    expect(parse({ category: "x".repeat(61) }).success).toBe(false);
    expect(parse({ notes: "x".repeat(5000) }).success).toBe(true);
    expect(parse({ notes: "x".repeat(5001) }).success).toBe(false);
    expect(parse({ phone: "1".repeat(41) }).success).toBe(false);
  });

  it("validates email format and length", () => {
    expect(parse({ email: "a@b.co" }).success).toBe(true);
    expect(parse({ email: "not-an-email" }).success).toBe(false);
    expect(parse({ email: `${"a".repeat(250)}@b.co` }).success).toBe(false);
  });

  it("accepts phone numbers with the allowed characters only", () => {
    expect(parse({ phone: "+1 (555) 010-2030 ext 12" }).success).toBe(true);
    expect(parse({ phone: "555.010.2030 x45" }).success).toBe(true);
    expect(parse({ phone: "call me" }).success).toBe(false);
    expect(parse({ phone: "555-0100; DROP" }).success).toBe(false);
  });

  it("rejects control and bidi characters", () => {
    expect(parse({ companyName: "Acme\u0000" }).success).toBe(false);
    expect(parse({ companyName: "Acme‮gnimulp" }).success).toBe(false);
    expect(parse({ contactName: "Bob⁦" }).success).toBe(false);
    expect(parse({ category: "Roof\nIng" }).success).toBe(false);
    expect(parse({ notes: "bad\u0007bell" }).success).toBe(false);
  });

  it("allows line breaks in notes", () => {
    expect(parse({ notes: "line one\nline two\r\n\tindented" }).success).toBe(true);
  });

  it("builds the API payload from parsed values", () => {
    const parsed = vendorFormSchema.parse({ ...ok, email: " a@b.co " });
    expect(toVendorInput(parsed)).toMatchObject({ companyName: "Acme Plumbing", email: "a@b.co", phone: null });
  });
});
