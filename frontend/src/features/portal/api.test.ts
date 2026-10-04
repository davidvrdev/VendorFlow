import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { fetchPortalInfo, uploadPortalDocument } from "./api";
import { portalInfo } from "./fixtures";

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json", ...headers } });

afterEach(() => vi.unstubAllGlobals());

describe("portal API", () => {
  it("sends the token in a header, never in the URL, and no cookies", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(portalInfo()));
    vi.stubGlobal("fetch", fetchMock);
    await fetchPortalInfo("tok-123456789");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/portal/link");
    expect(url).not.toContain("tok-123456789");
    expect(init.credentials).toBe("omit");
    expect((init.headers as Record<string, string>)["X-Portal-Token"]).toBe("tok-123456789");
    expect(init.referrerPolicy).toBe("no-referrer");
  });

  it("posts multipart without a CSRF header and keeps the problem type for 422s", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ title: "t", type: "https://x/problems/portal-upload-limit" }, 422));
    vi.stubGlobal("fetch", fetchMock);
    const error = await uploadPortalDocument("tok-123456789", { documentTypeId: "t1", file: new File(["%PDF-"], "a.pdf"), issueDate: null, expirationDate: "2031-01-01" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("portal-upload-limit");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/portal/link/documents");
    expect(init.body).toBeInstanceOf(FormData);
    expect(Object.keys(init.headers as Record<string, string>)).not.toContain("X-XSRF-TOKEN");
    expect((init.body as FormData).get("expirationDate")).toBe("2031-01-01");
    expect((init.body as FormData).has("issueDate")).toBe(false);
  });

  it("rejects a response that does not match the contract", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ organizationName: "x" })));
    await expect(fetchPortalInfo("tok-123456789")).rejects.toThrow("Unexpected response");
  });
});
