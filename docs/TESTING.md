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
1. Signup / login / logout
2. Organization isolation (two orgs, cross-access attempt via URL manipulation)
3. Create vendor
4. Upload document
5. View/download document
6. Unauthorized document access
7. Document expiration reflected in status + dashboard
8. Reminder generated (job triggered via test-only endpoint guarded by `test` profile)
9. CSV import (with validation errors, then fixed)
10. Stripe webhook updates subscription state

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
