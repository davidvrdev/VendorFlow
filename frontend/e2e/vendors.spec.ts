import { expect, test, type Page } from "@playwright/test";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const DEFAULT_REQUIREMENTS = ["Certificate of Insurance", "Workers' Compensation", "W-9"];

function vendorIdFromUrl(page: Page): string {
  const match = /\/vendors\/([^/?#]+)/.exec(page.url());
  if (!match || match[1] === "new") throw new Error(`Not on a vendor page: ${page.url()}`);
  return match[1];
}

test("vendor lifecycle: create, duplicate, edit, requirements, search, deactivate, reactivate, isolation", async ({ browser }) => {
  const owner = uniqueUser("vendors");
  const company = `Acme Plumbing ${Date.now()}`;

  const contextA = await browser.newContext({ baseURL: "http://localhost:3000" });
  const page = await contextA.newPage();
  await signUp(page, owner);

  // Empty organization.
  await page.goto("/vendors");
  await expect(page.getByRole("heading", { name: "No vendors yet" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Import CSV/ })).toBeDisabled();

  // Create.
  await page.getByRole("link", { name: "Add vendor" }).click();
  await expect(page).toHaveURL(/\/vendors\/new$/);
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(company);
  await page.getByLabel("Category").fill("Plumbing");
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name: company, level: 1 })).toBeVisible();
  const vendorId = vendorIdFromUrl(page);

  // Default requirements.
  for (const name of DEFAULT_REQUIREMENTS) await expect(page.getByText(name, { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "History", exact: true })).toBeVisible();
  await expect(page.getByText("Vendor created")).toBeVisible();

  // Duplicate name (case-insensitive) is a field error.
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(company.toUpperCase());
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByText("A vendor with this name already exists.")).toBeVisible();
  await expect(page).toHaveURL(/\/vendors\/new$/);

  // Edit contact.
  await page.goto(`/vendors/${vendorId}`);
  await page.getByRole("link", { name: "Edit" }).click();
  await expect(page).toHaveURL(new RegExp(`/vendors/${vendorId}/edit$`));
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Contact name").fill("Dana Reyes");
  await page.getByLabel("Email").fill("dana@acme-plumbing.example");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page).toHaveURL(new RegExp(`/vendors/${vendorId}$`));
  await expect(page.getByText("Dana Reyes").first()).toBeVisible();
  await expect(page.getByRole("link", { name: "dana@acme-plumbing.example" })).toHaveAttribute("href", "mailto:dana@acme-plumbing.example");
  await expect(page.getByText("Details updated")).toBeVisible();
  await expect(page.getByText("Contact name: empty → Dana Reyes")).toBeVisible();

  // Edit requirements: add General Liability, remove W-9.
  await page.waitForLoadState("networkidle");
  await page.getByRole("button", { name: "Edit requirements" }).click();
  const dialog = page.getByRole("dialog", { name: "Edit required documents" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("checkbox", { name: /General Liability/ }).check();
  await dialog.getByRole("checkbox", { name: /W-9/ }).uncheck();
  await dialog.getByRole("button", { name: "Save requirements" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("General Liability", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("W-9", { exact: true })).toHaveCount(0);
  await expect(page.getByText(/Requirements changed/)).toBeVisible();

  // List: search finds it; other terms do not.
  await page.goto("/vendors");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Search vendors").fill(company.slice(0, 18));
  await expect(page).toHaveURL(/[?&]q=/);
  await expect(page.getByRole("link", { name: company })).toBeVisible();
  await page.getByLabel("Search vendors").fill("zzz-no-such-vendor");
  await expect(page.getByRole("heading", { name: "No vendors match your filters" })).toBeVisible();
  await page.getByRole("link", { name: "Clear filters" }).click();
  await expect(page.getByRole("link", { name: company })).toBeVisible();

  // Deactivate -> only visible under Inactive.
  await page.goto(`/vendors/${vendorId}`);
  await page.waitForLoadState("networkidle");
  await page.getByRole("button", { name: "Deactivate" }).click();
  await page.getByRole("button", { name: "Deactivate vendor" }).click();
  await expect(page.getByText("Inactive", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reactivate" })).toBeVisible();

  await page.goto("/vendors");
  await expect(page.getByRole("heading", { name: "No active vendors" })).toBeVisible();
  await page.getByLabel("Status").click();
  await page.getByRole("option", { name: "Inactive" }).click();
  await expect(page).toHaveURL(/status=INACTIVE/);
  await expect(page.getByRole("link", { name: company })).toBeVisible();

  // Reactivate.
  await page.getByRole("link", { name: company }).click();
  await page.waitForLoadState("networkidle");
  await page.getByRole("button", { name: "Reactivate" }).click();
  await page.getByRole("button", { name: "Reactivate vendor" }).click();
  await expect(page.getByRole("button", { name: "Deactivate" })).toBeVisible();
  await expect(page.getByText("Vendor reactivated")).toBeVisible();

  // Another organization cannot see this vendor, and the page does not reveal whether it exists.
  const contextB = await browser.newContext({ baseURL: "http://localhost:3000" });
  const pageB = await contextB.newPage();
  await signUp(pageB, uniqueUser("vendorsB"));
  await pageB.goto(`/vendors/${vendorId}`);
  await expect(pageB.getByRole("heading", { name: "Vendor not found" })).toBeVisible();
  expect(await pageB.content()).not.toContain(company);

  await contextA.close();
  await contextB.close();
});
