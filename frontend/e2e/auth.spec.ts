import { expect, test, type Page } from "./support/test";

// Full-stack journey: needs the Spring backend + Postgres behind /api (see docs/DEV_SETUP.md).
// Run with E2E_FULLSTACK=1 npx playwright test e2e/auth.spec.ts
test.skip(!process.env.E2E_FULLSTACK, "requires backend");

const PASSWORD = "correct-horse-battery-staple";

async function signOut(page: Page) {
  await page.getByRole("button", { name: /Account menu for/ }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
}

test("signup, logout, login, update organization settings, invite a member", async ({ page }) => {
  const stamp = Date.now();
  const email = `e2e-${stamp}@example.com`;
  const orgName = `E2E Org ${stamp}`;

  // Signup -> dashboard
  await page.goto("/signup");
  await page.waitForLoadState("networkidle"); // wait for hydration before typing into RHF inputs
  await page.getByLabel("Full name").fill("Erin E2E");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("Organization name").fill(orgName);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText(orgName).first()).toBeVisible();
  // A fresh account is unverified: the banner is shown.
  await expect(page.getByText("Verify your email to invite teammates and receive reminders.")).toBeVisible();

  // Logout -> login
  await signOut(page);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);

  // Wrong password shows the generic message
  await signOut(page);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("definitely-not-the-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Invalid email or password." })).toBeVisible();
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);

  // Organization settings
  await page.goto("/settings/organization");
  await page.waitForLoadState("networkidle"); // wait for hydration before typing into RHF inputs
  const renamed = `${orgName} Renamed`;
  await page.getByLabel("Organization name").fill(renamed);
  await page.getByLabel("Expiring window (days)").fill("45");
  await page.getByLabel("Reminder days before expiry").fill("30, 14, 7");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByText("Organization settings saved.")).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("Organization name")).toHaveValue(renamed);
  await expect(page.getByLabel("Expiring window (days)")).toHaveValue("45");

  // Invalid reminder offsets are rejected client-side
  await page.getByLabel("Reminder days before expiry").fill("7, 7");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByText("Remove duplicate values.")).toBeVisible();

  // Members + invitations (UI only: the inviter is unverified, so the backend answers 422)
  await page.goto("/settings/members");
  await page.waitForLoadState("networkidle"); // wait for hydration before typing into RHF inputs
  await expect(page.getByRole("cell", { name: "Erin E2E" })).toBeVisible();
  await page.getByLabel("Email", { exact: true }).fill(`teammate-${stamp}@example.com`);
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Verify your own email" })).toBeVisible();
});
