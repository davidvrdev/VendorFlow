import { describe, expect, it } from "vitest";
import type { Subscription } from "./schemas";
import { bannerKind, billingAction, daysLeft, isAllowedStripeUrl } from "./view";

const NOW = new Date("2030-01-10T12:00:00Z");
const sub = (over: Partial<Subscription> = {}): Subscription => ({
  status: "ACTIVE",
  plan: "Pro",
  trialEndsAt: null,
  currentPeriodEnd: null,
  cancelAtPeriodEnd: false,
  readOnly: false,
  canManage: true,
  ...over,
});

describe("isAllowedStripeUrl", () => {
  it("accepts https checkout and billing hosts", () => {
    expect(isAllowedStripeUrl("https://checkout.stripe.com/c/pay/cs_test_1")).toBe(true);
    expect(isAllowedStripeUrl("https://billing.stripe.com/p/session/x")).toBe(true);
  });
  it("rejects http, other hosts, lookalikes, credentials and junk", () => {
    for (const url of [
      "http://checkout.stripe.com/x",
      "https://evil.com/x",
      "https://checkout.stripe.com.evil.com/x",
      "https://evilcheckout.stripe.com/x",
      "https://user:pw@checkout.stripe.com/x",
      "https://stripe.com/x",
      "javascript:alert(1)",
      "//checkout.stripe.com/x",
      "",
    ]) {
      expect(isAllowedStripeUrl(url), url).toBe(false);
    }
  });
});

describe("daysLeft", () => {
  it("rounds up and clamps at zero", () => {
    expect(daysLeft("2030-01-10T15:00:00Z", NOW)).toBe(1);
    expect(daysLeft("2030-01-13T12:00:00Z", NOW)).toBe(3);
    expect(daysLeft("2030-01-01T00:00:00Z", NOW)).toBe(0);
    expect(daysLeft(null, NOW)).toBeNull();
    expect(daysLeft("nope", NOW)).toBeNull();
  });
});

describe("bannerKind", () => {
  it("prioritises read-only, then past due, then ending trial", () => {
    expect(bannerKind(sub({ readOnly: true, status: "CANCELED" }), NOW)).toBe("read-only");
    expect(bannerKind(sub({ status: "PAST_DUE" }), NOW)).toBe("past-due");
    expect(bannerKind(sub({ status: "TRIALING", trialEndsAt: "2030-01-17T12:00:00Z" }), NOW)).toBe("trial-ending");
  });
  it("stays quiet for a long trial or an active plan", () => {
    expect(bannerKind(sub({ status: "TRIALING", trialEndsAt: "2030-01-30T12:00:00Z" }), NOW)).toBeNull();
    expect(bannerKind(sub(), NOW)).toBeNull();
  });
});

describe("billingAction", () => {
  it("manages active/past-due, subscribes otherwise", () => {
    expect(billingAction("ACTIVE")).toBe("manage");
    expect(billingAction("PAST_DUE")).toBe("manage");
    expect(billingAction("TRIALING")).toBe("subscribe");
    expect(billingAction("CANCELED")).toBe("subscribe");
  });
});
