import { expect, test } from "./support/test";
import { openVerificationLink, signUp, uniqueUser } from "./support/flows";
import { allLinks, latestLink } from "./support/mailbox";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

test("chasing: owner enables follow-ups, the tick emails the vendor, the vendor unsubscribes anonymously, staff cannot resume", async ({ browser }) => {
  test.setTimeout(180_000);
  const owner = uniqueUser("chase");
  const company = `Chase Plumbing ${Date.now()}`;
  const vendorEmail = `vendor-${Date.now()}@example.com`;

  const staffContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const staff = await staffContext.newPage();
  await signUp(staff, owner);
  // The backend only lets a verified owner/admin turn on emails to third parties (the form shows its message otherwise).
  await openVerificationLink(staff, await latestLink(staff.request, owner.email, "EMAIL_VERIFICATION"));

  // Vendor with an email; the default requirements are all missing, so it is deficient.
  await staff.goto("/vendors/new");
  await staff.waitForLoadState("networkidle");
  await staff.getByLabel("Company name").fill(company);
  await staff.getByLabel("Email", { exact: true }).fill(vendorEmail);
  await staff.getByRole("button", { name: "Add vendor" }).click();
  await expect(staff.getByRole("heading", { name: company, level: 1 })).toBeVisible();
  const vendorUrl = staff.url();
  await staff.waitForLoadState("networkidle");

  // Off by default: the card says so and links to settings.
  await expect(staff.getByRole("heading", { name: "Automatic follow-ups", level: 2 })).toBeVisible();
  await expect(staff.getByText(/turned off for your organization/)).toBeVisible();

  // Owner enables follow-ups. Hour 0 makes "at or after the send hour" true whatever time the test runs.
  await staff.getByRole("link", { name: "Turn them on in settings" }).click();
  await expect(staff).toHaveURL(/\/settings\/chasing$/);
  await staff.waitForLoadState("networkidle");
  await staff.getByLabel("Send automatic follow-ups to vendors").click();
  await staff.getByLabel("Send hour (0 to 23)").fill("0");
  await staff.getByRole("button", { name: "Save changes" }).click();
  await expect(staff.getByText("Follow-up settings saved.")).toBeVisible();

  // Run the tick now (e2e-only endpoint) with the owner's session + CSRF header.
  const cookies = await staffContext.cookies();
  const xsrf = cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const run = await staff.request.post("http://localhost:8080/api/test/chasing/run", { headers: { "X-XSRF-TOKEN": xsrf } });
  expect(run.status(), await run.text()).toBe(200);
  expect(await run.json()).toMatchObject({ ran: true, chased: 1 });

  // The vendor got one email with an upload link and an unsubscribe link (both keep the token in the fragment).
  const links = await allLinks(staff.request, vendorEmail, "VENDOR_CHASE");
  const portalLink = links.find((l) => /\/portal#token=/.test(l));
  const unsubscribeLink = links.find((l) => /\/portal\/unsubscribe#token=/.test(l));
  expect(portalLink, "upload link in the chase email").toBeTruthy();
  expect(unsubscribeLink, "unsubscribe link in the chase email").toBeTruthy();

  // Vendor card: ACTIVE, attempt 1, and a history row.
  await staff.goto(vendorUrl);
  const card = staff.locator("#follow-ups");
  await expect(card.getByText("Active", { exact: true })).toBeVisible();
  await expect(card.getByText("1 of 4")).toBeVisible();
  const history = card.getByRole("list", { name: "Follow-up history" });
  await expect(history.getByText(/Follow-up 1/)).toBeVisible();
  await expect(history.getByText("Certificate of Insurance (missing)")).toBeVisible();

  // The vendor opens the unsubscribe link anonymously: opening changes nothing, the button does.
  const fragment = (unsubscribeLink as string).slice((unsubscribeLink as string).indexOf("#"));
  const vendorContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const vendor = await vendorContext.newPage();
  await vendor.goto(`/portal/unsubscribe${fragment}`);
  await expect(vendor.getByRole("heading", { name: `Unsubscribe ${company}?` })).toBeVisible();
  await expect(vendor).toHaveURL(/\/portal\/unsubscribe$/); // token removed from the address bar
  await staff.reload();
  await expect(card.getByText("Active", { exact: true })).toBeVisible(); // still active after a mere GET
  await vendor.waitForLoadState("networkidle");
  await vendor.getByRole("button", { name: "Unsubscribe from reminders" }).click();
  await expect(vendor.getByText("You are unsubscribed")).toBeVisible();

  // A reload has no token any more.
  await vendor.reload();
  await expect(vendor.getByText(/Open the unsubscribe link from your email again/)).toBeVisible();

  // Staff: the vendor shows as unsubscribed and cannot be resumed.
  await staff.reload();
  await expect(card.getByText("Vendor unsubscribed", { exact: true })).toBeVisible();
  await expect(card.getByRole("button", { name: "Resume follow-ups" })).toBeDisabled();

  await vendorContext.close();
  await staffContext.close();
});
