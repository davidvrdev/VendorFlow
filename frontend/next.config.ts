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
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
