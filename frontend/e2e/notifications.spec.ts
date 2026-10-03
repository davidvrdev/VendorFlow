import { expect, test, type Page } from "@playwright/test";
import { smallPdf } from "./support/files";
import { openVerificationLink, signUp, uniqueUser } from "./support/flows";
import { latestLink, waitForMessage } from "./support/mailbox";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const COI = "Certificate of Insurance";
const W9 = "W-9";

function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

const row = (page: Page, typeName: string) => page.getByRole("row", { name: new RegExp(typeName) });

test("notifications: document request, daily digest and email activity", async ({ browser }) => {
  test.setTimeout(240_000);
  const owner = uniqueUser("notify");
  const vendorEmail = `vendor-${Date.now()}@example.com`;
  const company = `Notify Plumbing ${Date.now()}`;
  const context = await browser.newContext({ baseURL: "http://localhost:3000" });
  const page = await context.newPage();
  await signUp(page, owner);

  // Reminders and requests need a verified owner email.
  await openVerificationLink(page, await latestLink(page.request, owner.email, "EMAIL_VERIFICATION"));

  // Vendor with an email.
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(company);
  await page.getByLabel("Email").fill(vendorEmail);
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name: company, level: 1 })).toBeVisible();

  // Request the W-9.
  await page.waitForLoadState("networkidle");
  await row(page, W9).getByRole("button", { name: `Request from vendor: ${W9}` }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog).toContainText(vendorEmail);
  await dialog.getByRole("button", { name: "Send request" }).click();
  await expect(page.getByText(`Request sent to ${vendorEmail}`)).toBeVisible();

  const request = await waitForMessage(page.request, vendorEmail, "DOCUMENT_REQUEST");
  expect(request.subject).toContain(W9);
  await expect(page.getByText(`Requested ${W9} from ${vendorEmail}`)).toBeVisible();

  // Same day, same type: rejected.
  await page.waitForLoadState("networkidle");
  await row(page, W9).getByRole("button", { name: `Request from vendor: ${W9}` }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Send request" }).click();
  await expect(page.getByRole("alertdialog").getByText("Already requested today")).toBeVisible();
  await page.getByRole("alertdialog").getByRole("button", { name: "Cancel" }).click();

  // COI expiring in 5 days, approved.
  await page.waitForLoadState("networkidle");
  await row(page, COI).getByRole("button", { name: `Actions for ${COI}` }).click();
  await page.getByRole("menuitem", { name: "Upload", exact: true }).click();
  const upload = page.getByRole("dialog", { name: "Upload document" });
  await upload.getByLabel("File").setInputFiles(smallPdf("coi.pdf"));
  await upload.getByLabel("Expiration date").fill(inDays(5));
  await upload.getByRole("button", { name: /^(Upload|Replace) document$/ }).click();
  await expect(upload).toBeHidden();
  await page.waitForLoadState("networkidle");
  await row(page, COI).getByRole("button", { name: `Actions for ${COI}` }).click();
  await page.getByRole("menuitem", { name: "Approve" }).click();
  await expect(row(page, COI).getByText("Needs review")).toBeHidden();

  // Run the reminder job now (e2e-only endpoint) with the owner's session + CSRF header.
  const cookies = await context.cookies();
  const xsrf = cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const run = await page.request.post("http://localhost:8080/api/test/reminders/run", { headers: { "X-XSRF-TOKEN": xsrf } });
  expect(run.ok()).toBe(true);

  const digest = await waitForMessage(page.request, owner.email, "COMPLIANCE_DIGEST");
  expect(digest.content).toContain(company);

  // Email activity lists both as Sent.
  await page.goto("/settings/email-activity");
  await expect(page.getByRole("heading", { name: "Email activity", level: 2 })).toBeVisible();
  const requestRow = page.getByRole("row", { name: new RegExp(`Document request.*${vendorEmail}`) });
  await expect(requestRow.getByText("Sent")).toBeVisible();
  const digestRow = page.getByRole("row", { name: new RegExp(`Compliance digest.*${owner.email}`) });
  await expect(digestRow.getByText("Sent")).toBeVisible();

  await context.close();
});
