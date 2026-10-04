import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { describeCommitError, describePreviewError } from "./errors";

const api = (status: number, title = "t", detail?: string) => new ApiError({ status, title, detail });

describe("describePreviewError", () => {
  it("names the header columns on a 400 with field errors", () => {
    const error = new ApiError({ status: 400, title: "Validation failed", detail: "One or more fields are invalid.",
      errors: [{ field: "company_name", message: "Required column is missing" }] });
    expect(describePreviewError(error)).toBe("Fix the header row: company_name: Required column is missing.");
  });
  it("maps the documented statuses", () => {
    expect(describePreviewError(api(413))).toMatch(/larger than 1 MB/);
    expect(describePreviewError(api(415))).toMatch(/\.csv/);
    expect(describePreviewError(api(429))).toMatch(/Too many/);
    expect(describePreviewError(api(400))).toMatch(/UTF-8/);
    expect(describePreviewError(api(422))).toMatch(/2,000/);
  });
  it("prefers the server detail for 400 and 422", () => {
    expect(describePreviewError(api(400, "t", "Unknown column: foo"))).toBe("Unknown column: foo");
    expect(describePreviewError(api(422, "t", "Too many rows (2500)"))).toBe("Too many rows (2500)");
  });
  it("handles network errors", () => {
    expect(describePreviewError(new TypeError("fetch failed"))).toMatch(/could not reach the server/);
  });
});

describe("describeCommitError", () => {
  it("410 expired blocks the preview", () => {
    expect(describeCommitError(api(410, "Import expired"))).toMatchObject({ block: "expired" });
  });
  it("409 tells data-changed from already-committed by title", () => {
    expect(describeCommitError(api(409, "Data changed since preview"))).toMatchObject({ block: "changed" });
    expect(describeCommitError(api(409, "Import already committed"))).toMatchObject({
      block: "committed",
      message: "This import was already committed.",
    });
  });
  it("422 and unknown errors are not blocking", () => {
    expect(describeCommitError(api(422))).toMatchObject({ block: null });
    expect(describeCommitError(api(500, "Boom"))).toMatchObject({ block: null });
    expect(describeCommitError(new TypeError("x")).block).toBeNull();
  });
});
