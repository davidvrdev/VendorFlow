import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  // Full-stack specs share one backend (bcrypt, uploads, outbox polling): many parallel browsers made them time
  // out on a busy dev machine. Keep full-stack runs at 2 workers; mocked/smoke runs use the default.
  workers: process.env.E2E_FULLSTACK ? 2 : undefined,
  // Full-stack steps chain several real requests (CSRF, write, redirect, server render with backend reads); on a
  // dev machine with Docker networking a step can take a few seconds, so the 5 s default made runs flaky.
  timeout: process.env.E2E_FULLSTACK ? 90_000 : 30_000,
  expect: { timeout: process.env.E2E_FULLSTACK ? 15_000 : 5_000 },
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  // E2E runs against the standalone production build (as deployed): `next dev` compiles routes on first request, which made
  // full-stack runs flaky under parallel load. Set E2E_DEV_SERVER=1 to use the dev server instead.
  webServer: {
    command: process.env.E2E_DEV_SERVER ? "npm run dev" : "npm run build && npm run start",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI || !!process.env.E2E_REUSE_SERVER, // CI full-stack job starts the frontend itself
    timeout: 240_000,
  },
});
