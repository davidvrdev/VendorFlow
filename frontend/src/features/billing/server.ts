import "server-only";
import { serverApiFetch } from "@/lib/api/server";
import { subscriptionSchema, type Subscription } from "./schemas";

/** Null on any failure: a billing outage must never take the whole app down; the banner just stays hidden. */
export async function fetchSubscriptionOrNull(): Promise<Subscription | null> {
  try {
    return subscriptionSchema.parse(await serverApiFetch<unknown>("/billing/subscription"));
  } catch {
    return null;
  }
}
