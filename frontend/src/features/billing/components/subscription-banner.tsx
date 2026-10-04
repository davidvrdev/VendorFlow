"use client";

import { AlertTriangle, Clock, Lock, type LucideIcon } from "lucide-react";
import Link from "next/link";
import { cn } from "@/lib/utils";
import type { Subscription } from "../schemas";
import { bannerKind, daysLeft, type BannerKind } from "../view";
import { useSubscription } from "./subscription-provider";

const TONE: Record<BannerKind, { Icon: LucideIcon; className: string }> = {
  "trial-ending": { Icon: Clock, className: "border-sky-200 bg-sky-50 text-sky-900 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-100" },
  "past-due": { Icon: AlertTriangle, className: "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-100" },
  "read-only": { Icon: Lock, className: "border-red-300 bg-red-50 text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100" },
};

function message(kind: BannerKind, sub: Subscription, now: Date): string {
  if (kind === "read-only") return "Your workspace is read-only. Subscribe to make changes.";
  if (kind === "past-due") return "Your last payment failed. Update your payment method to avoid losing access.";
  const left = daysLeft(sub.trialEndsAt, now) ?? 0;
  return left === 0 ? "Your trial ends today." : `Your trial ends in ${left} ${left === 1 ? "day" : "days"}.`;
}

/** Pure presentation, exported for tests. Errors use role="alert"; informational/warning use role="status". */
export function SubscriptionBannerView({ subscription, now = new Date() }: { subscription: Subscription; now?: Date }) {
  const kind = bannerKind(subscription, now);
  if (!kind) return null;
  const { Icon, className } = TONE[kind];
  const linkLabel = kind === "read-only" ? "Go to billing" : kind === "past-due" ? "Update billing" : "View plan";
  return (
    <div
      role={kind === "read-only" ? "alert" : "status"}
      data-testid="subscription-banner"
      data-kind={kind}
      className={cn("flex flex-wrap items-center gap-x-4 gap-y-1 border-b px-4 py-2 text-sm sm:px-8", className)}
    >
      <Icon className="size-4 shrink-0" aria-hidden="true" />
      <p className="flex-1">{message(kind, subscription, now)}</p>
      {/* Owners can act; others are told who can. The backend enforces either way. */}
      {subscription.canManage ? (
        <Link href="/settings/billing" className="rounded-sm font-medium underline underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-ring">
          {linkLabel}
        </Link>
      ) : (
        <span className="text-xs">Ask a workspace owner.</span>
      )}
    </div>
  );
}

export function SubscriptionBanner() {
  const { subscription } = useSubscription();
  return subscription ? <SubscriptionBannerView subscription={subscription} /> : null;
}
