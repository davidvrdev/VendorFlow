import { expect, test } from "./support/test";

test("landing page shows the headline and Sign in leads to /login", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { level: 1, name: "Know which vendors need your attention." }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Sign in" }).first().click();
  await expect(page).toHaveURL(/\/login$/);
});

test("anonymous visit to an app page is redirected to /login with a next param", async ({ page }) => {
  await page.goto("/settings/members");
  await expect(page).toHaveURL(/\/login\?next=%2Fsettings%2Fmembers$/);
  await expect(page.getByRole("heading", { level: 1, name: "Sign in" })).toBeVisible();
});

test("reset-password strips the token from the URL and posts it in the body (mocked API)", async ({ page }) => {
  let posted: unknown;
  await page.route("**/api/v1/auth/csrf", (route) =>
    route.fulfill({ status: 204, headers: { "Set-Cookie": "XSRF-TOKEN=e2e; Path=/" } }),
  );
  await page.route("**/api/v1/auth/password-reset/confirm", async (route) => {
    posted = route.request().postDataJSON();
    await route.fulfill({ status: 204 });
  });

  await page.goto("/reset-password#token=secret-token-123");
  await expect(page.getByLabel("New password", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/reset-password$/); // fragment stripped via history.replaceState

  await page.getByLabel("New password", { exact: true }).fill("a-very-long-passphrase");
  await page.getByLabel("Confirm new password", { exact: true }).fill("a-very-long-passphrase");
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByText("Your password has been changed.")).toBeVisible();
  expect(posted).toEqual({ token: "secret-token-123", newPassword: "a-very-long-passphrase" });
});

test("reset-password without a token explains the link is invalid", async ({ page }) => {
  await page.goto("/reset-password");
  await expect(page.getByText("This reset link is invalid or has expired.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Request a new reset link" })).toBeVisible();
});

test("invite with an unknown token shows the invalid message (mocked API)", async ({ page }) => {
  await page.route("**/api/v1/invitations/lookup", (route) =>
    route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ title: "Not found" }) }),
  );
  await page.route("**/api/v1/me", (route) => route.fulfill({ status: 401, body: "" }));
  await page.goto("/invite#token=bad");
  await expect(page.getByText("This invitation is invalid or has expired.")).toBeVisible();
});
