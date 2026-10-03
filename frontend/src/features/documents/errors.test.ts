import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { applyApiError } from "@/lib/forms/api-errors";
import { UPLOAD_FIELDS, UPLOAD_STATUS_MESSAGES } from "./errors";
import type { UploadFormValues } from "./schemas";

function map(error: unknown) {
  const setError = vi.fn();
  const message = applyApiError<UploadFormValues>(error, setError, { fields: UPLOAD_FIELDS, statusMessages: UPLOAD_STATUS_MESSAGES });
  return { message, setError };
}

describe("upload error mapping", () => {
  it.each([
    [413, "File is larger than 15 MB."],
    [415, "Only PDF, PNG or JPG files are accepted."],
    [422, "File rejected."],
  ])("maps %i to a form-level message", (status, text) => {
    const { message, setError } = map(new ApiError({ status, title: "ignored" }));
    expect(message).toBe(text);
    expect(setError).not.toHaveBeenCalled();
  });

  it("puts 400 field errors on their fields", () => {
    const { message, setError } = map(
      new ApiError({
        status: 400,
        title: "Validation failed",
        errors: [
          { field: "expirationDate", message: "Expiration date is required." },
          { field: "documentTypeId", message: "Unknown document type." },
        ],
      }),
    );
    expect(message).toBeNull();
    expect(setError).toHaveBeenCalledWith("expirationDate", { type: "server", message: "Expiration date is required." });
    expect(setError).toHaveBeenCalledWith("documentTypeId", { type: "server", message: "Unknown document type." });
  });

  it("falls back to the generic network message for non-API errors", () => {
    expect(map(new TypeError("fetch failed")).message).toMatch(/could not reach the server/);
  });
});
