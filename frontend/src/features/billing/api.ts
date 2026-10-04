import { apiFetch } from "@/lib/api/client";
import { redirectSchema, subscriptionSchema, type Subscription } from "./schemas";

export async function fetchSubscriptionClient(): Promise<Subscription> {
  return subscriptionSchema.parse(await apiFetch<unknown>("/billing/subscription"));
}

export async function createCheckoutSession(): Promise<string> {
  return redirectSchema.parse(await apiFetch<unknown>("/billing/checkout-session", { method: "POST" })).url;
}

export async function createPortalSession(): Promise<string> {
  return redirectSchema.parse(await apiFetch<unknown>("/billing/portal-session", { method: "POST" })).url;
}
