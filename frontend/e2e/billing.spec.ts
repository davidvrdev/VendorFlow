import { expect, test } from "./support/test";
import { createHmac, randomUUID } from "node:crypto";
import { signUp, uniqueUser } from "./support/flows";

test.skip(!process.env.E2E_FULLSTACK, "requires backend (profile e2e) + Postgres");

test("billing: new workspace shows its trial; Subscribe explains that billing is not configured", async ({ page }) => {
  await signUp(page, uniqueUser("billing"));

  await page.goto("/settings/billing");
  await page.waitForLoadState("networkidle");
  await expect(page.getByText("Trial", { exact: true })).toBeVisible();
  await expect(page.getByText("Trial ends")).toBeVisible();

  // Local/e2e backend has no Stripe keys: 503 "Billing not configured" becomes a friendly message.
  await page.getByRole("button", { name: "Subscribe" }).click();
  await expect(page.getByText("Billing isn't configured yet")).toBeVisible();
  await expect(page.getByRole("button", { name: "Subscribe" })).toBeEnabled();
});

// ---- Stripe webhook (critical flow 10) ----
// The e2e backend has billing ON with a FIXED FAKE webhook secret (application-e2e.yml) and E2eBillingGateway instead
// of the Stripe API. The REAL signature verifier runs: this spec signs the payload like Stripe does.
const WEBHOOK_SECRET = "whsec_e2e_fake_not_a_real_secret";
const WEBHOOK_URL = "http://localhost:8080/api/v1/webhooks/stripe";

function signedHeaders(payload: string, secret = WEBHOOK_SECRET, timestamp = Math.floor(Date.now() / 1000)) {
  const signature = createHmac("sha256", secret).update(`${timestamp}.${payload}`).digest("hex");
  return { "content-type": "application/json", "stripe-signature": `t=${timestamp},v1=${signature}` };
}

function stripeEvent(type: string, object: Record<string, unknown>) {
  return JSON.stringify({
    id: `evt_e2e_${randomUUID().replace(/-/g, "")}`,
    object: "event",
    // Real events always carry it (the Stripe SDK dereferences it); the backend falls back when it differs from its own.
    api_version: "2025-01-01.e2e",
    created: Math.floor(Date.now() / 1000),
    livemode: false,
    pending_webhooks: 1,
    type,
    data: { object },
  });
}

test("stripe webhook: signed checkout makes the subscription Active, bad signature is rejected, deletion makes the workspace read-only", async ({
  page,
  request,
}) => {
  await signUp(page, uniqueUser("webhook"));
  await page.goto("/settings/billing");
  await page.waitForLoadState("networkidle");
  await expect(page.getByText("Trial", { exact: true })).toBeVisible();

  const me = await (await page.request.get("/api/v1/me")).json();
  const orgId: string = me.activeOrganization.id;
  const customer = `cus_e2e_${randomUUID().slice(0, 8)}`;

  // Forged / tampered requests change nothing.
  const checkout = stripeEvent("checkout.session.completed", {
    id: "cs_e2e_1",
    object: "checkout.session",
    customer,
    subscription: `sub_e2e_active_${customer}`,
    client_reference_id: orgId,
  });
  const wrongSecret = await request.post(WEBHOOK_URL, { data: checkout, headers: signedHeaders(checkout, "whsec_wrong") });
  expect(wrongSecret.status()).toBe(400);
  const tampered = await request.post(WEBHOOK_URL, { data: checkout + " ", headers: signedHeaders(checkout) });
  expect(tampered.status()).toBe(400);
  const stale = await request.post(WEBHOOK_URL, {
    data: checkout,
    headers: signedHeaders(checkout, WEBHOOK_SECRET, Math.floor(Date.now() / 1000) - 3600),
  });
  expect(stale.status()).toBe(400);
  await page.reload();
  await expect(page.getByText("Trial", { exact: true })).toBeVisible();

  // Valid event: customer bound through client_reference_id, state re-read from the (stub) gateway: ACTIVE.
  const ok = await request.post(WEBHOOK_URL, { data: checkout, headers: signedHeaders(checkout) });
  expect(ok.status()).toBe(200);
  // Replaying the identical event is idempotent.
  expect((await request.post(WEBHOOK_URL, { data: checkout, headers: signedHeaders(checkout) })).status()).toBe(200);
  await page.reload();
  await page.waitForLoadState("networkidle");
  await expect(page.getByText("Active", { exact: true })).toBeVisible();
  await expect(page.getByText("Renews on")).toBeVisible();
  await expect(page.getByTestId("subscription-banner")).toHaveCount(0);

  // Subscription deleted at Stripe: workspace becomes read-only (banner), writes are blocked with the 402 UX.
  const deleted = stripeEvent("customer.subscription.deleted", {
    id: `sub_e2e_canceled_${customer}`,
    object: "subscription",
    customer,
    status: "canceled",
  });
  expect((await request.post(WEBHOOK_URL, { data: deleted, headers: signedHeaders(deleted) })).status()).toBe(200);
  await page.goto("/vendors/new");
  await page.waitForLoadState("networkidle");
  await expect(page.getByTestId("subscription-banner")).toHaveAttribute("data-kind", "read-only");
  await page.getByLabel("Company name").fill("Blocked Vendor Inc");
  await page.getByRole("button", { name: "Add vendor" }).click();
  await expect(page).toHaveURL(/\/vendors\/new$/); // not created
  await expect(page.getByText(/workspace is read-only because the subscription is inactive/).first()).toBeVisible();

  // Reading still works.
  await page.goto("/vendors");
  await expect(page.getByRole("heading", { name: "No vendors yet" })).toBeVisible();
});
