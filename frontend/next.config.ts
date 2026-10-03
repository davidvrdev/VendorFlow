import type { NextConfig } from "next";

// Server-side only: where the Spring API lives. The browser never sees this URL (ADR-0003).
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

// Baseline hardening for every route. A full Content-Security-Policy (nonces, report-only
// rollout) is planned for Phase 9 and intentionally not set here.
const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

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
