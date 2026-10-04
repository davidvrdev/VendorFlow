import { expect, test, type Page } from "./support/test";
import { smallPdf, type UploadFile } from "./support/files";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const COI = "Certificate of Insurance";
const W9 = "W-9";
const WC = "Workers' Compensation";

/** yyyy-MM-dd, `days` from now (UTC; the e2e org uses a UTC-ish zone and the margins below are generous). */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

const row = (page: Page, typeName: string) => page.getByRole("row", { name: new RegExp(typeName) });

async function openRowMenu(page: Page, typeName: string) {
  await row(page, typeName).getByRole("button", { name: `Actions for ${typeName}` }).click();
}

async function upload(page: Page, typeName: string, menuItem: string, file: UploadFile, expiration?: string) {
  await page.waitForLoadState("networkidle");
  await openRowMenu(page, typeName);
  await page.getByRole("menuitem", { name: menuItem, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Upload document" });
  await dialog.getByLabel("File").setInputFiles(file);
  if (expiration) await dialog.getByLabel("Expiration date").fill(expiration);
  await dialog.getByRole("button", { name: /^(Upload|Replace) document$/ }).click();
  await expect(dialog).toBeHidden();
}

async function approve(page: Page, typeName: string) {
  await page.waitForLoadState("networkidle");
  await openRowMenu(page, typeName);
  await page.getByRole("menuitem", { name: "Approve" }).click();
  await expect(row(page, typeName).getByText("Needs review")).toBeHidden();
}

test("compliance: status moves through the lifecycle on the vendor page and in the list filter", async ({ browser }) => {
  test.setTimeout(240_000);
  const owner = uniqueUser("compliance");
  const company = `Compliance Roofing ${Date.now()}`;
  const context = await browser.newContext({ baseURL: "http://localhost:3000" });
  const page = await context.newPage();
  await signUp(page, owner);

  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(company);
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name: company, level: 1 })).toBeVisible();
  const vendorUrl = page.url();

  // New vendor: everything missing.
  await expect(page.getByText("Non-compliant").first()).toBeVisible();
  await expect(page.getByText("3 missing")).toBeVisible();

  // COI expiring in 10 days: pending review first.
  await upload(page, COI, "Upload", smallPdf("coi.pdf"), inDays(10));
  await expect(row(page, COI).getByText("Needs review")).toBeVisible();

  // Approved: Expiring soon. The vendor stays non-compliant (W-9 and Workers' Comp are still missing).
  await approve(page, COI);
  await expect(row(page, COI).getByText("Expiring soon")).toBeVisible();
  await expect(row(page, COI).getByText(/Expires in \d+ days?/)).toBeVisible();
  await expect(page.getByText("Non-compliant").first()).toBeVisible();

  // W-9 (no expiration) and Workers' Comp (1 year), approved.
  await upload(page, W9, "Upload", smallPdf("w9.pdf"));
  await approve(page, W9);
  await expect(row(page, W9).getByText("OK", { exact: true })).toBeVisible();
  await upload(page, WC, "Upload", smallPdf("wc.pdf"), inDays(365));
  await approve(page, WC);
  await expect(row(page, WC).getByText("OK", { exact: true })).toBeVisible();

  // Only the expiring COI is left: needs attention.
  await expect(page.getByText("Needs attention").first()).toBeVisible();

  // Push the COI expiration to 200 days: compliant.
  await page.waitForLoadState("networkidle");
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Edit dates" }).click();
  const dates = page.getByRole("dialog", { name: "Edit dates" });
  await dates.getByLabel("Expiration date").fill(inDays(200));
  await dates.getByRole("button", { name: "Save dates" }).click();
  await expect(dates).toBeHidden();
  await expect(row(page, COI).getByText("OK", { exact: true })).toBeVisible();
  await expect(page.getByText("Compliant", { exact: true }).first()).toBeVisible();

  // List filter: Compliant shows the vendor, Non-compliant does not.
  await page.goto("/vendors?compliance=COMPLIANT");
  await expect(page.getByRole("link", { name: company })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: /Next expiration/ })).toBeVisible();
  await page.goto("/vendors?compliance=NON_COMPLIANT");
  await expect(page.getByRole("link", { name: company })).toHaveCount(0);

  // A new COI with a past expiration: Expired, vendor non-compliant again.
  await page.goto(vendorUrl);
  await upload(page, COI, "Replace", smallPdf("coi-old.pdf"), inDays(-5));
  await expect(row(page, COI).getByText("Expired", { exact: true })).toBeVisible();
  await expect(row(page, COI).getByText(/Expired \d+ days? ago/)).toBeVisible();
  await expect(page.getByText("Non-compliant").first()).toBeVisible();

  await context.close();
});
