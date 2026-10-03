import { afterEach, describe, expect, it, vi } from "vitest";
import { apiFetch, buildHeaders, CSRF_HEADER, isUnsafeMethod, readCookie } from "./client";
import { ApiError } from "./errors";

describe("readCookie", () => {
  it("finds a cookie among several and decodes it", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; XSRF-TOKEN=abc%3D%3D; b=2")).toBe("abc==");
  });
  it("returns undefined when absent or when only a prefix matches", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; X-XSRF-TOKEN=nope")).toBeUndefined();
    expect(readCookie("XSRF-TOKEN", "")).toBeUndefined();
  });
});

describe("buildHeaders (CSRF)", () => {
  it("adds X-XSRF-TOKEN for unsafe methods", () => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
      expect(buildHeaders(method, undefined, false, "XSRF-TOKEN=t0k").get(CSRF_HEADER)).toBe("t0k");
    }
  });
  it("does not add it for safe methods", () => {
    for (const method of ["GET", "HEAD", "OPTIONS"]) {
      expect(buildHeaders(method, undefined, false, "XSRF-TOKEN=t0k").has(CSRF_HEADER)).toBe(false);
    }
  });
  it("omits the header when no cookie exists and sets JSON content type only with a body", () => {
    expect(buildHeaders("POST", undefined, true, "").has(CSRF_HEADER)).toBe(false);
    expect(buildHeaders("POST", undefined, true, "").get("Content-Type")).toBe("application/json");
    expect(buildHeaders("POST", undefined, false, "").has("Content-Type")).toBe(false);
  });
  it("classifies methods case-insensitively", () => {
    expect(isUnsafeMethod("post")).toBe(true);
    expect(isUnsafeMethod("get")).toBe(false);
  });
});

describe("apiFetch", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("calls /api/v1 same-origin with CSRF header and JSON body", async () => {
    document.cookie = "XSRF-TOKEN=abc; path=/";
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "1" }), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiFetch<{ id: string }>("/vendors", { method: "POST", json: { name: "Acme" } });

    expect(result).toEqual({ id: "1" });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/vendors");
    expect(init.credentials).toBe("same-origin");
    expect(init.body).toBe('{"name":"Acme"}');
    expect((init.headers as Headers).get(CSRF_HEADER)).toBe("abc");
  });

  it("returns undefined on 204", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(apiFetch("/auth/logout", { method: "POST" })).resolves.toBeUndefined();
  });

  it("throws ApiError on non-2xx", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () =>
        new Response(JSON.stringify({ title: "Not found", requestId: "r1" }), {
          status: 404,
          headers: { "Content-Type": "application/problem+json" },
        }),
      ),
    );
    await expect(apiFetch("/vendors/x")).rejects.toMatchObject({ status: 404, requestId: "r1" });
    await expect(apiFetch("/vendors/x")).rejects.toBeInstanceOf(ApiError);
  });

  it("refuses absolute URLs", async () => {
    vi.stubGlobal("fetch", vi.fn());
    await expect(apiFetch("//evil.example/x")).rejects.toThrow();
    await expect(apiFetch("https://evil.example/x")).rejects.toThrow();
  });

  it("primes CSRF with GET /auth/csrf once when the cookie is missing, then sends the token", async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === "/api/v1/auth/csrf") {
        document.cookie = "XSRF-TOKEN=fresh; path=/";
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 204 });
    });
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/auth/login", { method: "POST", json: { email: "a@b.co" } });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [primeUrl, primeInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(primeUrl).toBe("/api/v1/auth/csrf");
    expect(primeInit.method).toBe("GET");
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect((init.headers as Headers).get(CSRF_HEADER)).toBe("fresh");
  });

  it("does not prime when the cookie exists or the method is safe", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/me");
    expect(fetchMock).toHaveBeenCalledTimes(1);

    document.cookie = "XSRF-TOKEN=have; path=/";
    await apiFetch("/auth/logout", { method: "POST" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("shares one priming request between concurrent unsafe calls", async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === "/api/v1/auth/csrf") document.cookie = "XSRF-TOKEN=shared; path=/";
      return new Response(null, { status: 204 });
    });
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([apiFetch("/a", { method: "POST" }), apiFetch("/b", { method: "POST" })]);

    const primes = fetchMock.mock.calls.filter(([url]) => url === "/api/v1/auth/csrf");
    expect(primes).toHaveLength(1);
  });
});
