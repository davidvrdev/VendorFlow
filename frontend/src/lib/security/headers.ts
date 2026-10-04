/**
 * Security headers for every page. Pure functions so they are unit-testable (headers.test.ts).
 * CSP is per-request (nonce) and is attached in src/proxy.ts; the static headers are attached by next.config.ts.
 */

export type HeaderPair = { key: string; value: string };

// sonner injects its stylesheet with document.createElement("style") and no nonce, so it needs a hash source:
// the first is the sha256 of that CSS for the installed version (CHANGES ON A SONNER UPGRADE: e2e/csp.spec.ts reports
// the new hash in its failure message), the second is the empty <style> it creates first (harmless, avoids console noise).
const SONNER_STYLE_HASHES = ["sha256-StEaX+se6YS7pqjzrzMIA0KaX9zF/8zAhvQXZAe5epY=", "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="];

export function buildCsp(nonce: string, isProd: boolean): string {
  const directives = [
    "default-src 'self'",
    // 'strict-dynamic': scripts loaded by nonce'd scripts are trusted; host allowlists are ignored by browsers
    // that support it. Dev needs 'unsafe-eval' (React debug stacks) and ws: for HMR; never in production.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${isProd ? "" : " 'unsafe-eval'"}`,
    // No 'unsafe-inline' for style elements. Tailwind compiles to a stylesheet served from 'self'; Next injects its few inline <style> tags with the nonce.
    `style-src 'self' 'nonce-${nonce}' ${SONNER_STYLE_HASHES.map((h) => `'${h}'`).join(" ")}`,
    // Style ATTRIBUTES (style="...") cannot carry a nonce. Radix/sonner position popovers and toasts with them
    // (CSS custom properties, transforms), so allow attributes only: they cannot load resources or run script.
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    `connect-src 'self'${isProd ? "" : " ws: wss:"}`,
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
    "form-action 'self' https://checkout.stripe.com https://billing.stripe.com",
  ];
  if (isProd) directives.push("upgrade-insecure-requests");
  return directives.join("; ");
}

export function generateNonce(): string {
  // 128 bits from the Web Crypto CSPRNG, base64: unguessable and unique per request.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

export function staticSecurityHeaders(isProd: boolean): HeaderPair[] {
  const headers: HeaderPair[] = [
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
    { key: "X-Frame-Options", value: "DENY" },
    { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  ];
  // HSTS over plain-http localhost would pin browsers to https for that host, so production only.
  if (isProd) headers.push({ key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" });
  return headers;
}
