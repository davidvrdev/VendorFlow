# ADR-0007: Private object storage with backend-authorized downloads

- Status: Accepted · Date: 2026-10-03

## Context
Documents are sensitive. Prod target is Supabase Storage (S3-compatible API). Local dev must work offline.

## Options considered
1. Store files in Postgres (bytea).
2. **`ObjectStorage` interface: filesystem impl (dev/test) + S3 impl (prod).**
3. Downloads: presigned URLs vs **backend streaming after authorization**.

## Decision
Option 2 with backend streaming for the MVP. Keys are server-generated `org/{orgId}/doc/{uuid}`; the original filename
is metadata only. Downloads: authorize → audit → stream with `Content-Disposition: attachment`,
`X-Content-Type-Options: nosniff`, `Cache-Control: private, no-store`.
The S3 implementation uses the AWS SDK v2 S3 client (Apache-2.0; the reliable S3 protocol implementation). **Implemented in Phase 11 (`S3ObjectStorage`), see ADR-0010**: tested against LocalStack (real S3 protocol), uploads are spooled to a temp file (bounded by the 15 MB cap) because S3 needs the exact length.

## Consequences
+ Authorization and audit on every download; storage never exposed publicly.
− Bandwidth passes through the API (fine for ≤15 MB files at MVP scale). Short-lived presigned URLs (≤60 s) can be
  added behind the same authorization check later.
− The S3 implementation must be exercised against a real S3-compatible endpoint before Phase 11 (not mocked away).

## Alternatives rejected
- bytea: bloats DB and backups, poor streaming.
- Public bucket: unacceptable.
