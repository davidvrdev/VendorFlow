import { expect, test } from "@playwright/test";
import { BANNER_TEXT, openVerificationLink, PASSWORD, signUp, uniqueUser } from "./support/flows";
import { latestLink } from "./support/mailbox";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

test("owner invites a member; invitee creates an account, sees read-only members; owner changes role and revokes", async ({
  page,
  browser,
  request,
}) => {
  const owner = uniqueUser("owner");
  const stamp = Date.now();
  const inviteeEmail = `new-${stamp}@example.com`;
  const secondEmail = `second-${stamp}@example.com`;

  // Owner: sign up and verify the email (the backend refuses invitations from unverified inviters).
  await signUp(page, owner);
  await openVerificationLink(page, await latestLink(request, owner.email, "EMAIL_VERIFICATION"));
  await page.goto("/settings/members");
  await page.waitForLoadState("networkidle");
  await expect(page.getByText(BANNER_TEXT)).toHaveCount(0);

  // Invite as MEMBER (default role).
  await page.getByLabel("Email", { exact: true }).fill(inviteeEmail);
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByText(`Invitation sent to ${inviteeEmail}.`)).toBeVisible();
  await expect(page.getByRole("cell", { name: inviteeEmail, exact: true })).toBeVisible();

  // Inviting the same address again is refused with the server's message.
  await page.getByLabel("Email", { exact: true }).fill(inviteeEmail);
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "already a pending invitation" })).toBeVisible();

  // Invitee, in a brand-new browser context: opens the link and creates an account.
  const inviteLink = await latestLink(request, inviteeEmail, "INVITATION");
  const inviteeContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const invitee = await inviteeContext.newPage();
  await invitee.goto(inviteLink);
  await expect(invitee.getByText(owner.orgName)).toBeVisible();
  await expect(invitee).toHaveURL(/\/invite$/); // fragment stripped
  await invitee.waitForLoadState("networkidle");
  await invitee.getByLabel("Full name").fill("Nina Newcomer");
  await invitee.getByLabel("Password").fill(PASSWORD);
  await invitee.getByRole("button", { name: "Create account and join" }).click();
  await expect(invitee).toHaveURL(/\/dashboard$/);
  await expect(invitee.getByTestId("active-org")).toContainText(owner.orgName);
  // Email proved by the invitation link: no unverified banner.
  await expect(invitee.getByText(BANNER_TEXT)).toHaveCount(0);

  // Member sees Settings / Members read-only.
  await invitee.getByRole("link", { name: "Settings" }).first().click();
  await invitee.goto("/settings/members");
  await invitee.waitForLoadState("networkidle");
  await expect(invitee.getByRole("cell", { name: owner.fullName })).toBeVisible();
  await expect(invitee.getByRole("cell", { name: /Nina Newcomer/ })).toBeVisible();
  await expect(invitee.getByRole("combobox")).toHaveCount(0);
  await expect(invitee.getByRole("button", { name: "Send invitation" })).toHaveCount(0);
  await expect(invitee.getByRole("button", { name: /^Remove/ })).toHaveCount(0);

  // The used link no longer works.
  await invitee.goto(inviteLink);
  await expect(invitee.getByText("This invitation is invalid or has expired.")).toBeVisible();
  await inviteeContext.close();

  // Owner reloads and sees the new member; the invitation is no longer pending.
  await page.goto("/settings/members");
  await page.waitForLoadState("networkidle");
  const row = page.getByRole("row", { name: /Nina Newcomer/ });
  await expect(row).toBeVisible();
  await expect(row).toContainText("Member");
  await expect(page.getByRole("cell", { name: inviteeEmail, exact: true })).toHaveCount(1); // member row only (email column)

  // Owner changes the member's role to Viewer.
  await row.getByRole("combobox", { name: "Role for Nina Newcomer" }).click();
  await page.getByRole("option", { name: "Viewer" }).click();
  await expect(page.getByText("Nina Newcomer is now Viewer.")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("row", { name: /Nina Newcomer/ })).toContainText("Viewer");

  // Owner revokes a second pending invitation.
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Email", { exact: true }).fill(secondEmail);
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByRole("cell", { name: secondEmail, exact: true })).toBeVisible();
  await page.getByRole("button", { name: `Revoke invitation for ${secondEmail}` }).click();
  await page.getByRole("button", { name: "Revoke invitation", exact: true }).click();
  await expect(page.getByText(`Invitation for ${secondEmail} was revoked.`)).toBeVisible();
  await expect(page.getByRole("cell", { name: secondEmail, exact: true })).toHaveCount(0);
});
