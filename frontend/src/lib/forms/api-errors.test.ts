import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { applyApiError, errorMessage, NETWORK_ERROR_MESSAGE } from "./api-errors";

type Values = { email: string; password: string };
const fields = ["email", "password"] as const;

describe("applyApiError", () => {
  it("maps field errors to setError and returns no form error", () => {
    const setError = vi.fn();
    const error = new ApiError({ status: 400, title: "Validation failed", errors: [{ field: "email", message: "must be a valid email" }] });
    expect(applyApiError<Values>(error, setError, { fields })).toBeNull();
    expect(setError).toHaveBeenCalledWith("email", { type: "server", message: "must be a valid email" });
  });

  it("puts errors for unknown fields into the form-level message", () => {
    const setError = vi.fn();
    const error = new ApiError({ status: 400, title: "Validation failed", errors: [{ field: "nope", message: "bad thing" }] });
    expect(applyApiError<Values>(error, setError, { fields })).toBe("bad thing");
    expect(setError).not.toHaveBeenCalled();
  });

  it("uses status overrides, then detail, then title", () => {
    const setError = vi.fn();
    const e401 = new ApiError({ status: 401, title: "Unauthorized", detail: "ignored" });
    expect(applyApiError<Values>(e401, setError, { fields, statusMessages: { 401: "Invalid email or password." } })).toBe(
      "Invalid email or password.",
    );
    expect(applyApiError<Values>(new ApiError({ status: 422, title: "T", detail: "D" }), setError, { fields })).toBe("D");
    expect(applyApiError<Values>(new ApiError({ status: 500, title: "T" }), setError, { fields })).toBe("T");
  });

  it("applies field overrides", () => {
    const setError = vi.fn();
    const error = new ApiError({ status: 409, title: "Email already registered", errors: [{ field: "email", message: "dup" }] });
    applyApiError<Values>(error, setError, { fields, fieldOverrides: { email: "An account with this email already exists." } });
    expect(setError).toHaveBeenCalledWith("email", { type: "server", message: "An account with this email already exists." });
  });

  it("maps aliased server field names", () => {
    const setError = vi.fn();
    const error = new ApiError({ status: 400, title: "Validation failed", errors: [{ field: "mail", message: "bad" }] });
    expect(applyApiError<Values>(error, setError, { fields, fieldAliases: { mail: "email" } })).toBeNull();
    expect(setError).toHaveBeenCalledWith("email", { type: "server", message: "bad" });
  });

  it("never renders raw non-ApiError text", () => {
    expect(applyApiError<Values>(new TypeError("<script>boom</script>"), vi.fn(), { fields })).toBe(NETWORK_ERROR_MESSAGE);
    expect(errorMessage("weird")).toBe(NETWORK_ERROR_MESSAGE);
  });
});
