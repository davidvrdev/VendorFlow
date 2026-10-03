import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { errorMessage } from "@/lib/forms/api-errors";
import type { Role } from "@/features/organization/types";
import { REQUEST_ERROR_MESSAGES, requestAvailability, requestSentMessage } from "./request";

const ROLES: Role[] = ["OWNER", "ADMIN", "MEMBER", "VIEWER"];
const STATUSES = ["MISSING", "EXPIRED", "EXPIRING", "OK", "REVIEW_REQUIRED"];
const REQUESTABLE = ["MISSING", "EXPIRED", "EXPIRING"];

describe("requestAvailability", () => {
  for (const status of STATUSES) {
    for (const role of ROLES) {
      it(`${status} x ${role}`, () => {
        const writer = role !== "VIEWER";
        const requestable = REQUESTABLE.includes(status);
        expect(requestAvailability({ status, role, vendorEmail: "v@example.com" })).toBe(requestable && writer ? "available" : "hidden");
        expect(requestAvailability({ status, role, vendorEmail: null })).toBe(requestable && writer ? "no-email" : "hidden");
        expect(requestAvailability({ status, role, vendorEmail: "" })).toBe(requestable && writer ? "no-email" : "hidden");
      });
    }
  }
  it("offers the action when the email is unknown to the view", () => {
    expect(requestAvailability({ status: "MISSING", role: "MEMBER", vendorEmail: undefined })).toBe("available");
  });
  it("hides it without a role or status", () => {
    expect(requestAvailability({ status: "MISSING", role: null, vendorEmail: "a@b.co" })).toBe("hidden");
    expect(requestAvailability({ status: undefined, role: "OWNER", vendorEmail: "a@b.co" })).toBe("hidden");
  });
});

describe("request error mapping", () => {
  it.each([
    [409, "Already requested today"],
    [422, "Vendor has no email"],
    [429, "Too many requests. Please wait a minute and try again."],
    [403, "You do not have permission to request documents."],
  ])("maps %i", (status, text) => {
    expect(errorMessage(new ApiError({ status, title: "ignored" }), REQUEST_ERROR_MESSAGES)).toBe(text);
  });
  it("falls back to the network message", () => {
    expect(errorMessage(new TypeError("x"), REQUEST_ERROR_MESSAGES)).toMatch(/could not reach the server/);
    vi.fn();
  });
  it("formats the success toast", () => {
    expect(requestSentMessage("v@example.com")).toBe("Request sent to v@example.com");
  });
});
