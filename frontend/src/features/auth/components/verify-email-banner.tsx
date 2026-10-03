"use client";

import { MailWarning } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { resendVerification } from "@/features/auth/api";
import { errorMessage } from "@/lib/forms/api-errors";

type Status = { kind: "idle" } | { kind: "pending" } | { kind: "sent" } | { kind: "error"; message: string };

/** Shown while the user's email is unverified. Deliberately not dismissable (invites/reminders depend on it). */
export function VerifyEmailBanner() {
  const [status, setStatus] = useState<Status>({ kind: "idle" });

  async function resend() {
    setStatus({ kind: "pending" });
    try {
      await resendVerification();
      setStatus({ kind: "sent" });
    } catch (error) {
      setStatus({
        kind: "error",
        message: errorMessage(error, { 429: "Too many requests. Try again in a minute." }),
      });
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b bg-muted px-4 py-2 text-sm sm:px-8" data-testid="verify-banner">
      <MailWarning className="size-4 shrink-0" aria-hidden="true" />
      <p className="flex-1">Verify your email to invite teammates and receive reminders.</p>
      <Button size="sm" variant="outline" onClick={resend} disabled={status.kind === "pending"} aria-busy={status.kind === "pending"}>
        {status.kind === "pending" ? "Sending…" : "Resend email"}
      </Button>
      <p role="status" className="basis-full text-xs sm:basis-auto">
        {status.kind === "sent" ? "Verification email sent. Check your inbox." : null}
        {status.kind === "error" ? <span className="text-destructive">{status.message}</span> : null}
      </p>
    </div>
  );
}
