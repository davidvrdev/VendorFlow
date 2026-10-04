"use client";

import { AlertCircle, Info, Loader2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { formatDate } from "@/lib/format";
import { errorMessage } from "@/lib/forms/api-errors";
import { fetchPortalInfo } from "../api";
import { classifyPortalError, INVALID_LINK_MESSAGE, NO_TOKEN_MESSAGE, NOT_ACCEPTING_MESSAGE } from "../errors";
import type { PortalInfo } from "../schemas";
import { isPlausibleToken, takePortalToken } from "../token";
import { PortalDocumentCard } from "./portal-document-card";

// The token is kept in state (memory only) next to what it unlocked; see token.ts.
type State =
  | { kind: "loading" }
  | { kind: "no-token" }
  | { kind: "invalid" }
  | { kind: "error"; message: string; token: string }
  | { kind: "ready"; info: PortalInfo; token: string };

/**
 * Public vendor portal. No app shell, no session. On mount the token is taken out of the URL fragment into memory and
 * the address bar is cleaned before anything else happens (see token.ts).
 */
export function PortalPage() {
  const [state, setState] = useState<State>({ kind: "loading" });

  // Only the newest load may set state: pasting a second link while the first is still loading must not be overwritten.
  const latest = useRef(0);

  const load = useCallback(async (token: string) => {
    const mine = ++latest.current;
    try {
      const info = await fetchPortalInfo(token);
      if (mine === latest.current) setState({ kind: "ready", info, token });
    } catch (error) {
      if (mine !== latest.current) return;
      const failure = classifyPortalError(error);
      setState(
        failure === "invalid"
          ? { kind: "invalid" }
          : { kind: "error", token, message: errorMessage(error, { 429: "Too many requests. Please wait a minute and try again." }) },
      );
    }
  }, []);

  useEffect(() => {
    // Async on purpose: reading the URL fragment is an external-system sync, and state is only set from the callback.
    const start = async () => {
      const token = takePortalToken();
      if (token === null) return setState({ kind: "no-token" });
      if (!isPlausibleToken(token)) return setState({ kind: "invalid" });
      await load(token);
    };
    void start();
    // Pasting a new link into an already open /portal tab only changes the fragment (no reload): take it again,
    // strip it and load the new link. (replaceState does not fire hashchange, so stripping cannot loop.)
    const onHashChange = () => {
      if (window.location.hash.length <= 1) return;
      setState({ kind: "loading" });
      void start();
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, [load]);

  const info = state.kind === "ready" ? state.info : null;
  const readyToken = state.kind === "ready" ? state.token : "";

  return (
    <main id="main" tabIndex={-1} className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-8 outline-none sm:py-12">
      <header className="grid gap-1">
        <p className="text-sm font-medium text-muted-foreground">VendorFlow</p>
        <h1 className="text-2xl font-semibold tracking-tight">{info ? `Documents for ${info.organizationName}` : "Upload documents"}</h1>
      </header>

      {state.kind === "loading" ? (
        <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          Loading your upload link…
        </p>
      ) : null}

      {state.kind === "no-token" ? <Notice title="Open your link again">{NO_TOKEN_MESSAGE}</Notice> : null}

      {state.kind === "invalid" ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertTitle>Link not valid</AlertTitle>
          <AlertDescription>{INVALID_LINK_MESSAGE}</AlertDescription>
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

      {info ? (
        <>
          <section aria-label="About this link" className="grid gap-1 rounded-lg border bg-card p-4 text-sm">
            <p>
              <span className="text-muted-foreground">Vendor:</span> <span className="font-medium">{info.vendorName}</span>
            </p>
            <p>
              <span className="text-muted-foreground">Link expires:</span> {formatDate(info.expiresAt)}
            </p>
            <p>
              <span className="text-muted-foreground">Uploads remaining:</span> {info.remainingUploads}
            </p>
          </section>

          {!info.acceptingUploads ? <Notice title="Uploads are closed">{NOT_ACCEPTING_MESSAGE}</Notice> : null}

          {info.documentTypes.length === 0 ? (
            <p className="text-sm text-muted-foreground">No documents are requested through this link.</p>
          ) : (
            <section aria-labelledby="requested-heading" className="grid gap-4">
              <h2 id="requested-heading" className="text-lg font-semibold">
                Requested documents
              </h2>
              <ul className="grid gap-4">
                {info.documentTypes.map((type) => (
                  <li key={type.id}>
                    <PortalDocumentCard
                      type={type}
                      acceptingUploads={info.acceptingUploads}
                      token={readyToken}
                      onUploaded={() => void load(readyToken)}
                      onInvalid={() => setState({ kind: "invalid" })}
                      onClosed={() => void load(readyToken)}
                    />
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      ) : null}
    </main>
  );
}

function Notice({ title, children }: { title: string; children: string }) {
  return (
    <Alert>
      <Info aria-hidden="true" />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  );
}
