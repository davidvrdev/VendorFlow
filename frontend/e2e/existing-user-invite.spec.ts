import { expect, test } from "@playwright/test";
import { openVerificationLink, PASSWORD, signOut, signUp, uniqueUser } from "./support/flows";
import { latestLink } from "./support/mailbox";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

test("existing user: sign in to accept an invitation, then leave and switch back to their own organization", async ({
  page,
  browser,
  request,
}) => {
  const owner = uniqueUser("host");
  const guest = uniqueUser("guest");

  // The guest already has an account (and organization) of their own.
  const guestContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const guestPage = await guestContext.newPage();
  await signUp(guestPage, guest);
  await signOut(guestPage);

  // The owner verifies their email and invites the guest as ADMIN.
  await signUp(page, owner);
  await openVerificationLink(page, await latestLink(request, owner.email, "EMAIL_VERIFICATION"));
  await page.goto("/settings/members");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Email", { exact: true }).fill(guest.email);
  await page.getByRole("combobox", { name: "Role" }).click();
  await page.getByRole("option", { name: "Admin" }).click();
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByText(`Invitation sent to ${guest.email}.`)).toBeVisible();

  // Signed out: the page asks to sign in, keeps the invitation across the login, then offers Accept.
  const link = await latestLink(request, guest.email, "INVITATION");
  await guestPage.goto(link);
  await expect(guestPage.getByRole("link", { name: "Sign in to accept" })).toBeVisible();
  await guestPage.getByRole("link", { name: "Sign in to accept" }).click();
  await expect(guestPage).toHaveURL(/\/login\?next=%2Finvite$|\/login\?next=\/invite$/);
  await guestPage.waitForLoadState("networkidle");
  await guestPage.getByLabel("Email").fill(guest.email);
  await guestPage.getByLabel("Password").fill(PASSWORD);
  await guestPage.getByRole("button", { name: "Sign in" }).click();
  await expect(guestPage).toHaveURL(/\/invite$/);
  await guestPage.getByRole("button", { name: "Accept invitation" }).click();
  await expect(guestPage).toHaveURL(/\/dashboard$/);
  await expect(guestPage.getByRole("button", { name: /Organization: .*Switch organization/ })).toContainText(owner.orgName);

  // Leave the organization: the layout re-reads /me and offers the guest's own organization.
  await guestPage.goto("/settings/members");
  await guestPage.waitForLoadState("networkidle");
  await guestPage.getByRole("button", { name: "Leave organization" }).click();
  await guestPage.getByRole("alertdialog").getByRole("button", { name: "Leave organization" }).click();
  await expect(guestPage.getByText("You left the organization.")).toBeVisible();
  // The session has no active organization after leaving: /no-organization offers the remaining one.
  await expect(guestPage).toHaveURL(/no-organization$/);
  await guestPage.getByRole("button", { name: new RegExp(guest.orgName) }).click();
  await expect(guestPage).toHaveURL(/\/dashboard$/);
  await expect(guestPage.getByTestId("active-org")).toContainText(guest.orgName);
  await guestContext.close();
});
