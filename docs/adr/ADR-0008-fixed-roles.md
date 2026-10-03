# ADR-0008: Fixed role enum with code-defined permissions

- Status: Accepted · Date: 2026-10-03

## Context
The MVP needs four roles: OWNER, ADMIN, MEMBER, VIEWER.

## Options considered
1. Role and permission tables (custom roles).
2. **Enum on `membership.role` + permission map in code.**

## Decision
Option 2. Permissions are mapped in `RolePermissions` and checked in application services.

## Consequences
+ Reviewable, testable, no privilege data an admin could tamper with.
− Custom roles need a migration later — the permission-check API (`require(Permission)`) stays the same.

## Alternatives rejected
- Tables: speculative flexibility, more surface for privilege-escalation bugs.
