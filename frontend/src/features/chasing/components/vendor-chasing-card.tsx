"use client";

import { Loader2, Pause, Play } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import { ApiError } from "@/lib/api/errors";
import { formatDateTime } from "@/lib/format";
import { errorMessage } from "@/lib/forms/api-errors";
import { getVendorChasing, setVendorChasingPaused } from "../api";
import type { VendorChasingState } from "../schemas";
import { chasingStatusHint } from "../view";
import { ChaseHistory } from "./chase-history";
import { ChasingStatusBadge } from "./chasing-status-badge";

interface VendorChasingCardProps {
  vendorId: string;
  role: Role;
  /** Null when the settings could not be read: the hint is then simply omitted. */
  organizationEnabled: boolean | null;
}

type Loaded = { key: number; state: VendorChasingState } | { key: number; error: string };

/** "Automatic follow-ups" section of the vendor page. State is loaded in the browser; Pause/Resume write then refetch. */
export function VendorChasingCard({ vendorId, role, organizationEnabled }: VendorChasingCardProps) {
  const canWrite = can(role, "VENDORS_WRITE"); // cosmetic; the backend enforces CONTENT_WRITE
  const canConfigure = can(role, "ORG_SETTINGS_MANAGE");
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<Loaded | null>(null);
  const [pending, setPending] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const loading = result?.key !== reload;

  useEffect(() => {
    let cancelled = false;
    getVendorChasing(vendorId)
      .then((state) => !cancelled && setResult({ key: reload, state }))
      .catch((error: unknown) => !cancelled && setResult({ key: reload, error: errorMessage(error) }));
    return () => {
      cancelled = true;
    };
  }, [vendorId, reload]);

  const state = result && "state" in result && result.key === reload ? result.state : null;
  const error = result && "error" in result && result.key === reload ? result.error : null;

  const toggle = useCallback(
    async (paused: boolean) => {
      setPending(true);
      setActionError(null);
      try {
        await setVendorChasingPaused(vendorId, paused);
        toast.success(paused ? "Automatic follow-ups paused." : "Automatic follow-ups resumed.");
        setReload((value) => value + 1);
      } catch (e) {
        if (e instanceof ApiError && e.status === 422 && e.code === "vendor-opted-out") {
          setActionError("This vendor unsubscribed from reminders. Automatic follow-ups cannot be turned back on.");
          setReload((value) => value + 1); // the card was stale: show the real state
        } else {
          setActionError(
            errorMessage(e, {
              402: "Your subscription is inactive. Subscribe to make changes.",
              403: "You do not have permission to change follow-ups.",
            }),
          );
        }
      } finally {
        setPending(false);
      }
    },
    [vendorId],
  );

  const optedOut = state?.pausedReason === "OPT_OUT";
  const version = state ? `${state.status}:${state.attempts}:${state.lastChasedAt ?? ""}` : "none";

  return (
    <Card id="follow-ups">
      <CardHeader>
        <CardTitle>
          <h2>Automatic follow-ups</h2>
        </CardTitle>
        {canWrite && state ? (
          <CardAction>
            {state.paused ? (
              <Button variant="outline" disabled={pending || optedOut} onClick={() => void toggle(false)}>
                {pending ? <Loader2 className="animate-spin" aria-hidden="true" data-icon="inline-start" /> : <Play aria-hidden="true" data-icon="inline-start" />}
                {pending ? "Resuming…" : "Resume follow-ups"}
              </Button>
            ) : (
              <Button variant="outline" disabled={pending} onClick={() => void toggle(true)}>
                {pending ? <Loader2 className="animate-spin" aria-hidden="true" data-icon="inline-start" /> : <Pause aria-hidden="true" data-icon="inline-start" />}
                {pending ? "Pausing…" : "Pause follow-ups"}
              </Button>
            )}
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent className="grid gap-5">
        {organizationEnabled === false ? (
          <p className="rounded-md border bg-muted px-3 py-2 text-sm text-muted-foreground">
            Automatic follow-ups are turned off for your organization, so nothing is sent.{" "}
            {canConfigure ? (
              <Link href="/settings/chasing" className="font-medium text-foreground underline">
                Turn them on in settings
              </Link>
            ) : (
              "Ask an owner or admin to turn them on."
            )}
          </p>
        ) : null}

        {loading ? (
          <div role="status" aria-label="Loading follow-up status" className="grid gap-2">
            <Skeleton className="h-6 w-40" />
            <Skeleton className="h-4 w-64" />
          </div>
        ) : error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}{" "}
            <button type="button" className="underline" onClick={() => setReload((value) => value + 1)}>
              Try again
            </button>
          </p>
        ) : state ? (
          <div className="grid gap-2 text-sm">
            <p className="flex flex-wrap items-center gap-2">
              <ChasingStatusBadge state={state} />
              <span className="text-muted-foreground">{chasingStatusHint(state)}</span>
            </p>
            <dl className="grid gap-1 sm:grid-cols-[max-content_1fr] sm:gap-x-4">
              <dt className="text-muted-foreground">Follow-ups this round</dt>
              <dd>
                {state.attempts} of {state.maxAttempts}
              </dd>
              <dt className="text-muted-foreground">Last follow-up</dt>
              <dd>{state.lastChasedAt ? formatDateTime(state.lastChasedAt) : "None yet"}</dd>
              <dt className="text-muted-foreground">Next follow-up</dt>
              <dd>{state.nextChaseAt ? formatDateTime(state.nextChaseAt) : "Not scheduled"}</dd>
            </dl>
          </div>
        ) : null}

        {actionError ? (
          <p role="alert" className="text-sm text-destructive">
            {actionError}
          </p>
        ) : null}

        {/* After the state settled, so history loads once with the state it belongs to (and again after Pause/Resume). */}
        {loading ? null : <ChaseHistory vendorId={vendorId} version={version} />}
      </CardContent>
    </Card>
  );
}
