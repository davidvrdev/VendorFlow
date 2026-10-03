"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { TextField } from "@/components/forms/field";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button, buttonVariants } from "@/components/ui/button";
import { fetchMe, logout } from "@/features/auth/api";
import { AuthCard } from "@/features/auth/components/auth-card";
import { inviteAccountSchema, PASSWORD_HELP, type InviteAccountValues } from "@/features/auth/schemas";
import { ApiError } from "@/lib/api/errors";
import { useHashToken } from "@/lib/auth/hash-token";
import { applyApiError, errorMessage } from "@/lib/forms/api-errors";
import { acceptInvitation, lookupInvitation } from "../api";
import {
  clearStashedInviteToken,
  readStashedInviteToken,
  selectInviteState,
  stashInviteToken,
  type LookupResult,
  type SessionResult,
} from "../invite-state";
import { ROLE_LABELS } from "../permissions";
import type { InvitationLookup } from "../types";

function InvitationSummary({ invitation }: { invitation: InvitationLookup }) {
  return (
    <p className="text-sm">
      Join <strong>{invitation.organizationName}</strong> as <strong>{ROLE_LABELS[invitation.role]}</strong>. This invitation
      is for {invitation.email}.
    </p>
  );
}

export function InviteFlow() {
  const router = useRouter();
  const { token: hashToken, ready } = useHashToken();
  const [token, setToken] = useState<string | null>(null);
  const [resolved, setResolved] = useState(false);
  const [lookup, setLookup] = useState<LookupResult>({ status: "pending" });
  const [session, setSession] = useState<SessionResult>({ status: "pending" });
  const started = useRef(false);

  // Resolve the token (fragment first, else the one stashed before a sign-in detour), then look it
  // up and ask /me in parallel. Lookup is idempotent, but the guard avoids StrictMode double calls.
  useEffect(() => {
    if (!ready || started.current) return;
    started.current = true;
    const found = hashToken ?? readStashedInviteToken();
    setToken(found);
    setResolved(true);
    if (!found) return;

    lookupInvitation(found).then(
      (data) => setLookup({ status: "ok", data }),
      (error: unknown) => {
        if (error instanceof ApiError && error.status === 404) {
          clearStashedInviteToken();
          setLookup({ status: "invalid" });
        } else {
          setLookup({ status: "failed", message: errorMessage(error, { 429: "Too many attempts. Try again in a minute." }) });
        }
      },
    );
    fetchMe().then(
      (me) => setSession({ status: "signed-in", email: me.user.email }),
      // 401 = not signed in. Any other failure also degrades to "signed out"; accept then fails safely server-side.
      () => setSession({ status: "signed-out" }),
    );
  }, [ready, hashToken]);

  const view = selectInviteState({ tokenReady: resolved, token, lookup, session });

  function done() {
    clearStashedInviteToken();
    router.replace("/dashboard");
    router.refresh();
  }

  switch (view.kind) {
    case "loading":
      return (
        <AuthCard title="Invitation">
          <p role="status" className="text-sm text-muted-foreground">
            Checking your invitation…
          </p>
        </AuthCard>
      );
    case "invalid":
      return (
        <AuthCard title="Invitation not valid" footer={<Link href="/login" className="underline underline-offset-4">Back to sign in</Link>}>
          <p className="text-sm">This invitation is invalid or has expired.</p>
        </AuthCard>
      );
    case "failed":
      return (
        <AuthCard title="Invitation unavailable">
          <p role="alert" className="text-sm text-destructive">
            {view.message}
          </p>
        </AuthCard>
      );
    case "accept":
      return (
        <AuthCard title="You are invited">
          <InvitationSummary invitation={view.invitation} />
          <AcceptButton token={token!} onDone={done} />
        </AuthCard>
      );
    case "wrong-account":
      return (
        <AuthCard title="You are invited">
          <InvitationSummary invitation={view.invitation} />
          <p role="alert" className="text-sm text-destructive">
            You are signed in as {view.signedInAs}. Sign out and sign in with {view.invitation.email} to accept.
          </p>
          <Button
            variant="outline"
            onClick={async () => {
              await logout().catch(() => undefined);
              setSession({ status: "signed-out" });
            }}
          >
            Sign out
          </Button>
        </AuthCard>
      );
    case "sign-in-required":
      return (
        <AuthCard title="You are invited">
          <InvitationSummary invitation={view.invitation} />
          {/* The token survives the login detour in sessionStorage; see invite-state.ts for the risk. */}
          <Link
            href="/login?next=/invite"
            className={buttonVariants()}
            onClick={() => {
              if (token) stashInviteToken(token);
            }}
          >
            Sign in to accept
          </Link>
        </AuthCard>
      );
    case "create-account":
      return (
        <AuthCard title="You are invited">
          <InvitationSummary invitation={view.invitation} />
          <CreateAccountForm token={token!} onDone={done} />
        </AuthCard>
      );
  }
}

function AcceptButton({ token, onDone }: { token: string; onDone: () => void }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function accept() {
    setPending(true);
    setError(null);
    try {
      await acceptInvitation({ token });
      onDone();
    } catch (e) {
      setError(
        errorMessage(e, {
          403: "This invitation was sent to a different email address.",
          404: "This invitation is invalid or has expired.",
          429: "Too many attempts. Try again in a minute.",
        }),
      );
      setPending(false);
    }
  }

  return (
    <div className="grid gap-3">
      <FormAlert message={error} />
      <Button onClick={accept} disabled={pending} aria-busy={pending}>
        {pending ? "Joining…" : "Accept invitation"}
      </Button>
    </div>
  );
}

function CreateAccountForm({ token, onDone }: { token: string; onDone: () => void }) {
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<InviteAccountValues>({ resolver: zodResolver(inviteAccountSchema), defaultValues: { fullName: "", password: "" } });

  async function onSubmit(values: InviteAccountValues) {
    setFormError(null);
    try {
      await acceptInvitation({ token, ...values });
      onDone();
    } catch (error) {
      setFormError(
        applyApiError<InviteAccountValues>(error, setError, {
          fields: ["fullName", "password"],
          statusMessages: {
            404: "This invitation is invalid or has expired.",
            409: "An account with this email already exists. Reload this page and sign in to accept.",
            429: "Too many attempts. Try again in a minute.",
          },
        }),
      );
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
      <p className="text-sm text-muted-foreground">Create your account to accept.</p>
      <FormAlert message={formError} />
      <TextField id="fullName" label="Full name" autoComplete="name" error={errors.fullName?.message} {...register("fullName")} />
      <TextField
        id="password"
        label="Password"
        type="password"
        autoComplete="new-password"
        help={PASSWORD_HELP}
        error={errors.password?.message}
        {...register("password")}
      />
      <SubmitButton pending={isSubmitting} pendingLabel="Creating account…">
        Create account and join
      </SubmitButton>
    </form>
  );
}
