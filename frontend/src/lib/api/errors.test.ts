import { describe, expect, it } from "vitest";
import { ApiError } from "./errors";

function problem(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(typeof body === "string" ? body : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/problem+json", ...headers },
  });
}

describe("ApiError.fromResponse", () => {
  it("parses an RFC 9457 problem body including field errors", async () => {
    const error = await ApiError.fromResponse(
      problem(400, {
        title: "Validation failed",
        status: 400,
        detail: "One or more fields are invalid.",
        requestId: "req-123",
        errors: [{ field: "email", message: "must be a valid email" }],
      }),
    );
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.title).toBe("Validation failed");
    expect(error.detail).toBe("One or more fields are invalid.");
    expect(error.requestId).toBe("req-123");
    expect(error.errors).toEqual([{ field: "email", message: "must be a valid email" }]);
  });

  it("drops malformed field errors and non-string fields", async () => {
    const error = await ApiError.fromResponse(
      problem(422, { title: 42, detail: { x: 1 }, errors: [{ field: "a" }, null, { field: "b", message: "bad" }] }),
    );
    expect(error.title).toBe("The request could not be completed.");
    expect(error.detail).toBeUndefined();
    expect(error.errors).toEqual([{ field: "b", message: "bad" }]);
  });

  it("falls back to a generic message for HTML bodies and never leaks them", async () => {
    const html = "<html><body>java.lang.NullPointerException at com.x</body></html>";
    const error = await ApiError.fromResponse(
      new Response(html, { status: 502, headers: { "Content-Type": "text/html", "X-Request-Id": "r-9" } }),
    );
    expect(error.status).toBe(502);
    expect(error.message).toBe("Something went wrong on our side. Please try again.");
    expect(error.message).not.toContain("NullPointer");
    expect(error.requestId).toBe("r-9");
  });

  it("falls back when JSON content type has an unparseable body", async () => {
    const error = await ApiError.fromResponse(problem(500, "{not json"));
    expect(error.title).toBe("Something went wrong on our side. Please try again.");
  });

  it("uses a status-specific generic title for 401 and 404", async () => {
    const e401 = await ApiError.fromResponse(new Response("", { status: 401 }));
    const e404 = await ApiError.fromResponse(new Response("", { status: 404 }));
    expect(e401.title).toMatch(/sign in/i);
    expect(e404.title).toMatch(/could not find/i);
  });
});
