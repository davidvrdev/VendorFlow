import { describe, expect, it } from "vitest";
import { kindLabel, statusLabel } from "./labels";

describe("kindLabel", () => {
  it("names known kinds", () => {
    expect(kindLabel("DOCUMENT_REQUEST")).toBe("Document request");
    expect(kindLabel("COMPLIANCE_DIGEST")).toBe("Compliance digest");
  });
  it("humanizes unknown kinds", () => {
    expect(kindLabel("SOMETHING_NEW")).toBe("Something new");
    expect(kindLabel("")).toBe("Email");
  });
});

describe("statusLabel", () => {
  it.each([
    ["SENT", "Sent"],
    ["PENDING", "Queued"],
    ["SENDING", "Queued"],
    ["FAILED", "Retrying"],
    ["DEAD", "Failed"],
  ] as const)("%s -> %s", (status, text) => {
    expect(statusLabel(status).text).toBe(text);
  });
});
