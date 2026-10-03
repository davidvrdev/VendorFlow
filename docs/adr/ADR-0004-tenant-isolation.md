# ADR-0004: Tenant isolation strategy

- Status: Accepted · Date: 2026-10-03

## Context
Shared database, shared schema; every business row belongs to an organization. Cross-tenant leaks are the
highest-impact risk for the product.

## Problem
Where to enforce isolation?

## Options considered
1. Schema-per-tenant or database-per-tenant.
2. Shared schema + application-level scoping only.
3. **Shared schema + application scoping + database structural guarantees (composite FKs) + tests.**
4. Option 3 + Postgres Row Level Security.

## Decision
Option 3 now; keep 4 as a hardening option.
- The active organization lives in the server-side session and is set only via a membership-verified endpoint.
- `TenantContextFilter` re-validates membership + role from the DB on every request.
- Repository methods for tenant tables always take `organizationId`.
- Tables expose `UNIQUE (organization_id, id)`; children use composite FKs `(organization_id, parent_id)`, so the
  database rejects any cross-tenant reference even if application code has a bug.
- Foreign ids return 404. Every endpoint family has cross-tenant tests.

## Consequences
+ Simple mental model; works with connection pooling and Supabase without session variables.
− Read paths rely on discipline → mitigated by tests and an architecture test (Phase 9).

## Alternatives rejected
- Schema/database per tenant: migration and ops burden for thousands of small tenants.
- RLS now: requires per-transaction `set_config` with pooled connections and complicates testing; revisit after MVP
  as a second safety net for reads.
