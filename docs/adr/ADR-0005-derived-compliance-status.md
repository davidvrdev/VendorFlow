# ADR-0005: Compliance status is derived, not stored

- Status: Accepted · Date: 2026-10-03

## Context
Statuses MISSING / OK / EXPIRING / EXPIRED / REVIEW_REQUIRED depend on the current date and org settings.

## Problem
Store status on documents (updated by a job) or compute it?

## Options considered
1. Stored status column updated nightly.
2. **Derived at read time** from requirement + current document + today (org time zone) + window.

## Decision
Option 2. A pure `ComplianceCalculator` defines the rules (see PRODUCT_SPEC.md). Dashboard/list aggregates use SQL
implementing the same rules; a shared table of test vectors runs against both to keep them identical.

## Consequences
+ No drift, no job dependency for correctness, settings changes apply instantly.
− Two implementations (Java + SQL) must stay in sync → enforced by shared test vectors.
− Aggregations cost a query; indexed on `(organization_id, expiration_date) WHERE state='CURRENT'` — fine at target scale.

## Alternatives rejected
- Stored status: stale between job runs, complex invalidation on edits and settings changes.
