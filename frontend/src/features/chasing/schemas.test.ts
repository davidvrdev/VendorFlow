import { describe, expect, it } from "vitest";
import { chasingSettings } from "./fixtures";
import { chasingSettingsFormSchema, toChasingFormValues, toChasingRequest } from "./schemas";
import { chaseDateIso, chasingStatusLabel } from "./view";

const valid = () => toChasingFormValues(chasingSettings({ enabled: true, ccStaff: true }));

function problems(values: Record<string, unknown>): Record<string, string> {
  const result = chasingSettingsFormSchema.safeParse(values);
  if (result.success) return {};
  return Object.fromEntries(result.error.issues.map((issue) => [String(issue.path[0]), issue.message]));
}

describe("chasingSettingsFormSchema", () => {
  it("accepts the defaults and round-trips to the PUT body with every field", () => {
    const parsed = chasingSettingsFormSchema.parse(valid());
    expect(toChasingRequest(parsed)).toEqual({ enabled: true, cadenceDays: 7, maxAttempts: 4, leadDays: 30, sendHourLocal: 9, ccStaff: true });
  });

  it.each([
    ["cadenceDays", "2"],
    ["cadenceDays", "31"],
    ["maxAttempts", "0"],
    ["maxAttempts", "11"],
    ["leadDays", "6"],
    ["leadDays", "91"],
    ["sendHourLocal", "24"],
    ["sendHourLocal", "-1"],
    ["sendHourLocal", "9.5"],
    ["sendHourLocal", ""],
    ["cadenceDays", "abc"],
  ])("rejects %s = %j", (field, value) => {
    expect(problems({ ...valid(), [field]: value })[field]).toMatch(/whole number from/);
  });

  it("accepts the range edges", () => {
    expect(problems({ ...valid(), cadenceDays: "3", maxAttempts: "10", leadDays: "90", sendHourLocal: "0" })).toEqual({});
    expect(problems({ ...valid(), cadenceDays: "30", maxAttempts: "1", leadDays: "7", sendHourLocal: "23" })).toEqual({});
  });
});

describe("view helpers", () => {
  it("labels opt-out as Vendor unsubscribed and manual pause as Paused", () => {
    expect(chasingStatusLabel({ status: "PAUSED", pausedReason: "OPT_OUT" })).toBe("Vendor unsubscribed");
    expect(chasingStatusLabel({ status: "PAUSED", pausedReason: "MANUAL" })).toBe("Paused");
    expect(chasingStatusLabel({ status: "NO_EMAIL", pausedReason: null })).toBe("No email address");
  });

  it("anchors an org-local date to UTC midnight so it never shifts a day", () => {
    expect(chaseDateIso("2030-01-10")).toBe("2030-01-10T00:00:00Z");
  });
});
