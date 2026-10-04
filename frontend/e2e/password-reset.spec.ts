import { expect, test } from "./support/test";
import { PASSWORD, signIn, signOut, signUp, uniqueUser } from "./support/flows";
import { latestLink } from "./support/mailbox";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const NEW_PASSWORD = "another-very-long-passphrase-42";

test("forgot password -> mailbox link -> new password; old password no longer works", async ({ page, request }) => {
  const user = uniqueUser("reset");
  await signUp(page, user);
  await signOut(page);

  await page.goto("/forgot-password");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Email").fill(user.email);
  await page.getByRole("button", { name: "Send reset link" }).click();

  const link = await latestLink(request, user.email, "PASSWORD_RESET");
  await page.goto(link);
  await expect(page.getByLabel("New password", { exact: true })).toBeVisible();
  // The token is read from the fragment and removed from the address bar right away.
  await expect(page).toHaveURL(/\/reset-password$/);
  expect(page.url()).not.toContain("token");
  await page.waitForLoadState("networkidle");

  await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel("Confirm new password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/login\?reset=1$/);
  await expect(page.getByText("Your password has been changed.")).toBeVisible();

  // Old password fails, new one works.
  await signIn(page, user.email, PASSWORD);
  await expect(page.getByRole("alert").filter({ hasText: "Invalid email or password." })).toBeVisible();
  await page.getByLabel("Password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);

  // The link is single use.
  await page.goto(link);
  await page.waitForLoadState("networkidle");
  await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel("Confirm new password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByText("This reset link is invalid or has expired.")).toBeVisible();
});

test("a weak password is rejected without consuming the link", async ({ page, request }) => {
  const user = uniqueUser("weak");
  await signUp(page, user);
  await signOut(page);
  await page.goto("/forgot-password");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Email").fill(user.email);
  await page.getByRole("button", { name: "Send reset link" }).click();
  const link = await latestLink(request, user.email, "PASSWORD_RESET");

  await page.goto(link);
  await page.waitForLoadState("networkidle");
  const weak = "password1234"; // long enough for the client, a common password for the server
  await page.getByLabel("New password", { exact: true }).fill(weak);
  await page.getByLabel("Confirm new password", { exact: true }).fill(weak);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByLabel("New password", { exact: true })).toHaveAttribute("aria-invalid", "true");

  await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel("Confirm new password", { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/login\?reset=1$/);
});
