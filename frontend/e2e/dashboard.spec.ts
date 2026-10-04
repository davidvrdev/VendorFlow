import { expect, test, type Page } from "./support/test";
import { smallPdf } from "./support/files";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const COI = "Certificate of Insurance";

function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

async function addVendor(page: Page, name: string) {
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(name);
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name, level: 1 })).toBeVisible();
}

test("dashboard: onboarding, attention list actions and filtered tile links", async ({ browser }) => {
  test.setTimeout(240_000);
  const owner = uniqueUser("dashboard");
  const stamp = Date.now();
  const alpha = `Alpha Plumbing ${stamp}`;
  const beta = `Beta Roofing ${stamp}`;
  const context = await browser.newContext({ baseURL: "http://localhost:3000" });
  const page = await context.newPage();
  await signUp(page, owner);

  // New org: onboarding empty state.
  await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Add your first vendor" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Import CSV" })).toHaveAttribute("href", "/vendors/import");

  await addVendor(page, alpha);
  await addVendor(page, beta);

  // 2 active, 2 non-compliant, 6 missing documents.
  await page.goto("/dashboard");
  await expect(page.getByRole("link", { name: /Active vendors/ })).toContainText("2");
  await expect(page.getByRole("link", { name: /Non-compliant/ })).toContainText("2");
  await expect(page.getByTestId("documents-line")).toContainText("6 missing");
  await expect(page.getByRole("heading", { name: "Needs attention", level: 2 })).toBeVisible();

  // First item is MISSING with an Upload action.
  const items = page.getByRole("listitem").filter({ has: page.getByRole("button", { name: /^Upload / }) });
  await expect(items.first().getByText("Missing")).toBeVisible();

  // Upload a COI expiring in 10 days from the dashboard dialog.
  await page.waitForLoadState("networkidle");
  await page.getByRole("button", { name: `Upload ${COI} for ${alpha}` }).click();
  const dialog = page.getByRole("dialog", { name: "Upload document" });
  await dialog.getByLabel("File").setInputFiles(smallPdf("coi.pdf"));
  await dialog.getByLabel("Expiration date").fill(inDays(10));
  await dialog.getByRole("button", { name: /^(Upload|Replace) document$/ }).click();
  await expect(dialog).toBeHidden();

  // The item became "Needs review" with a Review action.
  const reviewButton = page.getByRole("button", { name: `Review ${COI} for ${alpha}` });
  await expect(reviewButton).toBeVisible();
  await expect(page.getByRole("button", { name: `Upload ${COI} for ${alpha}` })).toHaveCount(0);

  // Approve from the dashboard: it becomes Expiring with Upload renewal.
  await page.waitForLoadState("networkidle");
  await reviewButton.click();
  await page.getByRole("dialog", { name: "Review document" }).getByRole("button", { name: "Approve" }).click();
  const renewal = page.getByRole("button", { name: `Upload renewal ${COI} for ${alpha}` });
  await expect(renewal).toBeVisible();
  await expect(page.getByRole("listitem").filter({ has: renewal }).getByText("Expiring soon")).toBeVisible();

  // Tiles link to the filtered vendor list (wait for the post-approve refresh first, or the click can race it).
  await page.waitForLoadState("networkidle");
  await page.getByRole("link", { name: /Non-compliant/ }).click();
  await expect(page).toHaveURL(/\/vendors\?compliance=NON_COMPLIANT/);
  await expect(page.getByRole("link", { name: alpha })).toBeVisible();

  await context.close();
});
