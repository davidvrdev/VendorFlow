"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { TextField } from "@/components/forms/field";
import { SubmitButton } from "@/components/forms/submit-button";
import { login } from "@/features/auth/api";
import { loginSchema, type LoginValues } from "@/features/auth/schemas";
import { safeNextPath } from "@/lib/auth/safe-next";
import { applyApiError } from "@/lib/forms/api-errors";
import { AuthCard } from "./auth-card";

export function LoginForm({ next, passwordReset = false }: { next?: string | null; passwordReset?: boolean }) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: "", password: "" } });

  async function onSubmit(values: LoginValues) {
    setFormError(null);
    try {
      await login(values);
    } catch (error) {
      setFormError(
        applyApiError<LoginValues>(error, setError, {
          fields: ["email", "password"],
          // Backend returns an identical 401 for wrong email, wrong password and locked accounts.
          statusMessages: { 401: "Invalid email or password.", 429: "Too many attempts. Try again in a minute." },
        }),
      );
      return;
    }
    // Re-validate here as well: `next` originates from the URL and must never become an open redirect.
    router.replace(safeNextPath(next) ?? "/dashboard");
    router.refresh();
  }

  return (
    <AuthCard
      title="Sign in"
      description="Welcome back. Sign in to see which vendors need attention."
      footer={
        <>
          <Link href="/forgot-password" className="underline underline-offset-4">
            Forgot your password?
          </Link>
          <span>
            New to VendorFlow?{" "}
            <Link href="/signup" className="font-medium text-foreground underline underline-offset-4">
              Create an account
            </Link>
          </span>
        </>
      }
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
        {passwordReset ? (
          <p role="status" className="flex items-start gap-2 text-sm">
            <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            Your password has been changed. Sign in with your new password.
          </p>
        ) : null}
        <FormAlert message={formError} />
        <TextField
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register("email")}
        />
        <TextField
          id="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register("password")}
        />
        <SubmitButton pending={isSubmitting} pendingLabel="Signing in…">
          Sign in
        </SubmitButton>
      </form>
    </AuthCard>
  );
}
