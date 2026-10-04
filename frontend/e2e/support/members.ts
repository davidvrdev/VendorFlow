import { expect, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import { openVerificationLink, PASSWORD } from "./flows";
import { latestLink } from "./mailbox";

/**
 * Verify the signed-in owner's email (the backend refuses invitations from unverified inviters), invite
 * `email` as MEMBER and accept the invitation in a fresh browser context. Returns the member's page.
 */
export async function inviteMemberAndJoin(
  owner: Page,
  ownerEmail: string,
  email: string,
  browser: Browser,
  request: APIRequestContext,
): Promise<Page> {
  await openVerificationLink(owner, await latestLink(request, ownerEmail, "EMAIL_VERIFICATION"));
  await owner.goto("/settings/members");
  await owner.waitForLoadState("networkidle");
  await owner.getByLabel("Email", { exact: true }).fill(email);
  await owner.getByRole("button", { name: "Send invitation" }).click();
  await expect(owner.getByText(`Invitation sent to ${email}.`)).toBeVisible();

  const context = await browser.newContext({ baseURL: "http://localhost:3000" });
  const member = await context.newPage();
  await member.goto(await latestLink(request, email, "INVITATION"));
  await member.waitForLoadState("networkidle");
  await member.getByLabel("Full name").fill("Mia Member");
  await member.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await member.getByRole("button", { name: "Create account and join" }).click();
  await expect(member).toHaveURL(/\/dashboard$/);
  return member;
}
