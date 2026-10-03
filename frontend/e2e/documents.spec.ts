import { readFile } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import { fakePdf, largePdf, smallPdf, smallPng, type UploadFile } from "./support/files";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const COI = "Certificate of Insurance";

function vendorIdFromUrl(page: Page): string {
  const match = /\/vendors\/([^/?#]+)/.exec(page.url());
  if (!match || match[1] === "new") throw new Error(`Not on a vendor page: ${page.url()}`);
  return match[1];
}

const row = (page: Page, typeName: string) => page.getByRole("row", { name: new RegExp(typeName) });

async function openRowMenu(page: Page, typeName: string) {
  await row(page, typeName).getByRole("button", { name: `Actions for ${typeName}` }).click();
}

/** Fill and submit the upload dialog that is already open. */
async function submitUpload(page: Page, file: UploadFile, dates: { issue?: string; expiration?: string } = {}) {
  const dialog = page.getByRole("dialog", { name: "Upload document" });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("File").setInputFiles(file);
  if (dates.issue) await dialog.getByLabel("Issue date").fill(dates.issue);
  if (dates.expiration) await dialog.getByLabel("Expiration date").fill(dates.expiration);
  await dialog.getByRole("button", { name: /^(Upload|Replace) document$/ }).click();
  return dialog;
}

test("documents: upload, review, replace, history, download, reject, archive, validation, big upload, types, isolation", async ({ browser }) => {
  test.setTimeout(240_000);
  const owner = uniqueUser("documents");
  const company = `Docs Plumbing ${Date.now()}`;

  const contextA = await browser.newContext({ baseURL: "http://localhost:3000" });
  const page = await contextA.newPage();
  await signUp(page, owner);

  // A vendor with the default requirements (COI, Workers' Compensation, W-9).
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(company);
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name: company, level: 1 })).toBeVisible();
  await page.waitForLoadState("networkidle");
  const vendorId = vendorIdFromUrl(page);
  await expect(row(page, COI).getByText("Missing")).toBeVisible();

  // Upload a PDF for COI (expiration required for this type).
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Upload", exact: true }).click();
  const dialog = await submitUpload(page, smallPdf("coi.pdf"), { issue: "2030-01-15", expiration: "2031-01-15" });
  await expect(dialog).toBeHidden();
  await expect(row(page, COI).getByText("coi.pdf")).toBeVisible();
  await expect(row(page, COI).getByText("Pending review")).toBeVisible();
  await expect(page.getByText("Document uploaded", { exact: true }).first()).toBeVisible(); // vendor history

  // Approve.
  await page.waitForLoadState("networkidle");
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Approve" }).click();
  await expect(row(page, COI).getByText("Approved")).toBeVisible();

  // Replace with a PNG: warning shown, history lists the superseded PDF.
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Replace" }).click();
  const replaceDialog = page.getByRole("dialog", { name: "Upload document" });
  await expect(replaceDialog.getByText("This will replace the current document (kept in history).")).toBeVisible();
  await submitUpload(page, smallPng("coi.png"), { issue: "2030-02-01", expiration: "2031-02-01" });
  await expect(replaceDialog).toBeHidden();
  await expect(row(page, COI).getByText("coi.png")).toBeVisible();
  await expect(row(page, COI).getByText("Pending review")).toBeVisible();
  await page.getByRole("button", { name: "Show document history" }).click();
  const history = page.locator("#document-history-list");
  await expect(history.getByText("Superseded")).toBeVisible();
  await expect(history.getByText("coi.pdf")).toBeVisible();

  // Download returns the stored file as an attachment.
  await openRowMenu(page, COI);
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("menuitem", { name: "Download" }).click(),
  ]);
  expect(download.suggestedFilename()).toBe("coi.png");
  const downloadedPath = await download.path();
  expect((await readFile(downloadedPath)).equals(smallPng().buffer)).toBe(true);

  // Reject needs a note.
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Reject…" }).click();
  const rejectDialog = page.getByRole("dialog", { name: "Reject document" });
  await rejectDialog.getByRole("button", { name: "Reject document" }).click();
  await expect(rejectDialog.getByText("Explain why the document is rejected.")).toBeVisible();
  await rejectDialog.getByLabel("Reason for rejection").fill("Policy number is not legible.");
  await rejectDialog.getByRole("button", { name: "Reject document" }).click();
  await expect(rejectDialog).toBeHidden();
  await expect(row(page, COI).getByText("Rejected")).toBeVisible();
  await expect(row(page, COI).getByText("Note: Policy number is not legible.")).toBeVisible();

  // Archive (owner): the requirement shows Missing again.
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Archive…" }).click();
  await page.getByRole("alertdialog", { name: `Archive ${COI}?` }).getByRole("button", { name: "Archive document" }).click();
  await expect(row(page, COI).getByText("Missing")).toBeVisible();

  // A text file named .pdf passes the client check but fails the server's magic-byte check.
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Upload", exact: true }).click();
  const badDialog = await submitUpload(page, fakePdf(), { expiration: "2031-01-15" });
  await expect(badDialog.getByText("Only PDF, PNG or JPG files are accepted.")).toBeVisible();
  await badDialog.getByRole("button", { name: "Cancel" }).click();
  await expect(badDialog).toBeHidden();

  // ~14 MB must pass through the Next.js rewrite untouched (proxyClientMaxBodySize in next.config.ts).
  await openRowMenu(page, COI);
  await page.getByRole("menuitem", { name: "Upload", exact: true }).click();
  const bigDialog = await submitUpload(page, largePdf(14, "big.pdf"), { expiration: "2031-01-15" });
  await expect(bigDialog).toBeHidden({ timeout: 120_000 });
  await expect(row(page, COI).getByText("big.pdf")).toBeVisible();

  // Document types: create a custom type, then require it for the vendor.
  await page.goto("/settings/document-types");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Name").fill("Pollution Liability");
  await page.getByRole("checkbox", { name: "Has an expiration date" }).check();
  await page.getByRole("button", { name: "Add document type" }).click();
  await expect(page.getByRole("row", { name: /Pollution Liability/ })).toBeVisible();
  // Duplicate name (case-insensitive) is a field error.
  await page.getByLabel("Name").fill("pollution liability");
  await page.getByRole("button", { name: "Add document type" }).click();
  await expect(page.getByText("A document type with this name already exists.")).toBeVisible();

  await page.goto(`/vendors/${vendorId}`);
  await page.waitForLoadState("networkidle");
  await page.getByRole("button", { name: "Edit requirements" }).click();
  const requirements = page.getByRole("dialog", { name: "Edit required documents" });
  await requirements.getByRole("checkbox", { name: /Pollution Liability/ }).check();
  await requirements.getByRole("button", { name: "Save requirements" }).click();
  await expect(requirements).toBeHidden();
  await expect(row(page, "Pollution Liability").getByText("Missing")).toBeVisible();

  // Another organization cannot download this document: foreign ids are 404.
  const listing = await page.request.get(`/api/v1/vendors/${vendorId}/documents`);
  expect(listing.ok()).toBe(true);
  const documents = (await listing.json()) as { id: string }[];
  expect(documents.length).toBeGreaterThan(0);
  const documentId = documents[0].id;
  expect((await page.request.get(`/api/v1/documents/${documentId}/download`)).status()).toBe(200);

  const contextB = await browser.newContext({ baseURL: "http://localhost:3000" });
  const pageB = await contextB.newPage();
  await signUp(pageB, uniqueUser("documentsB"));
  expect((await pageB.request.get(`/api/v1/documents/${documentId}/download`)).status()).toBe(404);

  await contextA.close();
  await contextB.close();
});
