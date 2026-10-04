import { describe, expect, it } from "vitest";
import { settingsNavItems } from "./settings-nav";

const labels = (role: Parameters<typeof settingsNavItems>[0]) => settingsNavItems(role).map((i) => i.label);

describe("settingsNavItems", () => {
  it("shows Email activity to owners and admins only", () => {
    expect(labels("OWNER")).toContain("Email activity");
    expect(labels("ADMIN")).toContain("Email activity");
    expect(labels("MEMBER")).not.toContain("Email activity");
    expect(labels("VIEWER")).not.toContain("Email activity");
    expect(labels(null)).not.toContain("Email activity");
  });
  it("always lists Organization and Members", () => {
    expect(labels("VIEWER")).toEqual(["Organization", "Members", "Billing"]);
  });
});
