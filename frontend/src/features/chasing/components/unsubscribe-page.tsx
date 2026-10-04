"use client";

import { AlertCircle, CheckCircle2, Info, Loader2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import { errorMessage } from "@/lib/forms/api-errors";
import { confirmOptOut, fetchOptOutInfo } from "../api";
import type { OptOutInfo } from "../schemas";
import { isPlausibleOptOutToken, takeOptOutToken } from "../token";

export const UNSUBSCRIBE_INVALID_MESSAGE = "This link is invalid or has expired. If you still get reminders, use the link in the newest one.";
export const UNSUBSCRIBE_NO_TOKEN_MESSAGE = "Open the unsubscribe link from your email again. For your security this page does not remember the link.";

// The token lives in state (memory only) next to what it unlocked; see token.ts.
type State =
  | { kind: "loading" }
  | { kind: "no-token" }
  | { kind: "invalid" }
  | { kind: "error"; message: string; token: string }
  | { kind: "ready"; info: OptOutInfo; token: string }
  | { kind: "done"; info: OptOutInfo };

/**
 * Public unsubscribe page. Loading it NEVER changes anything (email scanners and prefetchers open links): the GET only
 * says who is asking, and the vendor must press the button to send the POST.
 */
export function UnsubscribePage() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [confirming, setConfirming] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  // Only the newest load may set state (a second link pasted while the first loads must win).
  const latest = useRef(0);

  const load = useCallback(async (token: string) => {
    const mine = ++latest.current;
    try {
      const info = await fetchOptOutInfo(token);
      if (mine === latest.current) setState({ kind: "ready", info, token });
    } catch (error) {
      if (mine !== latest.current) return;
      setState(
        error instanceof ApiError && error.status === 404
          ? { kind: "invalid" }
          : { kind: "error", token, message: errorMessage(error, { 429: "Too many requests. Please wait a minute and try again." }) },
      );
    }
  }, []);

  useEffect(() => {
    const start = async () => {
      const token = takeOptOutToken();
      if (token === null) return setState({ kind: "no-token" });
      if (!isPlausibleOptOutToken(token)) return setState({ kind: "invalid" });
      await load(token);
    };
    void start();
    // A new link pasted into an open tab only changes the fragment: take it again, strip it, load it.
    const onHashChange = () => {
      if (window.location.hash.length <= 1) return;
      setConfirmError(null);
      setState({ kind: "loading" });
      void start();
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, [load]);

  async function confirm(token: string) {
    setConfirming(true);
    setConfirmError(null);
    try {
      const info = await confirmOptOut(token);
      setState({ kind: "done", info });
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) setState({ kind: "invalid" });
      else setConfirmError(errorMessage(error, { 429: "Too many requests. Please wait a minute and try again." }));
    } finally {
      setConfirming(false);
    }
  }

  return (
    <main id="main" tabIndex={-1} className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-8 outline-none sm:py-12">
      <header className="grid gap-1">
        <p className="text-sm font-medium text-muted-foreground">VendorFlow</p>
        <h1 className="text-2xl font-semibold tracking-tight">Stop document reminders</h1>
      </header>

      {state.kind === "loading" ? (
        <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          Loading…
        </p>
      ) : null}

      {state.kind === "no-token" ? (
        <Alert>
          <Info aria-hidden="true" />
          <AlertTitle>Open your link again</AlertTitle>
          <AlertDescription>{UNSUBSCRIBE_NO_TOKEN_MESSAGE}</AlertDescription>
        </Alert>
      ) : null}

      {state.kind === "invalid" ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertTitle>Link not valid</AlertTitle>
          <AlertDescription>{UNSUBSCRIBE_INVALID_MESSAGE}</AlertDescription>
        </Alert>
      ) : null}

      {state.kind === "error" ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertTitle>We could not load this page</AlertTitle>
          <AlertDescription className="grid gap-3">
            <span>{state.message}</span>
            <span>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setState({ kind: "loading" });
                  void load(state.token);
                }}
              >
                Try again
              </Button>
            </span>
          </AlertDescription>
        </Alert>
      ) : null}

      {state.kind === "ready" && state.info.optedOut ? (
        <Alert>
          <CheckCircle2 aria-hidden="true" />
          <AlertTitle>Already unsubscribed</AlertTitle>
          <AlertDescription>
            {state.info.organizationName} will no longer send automatic document reminders to {state.info.vendorName}.
          </AlertDescription>
        </Alert>
      ) : null}

      {state.kind === "ready" && !state.info.optedOut ? (
        <section aria-labelledby="confirm-heading" className="grid gap-4 rounded-lg border bg-card p-4">
          <h2 id="confirm-heading" className="text-lg font-semibold">
            Unsubscribe {state.info.vendorName}?
          </h2>
          <p className="text-sm text-muted-foreground">
            {state.info.organizationName} uses VendorFlow to remind {state.info.vendorName} about missing or expiring documents. If you
            unsubscribe, these automatic reminders stop. The company can still contact you directly to request documents.
          </p>
          {confirmError ? (
            <p role="alert" className="text-sm text-destructive">
              {confirmError}
            </p>
          ) : null}
          <div>
            <Button disabled={confirming} aria-busy={confirming} onClick={() => void confirm(state.token)}>
              {confirming ? (
                <>
                  <Loader2 className="animate-spin" aria-hidden="true" data-icon="inline-start" />
                  Unsubscribing…
                </>
              ) : (
                "Unsubscribe from reminders"
              )}
            </Button>
          </div>
        </section>
      ) : null}

      {state.kind === "done" ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertTitle>You are unsubscribed</AlertTitle>
          <AlertDescription>
            {state.info.organizationName} will no longer send automatic document reminders to {state.info.vendorName}. You can close
            this page.
          </AlertDescription>
        </Alert>
      ) : null}
    </main>
  );
}
