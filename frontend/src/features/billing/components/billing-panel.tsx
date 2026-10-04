"use client";

import { AlertTriangle, CheckCircle2, Clock, Info, Lock, XCircle, type LucideIcon } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError } from "@/lib/api/errors";
import { cn } from "@/lib/utils";
import { createCheckoutSession, createPortalSession } from "../api";
import type { Subscription, SubscriptionStatus } from "../schemas";
import { billingAction, daysLeft, formatDate, isAllowedStripeUrl, STATUS_LABEL } from "../view";
import { useSubscription } from "./subscription-provider";

const AMBER = "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200";
const RED = "border-red-300 bg-red-100 text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100";

const STATUS_TONE: Record<SubscriptionStatus, { Icon: LucideIcon; className: string }> = {
  ACTIVE: { Icon: CheckCircle2, className: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200" },
  TRIALING: { Icon: Clock, className: "border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-200" },
  PAST_DUE: { Icon: AlertTriangle, className: AMBER },
  INCOMPLETE: { Icon: Info, className: AMBER },
  UNPAID: { Icon: XCircle, className: RED },
  CANCELED: { Icon: Lock, className: RED },
};

/** Status is always icon + text + color. */
export function BillingStatusBadge({ status }: { status: SubscriptionStatus }) {
  const { Icon, className } = STATUS_TONE[status];
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", className)}>
      <Icon aria-hidden="true" />
      <span>{STATUS_LABEL[status]}</span>
    </Badge>
  );
}

export const NOT_CONFIGURED = "Billing isn't configured yet. Please try again later or contact support.";

/** Redirect target comes from the API; refuse anything that is not a Stripe-hosted https page. */
export function redirectTo(url: string, assign: (url: string) => void = (u) => window.location.assign(u)): boolean {
  if (!isAllowedStripeUrl(url)) return false;
  assign(url);
  return true;
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 text-sm font-medium">{children}</dd>
    </div>
  );
}

function periodLines(sub: Subscription, now: Date) {
  const lines: { label: string; value: string }[] = [];
  const trialEnd = formatDate(sub.trialEndsAt);
  const periodEnd = formatDate(sub.currentPeriodEnd);
  if (sub.status === "TRIALING" && trialEnd) {
    const left = daysLeft(sub.trialEndsAt, now) ?? 0;
    lines.push({ label: "Trial ends", value: `${trialEnd} (${left} ${left === 1 ? "day" : "days"} left)` });
  }
  if (periodEnd) {
    const label = sub.cancelAtPeriodEnd
      ? "Cancels on"
      : sub.status === "ACTIVE" || sub.status === "PAST_DUE"
        ? "Renews on"
        : "Current period ends";
    lines.push({ label, value: periodEnd });
  }
  return lines;
}

export function BillingPanel() {
  const { subscription, loading, error, refetch } = useSubscription();
  const router = useRouter();
  const checkout = useSearchParams().get("checkout");
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const handledCheckout = useRef(false);

  // The webhook may land a moment after the redirect back: refetch once and refresh server data.
  useEffect(() => {
    if (checkout === "success" && !handledCheckout.current) {
      handledCheckout.current = true;
      void refetch();
      router.refresh();
    }
  }, [checkout, refetch, router]);

  async function start(action: "manage" | "subscribe") {
    setPending(true);
    setFailure(null);
    try {
      const url = await (action === "manage" ? createPortalSession() : createCheckoutSession());
      if (!redirectTo(url)) {
        setFailure("We received an unexpected billing address and did not follow it. Please try again.");
        setPending(false);
      }
      // On success the button stays disabled: the browser is navigating away.
    } catch (e) {
      setPending(false);
      if (e instanceof ApiError && e.status === 503) setFailure(NOT_CONFIGURED);
      else if (e instanceof ApiError && e.status === 403) setFailure("Only a workspace owner can manage billing.");
      else setFailure(e instanceof ApiError ? e.message : "We could not reach billing. Please try again.");
    }
  }

  const notice =
    checkout === "success" ? (
      <Alert role="status">
        <CheckCircle2 aria-hidden="true" />
        <AlertTitle>Payment received</AlertTitle>
        <AlertDescription>Your status updates within a minute.</AlertDescription>
      </Alert>
    ) : checkout === "canceled" ? (
      <Alert role="status">
        <Info aria-hidden="true" />
        <AlertTitle>Checkout canceled</AlertTitle>
        <AlertDescription>No charge was made. You can subscribe whenever you are ready.</AlertDescription>
      </Alert>
    ) : null;

  if (loading && !subscription) {
    return (
      <div aria-busy="true" aria-label="Loading billing" className="space-y-3">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-24 w-full max-w-xl" />
      </div>
    );
  }

  if (!subscription) {
    return (
      <div className="max-w-xl space-y-4">
        {notice}
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>We could not load billing</AlertTitle>
          <AlertDescription>{error ? "Something went wrong while loading your subscription." : "Subscription not available."}</AlertDescription>
        </Alert>
        <Button variant="outline" onClick={() => void refetch()}>
          Try again
        </Button>
      </div>
    );
  }

  const action = billingAction(subscription.status);
  const lines = periodLines(subscription, new Date());

  return (
    <div className="max-w-xl space-y-4">
      {notice}
      <Card>
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center justify-between gap-2">
            <span>Plan: {subscription.plan}</span>
            <BillingStatusBadge status={subscription.status} />
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {lines.length > 0 ? (
            <dl className="grid gap-3 sm:grid-cols-2">
              {lines.map((l) => (
                <Detail key={l.label} label={l.label}>
                  {l.value}
                </Detail>
              ))}
            </dl>
          ) : null}
          {subscription.cancelAtPeriodEnd ? (
            <p className="text-sm text-muted-foreground">Your subscription will not renew after the current period.</p>
          ) : null}
          {subscription.readOnly ? (
            <p className="text-sm text-muted-foreground">Your workspace is read-only until the subscription is active.</p>
          ) : null}

          {/* Cosmetic: the backend returns 403 to non-owners regardless. */}
          {subscription.canManage ? (
            <Button onClick={() => void start(action)} disabled={pending} aria-busy={pending}>
              {pending ? "Redirecting…" : action === "manage" ? "Manage billing" : "Subscribe"}
            </Button>
          ) : (
            <p className="text-sm text-muted-foreground">Ask an owner of this workspace to manage billing.</p>
          )}
          <p role="alert" className="text-sm text-destructive empty:hidden">
            {failure}
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
