import { expect, test } from "@playwright/test";

test("landing page shows the headline and Sign in leads to /login", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { level: 1, name: "Know which vendors need your attention." }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Sign in" }).first().click();
  await expect(page).toHaveURL(/\/login$/);
});
