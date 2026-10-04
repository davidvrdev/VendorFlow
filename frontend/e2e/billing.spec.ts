import { expect, test } from "@playwright/test";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

test("billing: new workspace shows its trial; Subscribe explains that billing is not configured", async ({ page }) => {
  await signUp(page, uniqueUser("billing"));

  await page.goto("/settings/billing");
  await page.waitForLoadState("networkidle");
  await expect(page.getByText("Trial", { exact: true })).toBeVisible();
  await expect(page.getByText("Trial ends")).toBeVisible();

  // Local/e2e backend has no Stripe keys: 503 "Billing not configured" becomes a friendly message.
  await page.getByRole("button", { name: "Subscribe" }).click();
  await expect(page.getByText("Billing isn't configured yet")).toBeVisible();
  await expect(page.getByRole("button", { name: "Subscribe" })).toBeEnabled();
});
