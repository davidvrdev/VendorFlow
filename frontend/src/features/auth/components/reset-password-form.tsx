"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { TextField } from "@/components/forms/field";
import { SubmitButton } from "@/components/forms/submit-button";
import { buttonVariants } from "@/components/ui/button";
import { confirmPasswordReset } from "@/features/auth/api";
import { PASSWORD_HELP, resetPasswordSchema, type ResetPasswordValues } from "@/features/auth/schemas";
import { ApiError } from "@/lib/api/errors";
import { useHashToken } from "@/lib/auth/hash-token";
import { applyApiError } from "@/lib/forms/api-errors";
import { AuthCard } from "./auth-card";

const requestNewLink = (
  <Link href="/forgot-password" className="font-medium underline underline-offset-4">
    Request a new reset link
  </Link>
);

export function ResetPasswordForm() {
  const { token, ready } = useHashToken();
  const [done, setDone] = useState(false);
  const [linkInvalid, setLinkInvalid] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { newPassword: "", confirmPassword: "" },
  });

  async function onSubmit(values: ResetPasswordValues) {
    if (!token) return;
    setFormError(null);
    try {
      await confirmPasswordReset(token, values.newPassword);
      setDone(true);
    } catch (error) {
      // 400 with field errors = rejected password (e.g. too common); without them = bad/expired token.
      if (error instanceof ApiError && !error.errors?.length && [400, 404, 410].includes(error.status)) {
        setLinkInvalid(true);
        return;
      }
      setFormError(
        applyApiError<ResetPasswordValues>(error, setError, {
          fields: ["newPassword"],
          statusMessages: { 429: "Too many attempts. Try again in a minute." },
        }),
      );
    }
  }

  if (!ready) return <AuthCard title="Choose a new password" description="Loading…" />;

  if (done) {
    return (
      <AuthCard title="Password updated">
        <p role="status" className="flex items-start gap-2 text-sm">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          Your password has been changed. Sign in with your new password.
        </p>
        <Link href="/login" className={buttonVariants()}>
          Go to sign in
        </Link>
      </AuthCard>
    );
  }

  if (!token || linkInvalid) {
    return (
      <AuthCard title="Reset link not valid" footer={requestNewLink}>
        <p className="text-sm">This reset link is invalid or has expired.</p>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Choose a new password" description="You will be signed out everywhere else after changing it.">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
        <FormAlert message={formError} />
        <TextField
          id="newPassword"
          label="New password"
          type="password"
          autoComplete="new-password"
          help={PASSWORD_HELP}
          error={errors.newPassword?.message}
          {...register("newPassword")}
        />
        <TextField
          id="confirmPassword"
          label="Confirm new password"
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register("confirmPassword")}
        />
        <SubmitButton pending={isSubmitting} pendingLabel="Saving…">
          Change password
        </SubmitButton>
      </form>
    </AuthCard>
  );
}
