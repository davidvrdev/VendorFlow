import { expect, test, type Page } from "./support/test";
import { smallPdf } from "./support/files";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const COI = "Certificate of Insurance";
const INVALID = "This link is invalid or has expired. Ask the company that sent it for a new one.";

async function createVendor(page: Page, company: string) {
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(company);
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name: company, level: 1 })).toBeVisible();
  await page.waitForLoadState("networkidle");
}

test("portal: staff creates a link, the vendor uploads anonymously, staff sees a pending portal document, revoke kills the link", async ({ browser }) => {
  test.setTimeout(180_000);
  const owner = uniqueUser("portal");
  const company = `Portal Plumbing ${Date.now()}`;

  const staffContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const staff = await staffContext.newPage();
  await signUp(staff, owner);
  await createVendor(staff, company);

  // Staff: create a link (the default requirements are all missing, so all are preselected).
  await staff.getByRole("button", { name: "Send upload link" }).click();
  const dialog = staff.getByRole("dialog", { name: "Send upload link" });
  await expect(dialog.getByRole("checkbox", { name: COI })).toBeChecked();
  await dialog.getByRole("button", { name: "Create link" }).click();
  const url = await staff.getByLabel("Upload link", { exact: true }).inputValue();
  expect(url).toMatch(/\/portal#token=.+/);
  await expect(staff.getByText(/shown only once/)).toBeVisible();
  await staff.getByRole("button", { name: "Done" }).click();
  const list = staff.getByRole("list", { name: "Upload links" });
  await expect(list.getByText("Active")).toBeVisible();

  // The origin in APP_BASE_URL may differ from the test server: only the fragment matters.
  const fragment = url.slice(url.indexOf("#"));

  // Vendor: anonymous context, no session.
  const vendorContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const vendor = await vendorContext.newPage();
  await vendor.goto(`/portal${fragment}`);
  await expect(vendor.getByRole("heading", { name: new RegExp(`Documents for ${owner.orgName}`) })).toBeVisible();
  await expect(vendor).toHaveURL(/\/portal$/); // token removed from the address bar
  await expect(vendor.getByText(company)).toBeVisible();
  await vendor.waitForLoadState("networkidle");

  await vendor.getByLabel(`File for ${COI}`).setInputFiles(smallPdf("portal-coi.pdf"));
  await vendor.getByLabel("Expiration date").first().fill("2031-01-15");
  await vendor.getByRole("button", { name: `Upload ${COI}` }).click();
  await expect(vendor.getByText(/Uploaded portal-coi\.pdf/)).toBeVisible();
  await expect(vendor.getByText("Received, awaiting review")).toBeVisible();

  // A reload has no token any more.
  await vendor.reload();
  await expect(vendor.getByText(/Open the link from your email again/)).toBeVisible();

  // Staff sees the pending document marked as a portal upload.
  await staff.reload();
  await staff.waitForLoadState("networkidle");
  const row = staff.getByRole("row", { name: new RegExp(COI) });
  await expect(row.getByText("portal-coi.pdf")).toBeVisible();
  await expect(row.getByText("Via portal")).toBeVisible();
  await expect(row.getByText("Pending review")).toBeVisible();

  // Revoke: the link stops working.
  await staff.getByRole("list", { name: "Upload links" }).getByRole("button", { name: /Revoke link created/ }).click();
  await staff.getByRole("alertdialog").getByRole("button", { name: "Revoke link" }).click();
  await expect(staff.getByRole("list", { name: "Upload links" }).getByText("Revoked")).toBeVisible();

  // Same path + new hash would be a same-document navigation; leave first, like opening the email link afresh.
  await vendor.goto("about:blank");
  await vendor.goto(`/portal${fragment}`);
  await expect(vendor.getByText(INVALID)).toBeVisible();

  await vendorContext.close();
  await staffContext.close();
});
