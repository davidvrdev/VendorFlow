import type { Subscription, SubscriptionStatus } from "./schemas";

const DAY_MS = 86_400_000;
export const TRIAL_WARNING_DAYS = 7;

/** Only Stripe-hosted pages may receive the browser: a tampered response must not become an open redirect. */
const ALLOWED_HOSTS = new Set(["checkout.stripe.com", "billing.stripe.com"]);

export function isAllowedStripeUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && ALLOWED_HOSTS.has(url.hostname) && url.username === "" && url.password === "";
  } catch {
    return false;
  }
}

/** Whole days left, rounded up (a trial ending in 3 hours is "1 day left"). Null when there is no valid end. */
export function daysLeft(iso: string | null, now: Date): number | null {
  if (!iso) return null;
  const end = Date.parse(iso);
  if (Number.isNaN(end)) return null;
  return Math.max(0, Math.ceil((end - now.getTime()) / DAY_MS));
}

export function formatDate(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString("en-US", { dateStyle: "medium" });
}

export const STATUS_LABEL: Record<SubscriptionStatus, string> = {
  TRIALING: "Trial",
  ACTIVE: "Active",
  PAST_DUE: "Payment past due",
  CANCELED: "Canceled",
  INCOMPLETE: "Incomplete",
  UNPAID: "Unpaid",
};

/** Portal only makes sense once a Stripe customer exists; the contract has no flag, so infer from status. */
export function billingAction(status: SubscriptionStatus): "manage" | "subscribe" {
  return status === "ACTIVE" || status === "PAST_DUE" ? "manage" : "subscribe";
}

export type BannerKind = "read-only" | "past-due" | "trial-ending";

export function bannerKind(sub: Subscription, now: Date): BannerKind | null {
  if (sub.readOnly) return "read-only";
  if (sub.status === "PAST_DUE") return "past-due";
  if (sub.status === "TRIALING") {
    const left = daysLeft(sub.trialEndsAt, now);
    if (left !== null && left <= TRIAL_WARNING_DAYS) return "trial-ending";
  }
  return null;
}
