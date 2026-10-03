import { describe, expect, it } from "vitest";
import { inviteAccountSchema, resetPasswordSchema, signupSchema } from "./schemas";
import { organizationFormSchema } from "@/features/organization/schemas";

// Built from code points so the test file itself contains no invisible characters.
const BIDI_OVERRIDE = String.fromCharCode(0x202e);
const NUL = String.fromCharCode(0);

describe("password byte limit (bcrypt, 72 UTF-8 bytes) on every new-password form", () => {
  const twoByte = "ñ".repeat(40); // 40 characters, 80 bytes
  it("applies to reset-password", () => {
    const ok = (p: string) => resetPasswordSchema.safeParse({ newPassword: p, confirmPassword: p }).success;
    expect(ok("x".repeat(72))).toBe(true);
    expect(ok("x".repeat(73))).toBe(false);
    expect(ok(twoByte)).toBe(false);
    expect(ok("x".repeat(11))).toBe(false);
  });
  it("applies to the invite-accept new-account form", () => {
    const ok = (p: string) => inviteAccountSchema.safeParse({ fullName: "Sam", password: p }).success;
    expect(ok("x".repeat(72))).toBe(true);
    expect(ok("x".repeat(73))).toBe(false);
    expect(ok(twoByte)).toBe(false);
  });
  it("reset-password requires matching confirmation", () => {
    const result = resetPasswordSchema.safeParse({ newPassword: "x".repeat(14), confirmPassword: "y".repeat(14) });
    expect(result.success).toBe(false);
  });
});

describe("names reject control and bidi-override characters (mirrors the backend)", () => {
  const signup = { fullName: "Sam", email: "s@example.com", password: "x".repeat(14), organizationName: "Acme" };
  it("full name", () => {
    expect(signupSchema.safeParse({ ...signup, fullName: `Sam${BIDI_OVERRIDE}` }).success).toBe(false);
    expect(signupSchema.safeParse({ ...signup, fullName: `Sa${NUL}m` }).success).toBe(false);
    expect(signupSchema.safeParse({ ...signup, fullName: "Zoë Müller-Ñandú" }).success).toBe(true);
    expect(inviteAccountSchema.safeParse({ fullName: `A${NUL}`, password: "x".repeat(14) }).success).toBe(false);
  });
  it("organization name (signup and settings)", () => {
    expect(signupSchema.safeParse({ ...signup, organizationName: `Acme${BIDI_OVERRIDE}` }).success).toBe(false);
    const settings = {
      name: `Acme${NUL}`,
      timeZone: "UTC",
      expiringWindowDays: "30",
      reminderOffsets: "30, 7",
      remindersEnabled: true,
    };
    expect(organizationFormSchema.safeParse(settings).success).toBe(false);
    expect(organizationFormSchema.safeParse({ ...settings, name: "Acme & Sons" }).success).toBe(true);
  });
});
