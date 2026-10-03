# ADR-0002: Authentication with Spring Security and server-side sessions

- Status: Accepted (confirmed by product owner) · Date: 2026-10-03

## Context
We need email/password auth, email verification, password reset, invitations and multi-org membership.

## Problem
Own authentication in Spring, or delegate to a provider (Supabase Auth) and verify JWTs?

## Options considered
1. **Spring Security, server-side sessions in Postgres (Spring Session JDBC), HttpOnly cookie, CSRF tokens.**
2. Supabase Auth + JWT verification in Spring.
3. Stateless JWTs issued by Spring.

## Decision
Option 1. Passwords hashed with Spring's `DelegatingPasswordEncoder` (bcrypt, cost 12; the `{id}` prefix allows
transparent upgrade to Argon2 later without a migration). Sessions persisted with Spring Session JDBC so they survive
restarts, work across instances, and can be revoked (logout, password reset, member removal).

## Consequences
+ Single source of identity, no vendor lock-in, fully testable with Testcontainers.
+ Instant revocation; no token-refresh complexity; cookie never readable by JS.
− We build verification/reset/invitation flows ourselves (small, well-understood; emails via Resend).
− CSRF protection is mandatory (cookie auth) — handled by Spring Security.
− SSO/OAuth (Google/Microsoft) would be added later via Spring Security OAuth2 client.

## Alternatives rejected
- Supabase Auth: identity split between two systems, harder local/CI testing, coupling to Supabase.
- Stateless JWT: no revocation without a denylist (which re-introduces state); theft via XSS if stored in JS.
