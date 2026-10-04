import { describe, expect, it } from "vitest";
import { buildCsp, generateNonce, staticSecurityHeaders } from "./headers";

describe("buildCsp", () => {
  const prod = buildCsp("abc", true);
  const dev = buildCsp("abc", false);

  it("contains the required directives", () => {
    for (const d of [
      "default-src 'self'",
      "script-src 'self' 'nonce-abc' 'strict-dynamic'",
      "style-src 'self' 'nonce-abc' 'sha256-StEaX+se6YS7pqjzrzMIA0KaX9zF/8zAhvQXZAe5epY='",
      "img-src 'self' data: blob:",
      "connect-src 'self'",
      "frame-ancestors 'none'",
      "form-action 'self' https://checkout.stripe.com https://billing.stripe.com",
      "base-uri 'self'",
      "object-src 'none'",
    ]) {
      expect(prod).toContain(d);
    }
  });

  it("is strict in production", () => {
    expect(prod).toContain("upgrade-insecure-requests");
    expect(prod).not.toContain("unsafe-eval");
    expect(prod).not.toContain("ws:");
    expect(prod).not.toMatch(/script-src[^;]*unsafe-inline/);
    expect(prod).not.toMatch(/style-src 'self'[^;]*unsafe-inline/);
  });

  it("relaxes only eval and websockets in dev", () => {
    expect(dev).toContain("'unsafe-eval'");
    expect(dev).toContain("ws:");
    expect(dev).not.toContain("upgrade-insecure-requests");
  });
});

describe("generateNonce", () => {
  it("is unique and base64", () => {
    const a = generateNonce();
    expect(a).toMatch(/^[A-Za-z0-9+/]{22}==$/);
    expect(generateNonce()).not.toBe(a);
  });
});

describe("staticSecurityHeaders", () => {
  const names = (prod: boolean) => staticSecurityHeaders(prod).map((h) => h.key);
  it("always sets the baseline headers", () => {
    expect(names(false)).toEqual(
      expect.arrayContaining(["X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options", "Permissions-Policy"]),
    );
    expect(staticSecurityHeaders(false).find((h) => h.key === "X-Frame-Options")?.value).toBe("DENY");
  });
  it("sets HSTS only in production", () => {
    expect(names(true)).toContain("Strict-Transport-Security");
    expect(names(false)).not.toContain("Strict-Transport-Security");
  });
});
