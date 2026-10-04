import type { NextConfig } from "next";
import { staticSecurityHeaders } from "./src/lib/security/headers";

// Server-side only: where the Spring API lives. The browser never sees this URL (ADR-0003).
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

// Static headers here; the per-request nonce CSP is set in src/proxy.ts.
const securityHeaders = staticSecurityHeaders(process.env.NODE_ENV === "production");

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  experimental: {
    // Next buffers every request body (also for rewrites to the API) up to this size and SILENTLY TRUNCATES
    // anything larger (default 10 MB), which would corrupt document uploads. Our cap is 15 MB per file
    // (docs/API.md Phase 3) plus multipart overhead, so allow 16 MB; the backend stays authoritative (413).
    proxyClientMaxBodySize: "16mb",
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
