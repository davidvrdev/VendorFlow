# ADR-0003: Same-origin API access via Next.js rewrites

- Status: Accepted · Date: 2026-10-03

## Context
Browser UI is Next.js; API is Spring. Sessions are cookie-based (ADR-0002).

## Problem
How does the browser reach the API without CORS/cookie complexity?

## Options considered
1. Browser calls Spring directly on another origin (CORS + `SameSite=None` cookies).
2. **Next.js rewrites `/api/*` to Spring; the browser only sees one origin.**
3. Next.js Route Handlers proxy every endpoint manually (full BFF).

## Decision
Option 2. Server Components call Spring directly over the private network, forwarding the incoming `Cookie` header.
Spring sees the original client IP via `X-Forwarded-For` (trusted proxy configured explicitly).

## Consequences
+ First-party `SameSite=Lax` cookie; no CORS in production; backend can be private.
+ Zero per-endpoint proxy code.
− Next.js needs `BACKEND_URL`; uploads stream through Next (OK at 15 MB cap; revisit with presigned uploads if needed).
− Forwarded-header trust must be restricted to the Next.js hop (Phase 9 / 11).

## Alternatives rejected
- Cross-origin: `SameSite=None` weakens CSRF posture; CORS misconfiguration risk.
- Manual BFF: lots of boilerplate with no security gain over rewrites.
