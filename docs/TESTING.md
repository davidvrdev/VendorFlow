# VendorFlow — Testing Strategy

Test behavior, not coverage numbers. Never disable or delete a failing test to get green; fix the cause or
document it as a known issue with an owner.

## Layers
| Layer | Tool | What | Where |
|---|---|---|---|
| Domain unit | JUnit 5 + AssertJ | Pure logic (`ComplianceCalculator`, password policy, CSV row validation) | `backend/src/test/.../domain` |
| Integration / API | Spring Boot Test + MockMvc + **Testcontainers Postgres** | Real DB, real migrations, real security filter chain | `backend/src/test/...` (`*IT` suffix optional; all run in `./mvnw verify`) |
| Authorization | same | Every endpoint × lowest denied role → 403; unauthenticated → 401 | per feature |
| Tenant isolation | same | User of org B uses org A ids on every endpoint → 404, nothing modified | per feature (`*TenantIsolationTest`) |
| Frontend unit | Vitest + Testing Library | Components with logic (status badge, forms validation, import preview) | `frontend/src/**/*.test.tsx` |
| E2E | Playwright | Critical user flows against real backend + DB | `frontend/e2e/` |

**Never mock the database.** Mock only true external services at their boundary (Resend → capturing `EmailSender`;
Stripe → signed fixture events with a test webhook secret; storage → filesystem impl on a temp dir).

## Test conventions (backend)
- Base class `IntegrationTest` starts one shared Postgres container (reused across the suite) via
  `@ServiceConnection`. Each test creates its own orgs/users through factories — tests are independent and never rely
  on ordering.
- `MutableClock` (primary `Clock` bean in `TestBeans`) lets tests advance time (lock expiry, outbox backoff, "today"); `CapturingEmailSender` replaces the email provider; both plus the rate limiter are reset before every test by `IntegrationTest`.
- `TestAccounts` (signup/login/join-organization factories) and `ApiClient` (cookie jar + CSRF header, like the frontend) live in `support/`; use them instead of hand-building cookies. **Do not use `.with(csrf())`** from spring-security-test: it permanently swaps the CSRF repository of the shared filter chain for the whole JVM and breaks later tests; `ApiClient` sends the real cookie + header pair.
- **Real-server tests**: MockMvc does not run Tomcat valves, so anything depending on them (trusted-proxy / `X-Forwarded-For` handling) extends `support/RealServerTest` (`@SpringBootTest(RANDOM_PORT)` + `RestClient`; the peer address is 127.0.0.1). Each distinct configuration starts another server (a few seconds): keep them few. On Windows/JDK 25 the pom profile `windows-uds-tmpdir` sets `jdk.net.unixdomain.tmpdir` for surefire automatically (docs/DEV_SETUP.md § Troubleshooting); Linux CI needs nothing.
- Tests that move `MutableClock` by more than 7 days must log in again (absolute session lifetime). `ApiClient` paths containing `%` are sent as-is (already encoded), to test percent-encoding variants.
- Test names describe behavior: `memberCannotArchiveVendor()`, `userOfOtherOrgGets404ForDocumentDownload()`.

## Critical E2E flows (Phase 10 must have all green)
All specs live in `frontend/e2e/`, run against the real backend (profile `local,e2e`) + Postgres with `E2E_FULLSTACK=1`, and import `test`/`expect` from `e2e/support/test.ts`, a global fixture that **fails any test on a CSP violation or uncaught page error** (also in extra `browser.newContext()` pages).

| # | Flow | Spec (test) |
|---|------|-------------|
| 1 | Signup / login / logout | `auth.spec.ts` (signup, logout, login), `email-verification.spec.ts`, `password-reset.spec.ts` |
| 2 | Organization isolation (URL manipulation) | `tenant-isolation.spec.ts`; foreign-id checks inside `vendors.spec.ts`, `documents.spec.ts`, `csv.spec.ts` |
| 3 | Create vendor | `vendors.spec.ts` (lifecycle) |
| 4 | Upload document | `documents.spec.ts` (upload, replace, validation, big upload, types) |
| 5 | View / download document | `documents.spec.ts` (history, download) |
| 6 | Unauthorized document access | `documents.spec.ts` (isolation, role limits) |
| 7 | Expiration reflected in status + dashboard | `compliance.spec.ts`, `dashboard.spec.ts` |
| 8 | Reminder generated | `notifications.spec.ts` (job triggered via `POST /api/test/reminders/run`, profile `e2e` only, loopback + ORG_SETTINGS_MANAGE) |
| 9 | CSV import (errors, then fixed) | `csv.spec.ts` |
| 10 | Stripe webhook updates subscription state | `billing.spec.ts` (HMAC-signed with the fixed FAKE secret in `application-e2e.yml`; `E2eBillingGateway` replaces the Stripe API; bad/stale signature rejected, Active, replay idempotent, `subscription.deleted` -> read-only banner + 402 on write) |

Profile safety: `e2e` + `prod` together is refused at startup (`ProfileGuard`, covered by `ProfileGuardTest`). CI runs the whole suite in the `e2e-fullstack` job of `.github/workflows/ci.yml`.

## Commands
```bash
# backend (requires Docker running for Testcontainers)
cd backend && ./mvnw verify
# frontend
cd frontend && npm run lint && npm run typecheck && npm test
cd frontend && npx playwright test
```

## Last known results
See `docs/PROJECT_STATE.md` § Verification.
