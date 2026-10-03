import { describe, expect, it } from "vitest";
import { inviteFormSchema, organizationFormSchema, parseReminderOffsets, toFormValues, toUpdateRequest } from "./schemas";
import { buildTimeZoneOptions } from "./time-zones";

describe("parseReminderOffsets", () => {
  it("parses, trims and sorts descending", () => {
    expect(parseReminderOffsets("7, 30 ,14")).toEqual({ ok: true, values: [30, 14, 7] });
  });
  it.each([
    ["", /at least one/i],
    [" , ", /at least one/i],
    ["7, abc", /whole numbers/i],
    ["7.5", /whole numbers/i],
    ["0, 7", /between 1 and 180/],
    ["181", /between 1 and 180/],
    ["7, 7", /duplicate/i],
    ["1,2,3,4,5,6", /at most 5/],
  ])("rejects %j", (input, message) => {
    const result = parseReminderOffsets(input);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toMatch(message);
  });
  it("accepts exactly 5 values", () => {
    expect(parseReminderOffsets("1,2,3,4,5").ok).toBe(true);
  });
});

describe("organizationFormSchema", () => {
  const valid = {
    name: "Acme",
    timeZone: "America/Chicago",
    expiringWindowDays: "30",
    reminderOffsets: "30, 7",
    remindersEnabled: true,
  };
  it("accepts valid values and builds the PATCH body", () => {
    const parsed = organizationFormSchema.parse(valid);
    expect(toUpdateRequest(parsed)).toEqual({
      name: "Acme",
      timeZone: "America/Chicago",
      expiringWindowDays: 30,
      reminderOffsetsDays: [30, 7],
      remindersEnabled: true,
    });
  });
  it("rejects out-of-range window days and bad offsets with messages", () => {
    const result = organizationFormSchema.safeParse({ ...valid, expiringWindowDays: "181", reminderOffsets: "0" });
    expect(result.success).toBe(false);
    const messages = result.error?.issues.map((i) => i.message) ?? [];
    expect(messages).toContain("Enter a number from 1 to 180.");
    expect(messages).toContain("Each value must be between 1 and 180 days.");
  });
  it("round-trips an organization into form values", () => {
    expect(
      toFormValues({
        id: "1",
        name: "A",
        timeZone: "UTC",
        expiringWindowDays: 45,
        reminderOffsetsDays: [30, 14],
        remindersEnabled: false,
        createdAt: "",
      }),
    ).toEqual({ name: "A", timeZone: "UTC", expiringWindowDays: "45", reminderOffsets: "30, 14", remindersEnabled: false });
  });
});

describe("inviteFormSchema", () => {
  it("never allows OWNER and requires a valid email", () => {
    expect(inviteFormSchema.safeParse({ email: "a@b.co", role: "OWNER" }).success).toBe(false);
    expect(inviteFormSchema.safeParse({ email: "nope", role: "MEMBER" }).success).toBe(false);
    expect(inviteFormSchema.safeParse({ email: " a@b.co ", role: "MEMBER" }).success).toBe(true);
  });
});

describe("buildTimeZoneOptions", () => {
  it("keeps US zones first, de-duplicates, and includes the current zone", () => {
    const options = buildTimeZoneOptions("Europe/Paris", ["America/New_York", "Europe/Berlin"]);
    expect(options.us[0].value).toBe("America/New_York");
    expect(options.other).toEqual(["Europe/Berlin", "Europe/Paris"]);
  });
});
