import { expect, type Page } from "@playwright/test";

export const PASSWORD = "correct-horse-battery-staple";

export const BANNER_TEXT = "Verify your email to invite teammates and receive reminders.";

export function uniqueUser(prefix: string) {
  const stamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  return { email: `${prefix}-${stamp}@example.com`, orgName: `${prefix} Org ${stamp}`, fullName: `${prefix} User` };
}

/** Sign up through the UI and wait for the dashboard. */
export async function signUp(page: Page, user: { email: string; orgName: string; fullName: string }) {
  await page.goto("/signup");
  await page.waitForLoadState("networkidle"); // wait for hydration before typing into RHF inputs
  await page.getByLabel("Full name").fill(user.fullName);
  await page.getByLabel("Work email").fill(user.email);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("Organization name").fill(user.orgName);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
}

export async function signOut(page: Page) {
  await page.getByRole("button", { name: /Account menu for/ }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

/** Open an emailed verification link in `page` and wait for the success state. */
export async function openVerificationLink(page: Page, link: string) {
  await page.goto(link);
  await expect(page.getByRole("heading", { name: "Email verified" })).toBeVisible();
}
