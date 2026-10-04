import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { confirmOptOut, fetchOptOutInfo, listChases, setVendorChasingPaused } from "./api";
import { chasePage, chasingState, optOutInfo } from "./fixtures";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json" } });

afterEach(() => vi.unstubAllGlobals());

describe("public opt-out API", () => {
  it("GET sends the token only in a header, no cookies, no referrer, no body", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(optOutInfo()));
    vi.stubGlobal("fetch", fetchMock);
    await fetchOptOutInfo("tok-123456789");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/portal/chasing/opt-out");
    expect(url).not.toContain("tok-123456789");
    expect(init.method).toBe("GET");
    expect(init.credentials).toBe("omit");
    expect(init.referrerPolicy).toBe("no-referrer");
    expect(init.cache).toBe("no-store");
    expect((init.headers as Record<string, string>)["X-Portal-Token"]).toBe("tok-123456789");
    expect(Object.keys(init.headers as Record<string, string>)).not.toContain("X-XSRF-TOKEN");
  });

  it("POST uses the same rules and parses the confirmation", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(optOutInfo({ optedOut: true })));
    vi.stubGlobal("fetch", fetchMock);
    await expect(confirmOptOut("tok-123456789")).resolves.toMatchObject({ optedOut: true });
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("omit");
    expect(init.body).toBeUndefined();
  });

  it("surfaces a 404 as an ApiError and rejects contract drift", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json({ title: "x", type: "https://x/problems/chasing-opt-out-invalid" }, 404)));
    const error = await fetchOptOutInfo("tok-123456789").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ organizationName: "x" })));
    await expect(fetchOptOutInfo("tok-123456789")).rejects.toThrow("Unexpected response");
  });
});

describe("staff chasing API", () => {
  it("pages the history and sends the pause body", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json(chasePage())).mockResolvedValueOnce(json(chasingState({ paused: true, status: "PAUSED", pausedReason: "MANUAL" })));
    document.cookie = "XSRF-TOKEN=test-csrf"; // present, so apiFetch does not make a priming request
    vi.stubGlobal("fetch", fetchMock);
    await listChases("v 1", 2);
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v1/vendors/v%201/chases?page=2&size=10");
    await setVendorChasingPaused("v1", true);
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(JSON.stringify({ paused: true }));
  });
});
