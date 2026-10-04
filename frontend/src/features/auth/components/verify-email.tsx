"use client";

import { CheckCircle2, Loader2, XCircle } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";
import { buttonVariants } from "@/components/ui/button";
import { verifyEmail } from "@/features/auth/api";
import { ApiError } from "@/lib/api/errors";
import { useHashToken } from "@/lib/auth/hash-token";
import { NETWORK_ERROR_MESSAGE } from "@/lib/forms/api-errors";
import { AuthCard } from "./auth-card";

type Outcome = { kind: "success" } | { kind: "failed"; message: string };

export function VerifyEmail() {
  const router = useRouter();
  const { token, ready } = useHashToken();
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  // Verification tokens are single-use: StrictMode's double effect must not POST twice.
  const submitted = useRef(false);
  // Next's router keeps the URL it first loaded (fragment included) as its canonical URL and writes it back to the
  // address bar when a refresh/navigation settles. On a cold server that write-back lands AFTER our replaceState, so the
  // token reappeared. Strip it again once the refresh transition has finished (observable via isPending).
  const [refreshing, startRefresh] = useTransition();
  const refreshStarted = useRef(false);
  useEffect(() => {
    if (refreshStarted.current && !refreshing) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
  }, [refreshing]);

  useEffect(() => {
    if (!ready || !token || submitted.current) return;
    submitted.current = true;
    verifyEmail(token).then(
      () => {
        setOutcome({ kind: "success" });
        // If the user is signed in, re-read /me so the unverified banner disappears. Next restores the
        // URL it last navigated to on refresh (fragment included), so first make the stripped URL canonical.
        router.replace("/verify-email");
        refreshStarted.current = true;
        startRefresh(() => router.refresh());
      },
      (error: unknown) =>
        setOutcome({
          kind: "failed",
          message:
            error instanceof ApiError && error.status === 400
              ? "This verification link is invalid or has expired."
              : error instanceof ApiError
                ? (error.detail ?? error.title)
                : NETWORK_ERROR_MESSAGE,
        }),
    );
  }, [ready, token, router, startRefresh]);

  if (!ready) return <AuthCard title="Verifying your email" description="Loading…" />;

  if (!token) {
    return (
      <AuthCard title="Verification link not valid" footer={<Link href="/login" className="underline underline-offset-4">Back to sign in</Link>}>
        <p className="text-sm">This verification link is invalid or has expired. Sign in and choose &quot;Resend email&quot; to get a new one.</p>
      </AuthCard>
    );
  }

  if (!outcome) {
    return (
      <AuthCard title="Verifying your email">
        <p role="status" className="flex items-center gap-2 text-sm">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          Verifying…
        </p>
      </AuthCard>
    );
  }

  if (outcome.kind === "success") {
    return (
      <AuthCard title="Email verified">
        <p role="status" className="flex items-start gap-2 text-sm">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          Thanks. Your email address is verified.
        </p>
        <Link href="/dashboard" className={buttonVariants()}>
          Continue to VendorFlow
        </Link>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Could not verify email" footer={<Link href="/login" className="underline underline-offset-4">Back to sign in</Link>}>
      <p role="alert" className="flex items-start gap-2 text-sm text-destructive">
        <XCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        {outcome.message}
      </p>
      <p className="text-sm text-muted-foreground">Sign in and choose &quot;Resend email&quot; to get a new link.</p>
    </AuthCard>
  );
}
