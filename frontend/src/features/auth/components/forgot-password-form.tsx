"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { MailCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { TextField } from "@/components/forms/field";
import { SubmitButton } from "@/components/forms/submit-button";
import { requestPasswordReset } from "@/features/auth/api";
import { forgotPasswordSchema, type ForgotPasswordValues } from "@/features/auth/schemas";
import { applyApiError } from "@/lib/forms/api-errors";
import { AuthCard } from "./auth-card";

export function ForgotPasswordForm() {
  const [sent, setSent] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordValues>({ resolver: zodResolver(forgotPasswordSchema), defaultValues: { email: "" } });

  async function onSubmit(values: ForgotPasswordValues) {
    setFormError(null);
    try {
      await requestPasswordReset(values.email);
      setSent(true);
    } catch (error) {
      setFormError(
        applyApiError<ForgotPasswordValues>(error, setError, {
          fields: ["email"],
          statusMessages: { 429: "Too many attempts. Try again in a minute." },
        }),
      );
    }
  }

  const footer = (
    <Link href="/login" className="underline underline-offset-4">
      Back to sign in
    </Link>
  );

  if (sent) {
    return (
      <AuthCard title="Check your email" footer={footer}>
        <p role="status" className="flex items-start gap-2 text-sm">
          <MailCheck className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          If an account exists, we&apos;ve emailed a reset link.
        </p>
      </AuthCard>
    );
  }

  return (
    <AuthCard
      title="Reset your password"
      description="Enter your email and we will send you a link to choose a new password."
      footer={footer}
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
        <FormAlert message={formError} />
        <TextField id="email" label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register("email")} />
        <SubmitButton pending={isSubmitting} pendingLabel="Sending…">
          Send reset link
        </SubmitButton>
      </form>
    </AuthCard>
  );
}
