import { expect, test, type Page } from "@playwright/test";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

async function activeOrgId(page: Page): Promise<string> {
  const response = await page.request.get("/api/v1/me");
  expect(response.status()).toBe(200);
  const me = (await response.json()) as { activeOrganization: { id: string } };
  return me.activeOrganization.id;
}

test("user B cannot switch into org A and never sees org A's name", async ({ browser }) => {
  const a = uniqueUser("tenantA");
  const b = uniqueUser("tenantB");

  const contextA = await browser.newContext({ baseURL: "http://localhost:3000" });
  const pageA = await contextA.newPage();
  await signUp(pageA, a);
  const orgAId = await activeOrgId(pageA);

  const contextB = await browser.newContext({ baseURL: "http://localhost:3000" });
  const pageB = await contextB.newPage();
  await signUp(pageB, b);

  // B uses its own session cookie + CSRF token (read right before sending) to try org A's id.
  const cookies = await contextB.cookies();
  const xsrf = cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  expect(xsrf).not.toBe("");
  const attempt = await pageB.request.post("/api/v1/session/organization", {
    data: { organizationId: orgAId },
    headers: { "X-XSRF-TOKEN": xsrf },
  });
  expect(attempt.status()).toBe(404);

  // Still in B's own organization, and org A's name appears nowhere in B's settings.
  await pageB.goto("/settings/organization");
  await pageB.waitForLoadState("networkidle");
  await expect(pageB.getByLabel("Organization name")).toHaveValue(b.orgName);
  await expect(pageB.getByText(a.orgName)).toHaveCount(0);
  expect(await pageB.content()).not.toContain(a.orgName);
  await pageB.goto("/settings/members");
  await expect(pageB.getByRole("cell", { name: b.fullName })).toBeVisible();
  expect(await pageB.content()).not.toContain(a.email);

  await contextA.close();
  await contextB.close();
});
