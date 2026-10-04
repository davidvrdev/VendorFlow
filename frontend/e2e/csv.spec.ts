import { expect, test, type Page } from "@playwright/test";
import { signUp, uniqueUser } from "./support/flows";
import { inviteMemberAndJoin } from "./support/members";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

const csvFile = (name: string, content: string) => ({ name, mimeType: "text/csv", buffer: Buffer.from(content, "utf8") });

async function upload(page: Page, file: ReturnType<typeof csvFile>) {
  await page.getByLabel("CSV file").setInputFiles(file);
  await page.getByRole("button", { name: "Upload & preview" }).click();
}

test("csv: export, import preview with errors, corrected import, member is redirected", async ({ browser, request }) => {
  test.setTimeout(240_000);
  const owner = uniqueUser("csv");
  const stamp = Date.now();
  const existing = `Csv Existing ${stamp}`;
  const newA = `Csv Alpha ${stamp}`;
  const newB = `Csv Beta ${stamp}`;
  const badCo = `Csv Broken ${stamp}`;

  const context = await browser.newContext({ baseURL: "http://localhost:3000" });
  const page = await context.newPage();
  await signUp(page, owner);

  // One vendor through the UI.
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await page.getByLabel("Company name").fill(existing);
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page.getByRole("heading", { name: existing, level: 1 })).toBeVisible();

  // Export: a real download with BOM, header and the vendor.
  await page.goto("/vendors");
  await page.waitForLoadState("networkidle");
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "Export CSV" }).click()]);
  expect(download.suggestedFilename()).toMatch(/^vendors-\d{4}-\d{2}-\d{2}\.csv$/);
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  const bytes = Buffer.concat(chunks);
  expect([...bytes.subarray(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
  const text = bytes.toString("utf8").slice(1);
  expect(text.split(/\r?\n/)[0]).toContain("company_name");
  expect(text).toContain(existing);

  // Import: 2 new (one with a quoted multiline note), 1 update of the phone, 1 invalid email.
  await page.getByRole("link", { name: "Import CSV" }).click();
  await expect(page).toHaveURL(/\/vendors\/import$/);
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("link", { name: "Download template" })).toHaveAttribute("href", "/api/v1/vendors/import/template.csv");

  const header = "company_name,contact_name,email,phone,category,notes,status";
  const rows = {
    a: `${newA},Ann,ann@example.com,555-0101,Plumbing,"line one\nline two, with comma",ACTIVE`,
    b: `${newB},Bob,bob@example.com,555-0102,Roofing,,`,
    update: `${existing},,,555-0199,,,`,
  };
  await upload(page, csvFile("bad.csv", [header, rows.a, rows.update, `${badCo},,not-an-email,,,,`].join("\n")));
  await expect(page.getByText("1 with errors")).toBeVisible();
  await expect(page.getByText(/email: /)).toBeVisible();
  await expect(page.getByText("Fix the errors in your file and upload it again.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Import vendors" })).toBeDisabled();

  // Corrected file.
  await page.getByRole("button", { name: "Upload a different file" }).click();
  await upload(page, csvFile("good.csv", [header, rows.a, rows.b, rows.update].join("\n")));
  await expect(page.getByText("2 to create")).toBeVisible();
  await expect(page.getByText("1 to update")).toBeVisible();
  await expect(page.getByText("0 with errors")).toBeVisible();
  await expect(page.getByText(/This preview expires at/)).toBeVisible();

  await page.getByRole("button", { name: "Import vendors" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Create 2 vendors and update 1 vendor?");
  await page.getByRole("alertdialog").getByRole("button", { name: "Import vendors" }).click();
  await expect(page.getByText("2 vendors created, 1 vendor updated, 0 unchanged.")).toBeVisible();

  // New vendors are in the list with default requirements (Non-compliant); the update landed on the detail page.
  await page.getByRole("link", { name: "Go to vendors" }).click();
  await expect(page).toHaveURL(/\/vendors$/);
  for (const name of [newA, newB]) {
    await expect(page.getByRole("row", { name: new RegExp(name) }).getByText("Non-compliant")).toBeVisible();
  }
  await page.getByRole("link", { name: existing }).click();
  await expect(page.getByRole("heading", { name: existing, level: 1 })).toBeVisible();
  await expect(page.getByRole("link", { name: "555-0199" })).toBeVisible();

  // A member cannot use the import page and sees no Import button.
  const member = await inviteMemberAndJoin(page, owner.email, `csv-member-${stamp}@example.com`, browser, request);
  await member.goto("/vendors/import");
  await expect(member).toHaveURL(/\/vendors$/);
  await member.waitForLoadState("networkidle");
  await expect(member.getByRole("link", { name: "Import CSV" })).toHaveCount(0);
  await expect(member.getByRole("link", { name: "Export CSV" })).toBeVisible();
});
