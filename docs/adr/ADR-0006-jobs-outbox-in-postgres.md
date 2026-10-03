# ADR-0006: Background jobs with @Scheduled + Postgres outbox (no broker)

- Status: Accepted · Date: 2026-10-03

## Context
Reminders and emails must be idempotent, retryable and observable, and must not block web requests.

## Options considered
1. Message broker (RabbitMQ/SQS) + workers.
2. Job library (Quartz / JobRunr / ShedLock).
3. **Spring `@Scheduled` + Postgres tables with unique constraints + `FOR UPDATE SKIP LOCKED`.**

## Decision
Option 3.
- `reminder` is a ledger with `UNIQUE (document_id, kind, offset_days, expiration_date)` → a threshold fires once,
  even with retries or multiple instances.
- `notification` is a transactional outbox with `idempotency_key UNIQUE`; the dispatcher claims rows with
  `SKIP LOCKED`, retries with exponential backoff (1m, 5m, 30m, 2h, 6h), then `DEAD` (visible, alertable).
- Emails are enqueued in the same transaction as the business change (no lost or phantom emails).
- Resend calls send an `Idempotency-Key` header derived from the notification id.

## Consequences
+ No extra infrastructure; correctness enforced by the database.
− Throughput bounded by polling interval — irrelevant at our scale.
− If many job types appear later, revisit option 2.
