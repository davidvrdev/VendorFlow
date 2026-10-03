import { expect, test } from "@playwright/test";
import { BANNER_TEXT, openVerificationLink, signUp, uniqueUser } from "./support/flows";
import { latestLink } from "./support/mailbox";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

test("signup -> banner -> verification link from the mailbox -> banner gone", async ({ page, request }) => {
  const user = uniqueUser("verify");
  await signUp(page, user);
  await expect(page.getByText(BANNER_TEXT)).toBeVisible();

  const link = await latestLink(request, user.email, "EMAIL_VERIFICATION");
  await openVerificationLink(page, link);
  await expect(page).toHaveURL(/\/verify-email$/); // fragment stripped

  // Same signed-in session: the unverified banner must be gone after /me was re-read.
  await page.getByRole("link", { name: "Continue to VendorFlow" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText(BANNER_TEXT)).toHaveCount(0);
});

test("resend sends a new link and an invalid link is reported", async ({ page, request }) => {
  const user = uniqueUser("resend");
  await signUp(page, user);
  await page.waitForLoadState("networkidle");
  await page.getByRole("button", { name: "Resend email" }).click();
  await expect(page.getByText("Verification email sent. Check your inbox.")).toBeVisible();
  await latestLink(request, user.email, "EMAIL_VERIFICATION", 2);

  await page.goto("/verify-email#token=not-a-real-token");
  await expect(page.getByText("This verification link is invalid or has expired.")).toBeVisible();
});
