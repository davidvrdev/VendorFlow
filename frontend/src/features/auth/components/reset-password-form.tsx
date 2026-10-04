"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { confirmPasswordReset } from "@/features/auth/api";
import { PASSWORD_HELP, resetPasswordSchema, type ResetPasswordValues } from "@/features/auth/schemas";
import { ApiError } from "@/lib/api/errors";
import { useHashToken } from "@/lib/auth/hash-token";
import { applyApiError } from "@/lib/forms/api-errors";
import { AuthCard } from "./auth-card";
import { PasswordField } from "./password-field";

const requestNewLink = (
  <Link href="/forgot-password" className="font-medium underline underline-offset-4">
    Request a new reset link
  </Link>
);

export function ResetPasswordForm() {
  const { token, ready } = useHashToken();
  const router = useRouter();
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
      // The backend revoked every session of this user, so the next step is a fresh sign-in.
      router.replace("/login?reset=1");
      return;
    } catch (error) {
      // 400 with field errors = rejected password (token NOT consumed); 400 without = bad/expired link.
      if (error instanceof ApiError && error.status === 400 && !error.errors?.length) {
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
        <PasswordField
          id="newPassword"
          label="New password"
          autoComplete="new-password"
          help={PASSWORD_HELP}
          error={errors.newPassword?.message}
          {...register("newPassword")}
        />
        <PasswordField
          id="confirmPassword"
          label="Confirm new password"
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
