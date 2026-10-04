import { z } from "zod";

export const SUBSCRIPTION_STATUSES = ["TRIALING", "ACTIVE", "PAST_DUE", "CANCELED", "INCOMPLETE", "UNPAID"] as const;

export const subscriptionSchema = z.object({
  status: z.enum(SUBSCRIPTION_STATUSES),
  plan: z.string(),
  trialEndsAt: z.string().nullable(),
  currentPeriodEnd: z.string().nullable(),
  cancelAtPeriodEnd: z.boolean(),
  readOnly: z.boolean(),
  canManage: z.boolean(),
});

export const redirectSchema = z.object({ url: z.string() });

export type Subscription = z.infer<typeof subscriptionSchema>;
export type SubscriptionStatus = Subscription["status"];
