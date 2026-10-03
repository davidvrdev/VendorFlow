"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { TextField } from "@/components/forms/field";
import { SubmitButton } from "@/components/forms/submit-button";
import { signup } from "@/features/auth/api";
import { PASSWORD_HELP, signupSchema, type SignupValues } from "@/features/auth/schemas";
import { ApiError } from "@/lib/api/errors";
import { applyApiError } from "@/lib/forms/api-errors";
import { AuthCard } from "./auth-card";

const FIELDS = ["fullName", "email", "password", "organizationName"] as const;

export function SignupForm() {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SignupValues>({
    resolver: zodResolver(signupSchema),
    defaultValues: { fullName: "", email: "", password: "", organizationName: "" },
  });

  async function onSubmit(values: SignupValues) {
    setFormError(null);
    try {
      await signup(values);
    } catch (error) {
      // The 409 body carries no field list in the contract, so attach it to the email field ourselves.
      if (error instanceof ApiError && error.status === 409) {
        setError("email", { type: "server", message: "An account with this email already exists." });
        return;
      }
      setFormError(
        applyApiError<SignupValues>(error, setError, {
          fields: FIELDS,
          statusMessages: { 429: "Too many attempts. Try again in a minute." },
        }),
      );
      return;
    }
    router.replace("/dashboard");
    router.refresh();
  }

  return (
    <AuthCard
      title="Create your account"
      description="Start tracking vendor compliance. You will be the owner of the new organization."
      footer={
        <span>
          Already have an account?{" "}
          <Link href="/login" className="font-medium text-foreground underline underline-offset-4">
            Sign in
          </Link>
        </span>
      }
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
        <FormAlert message={formError} />
        <TextField id="fullName" label="Full name" autoComplete="name" error={errors.fullName?.message} {...register("fullName")} />
        <TextField id="email" label="Work email" type="email" autoComplete="email" error={errors.email?.message} {...register("email")} />
        <TextField
          id="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          help={PASSWORD_HELP}
          error={errors.password?.message}
          {...register("password")}
        />
        <TextField
          id="organizationName"
          label="Organization name"
          autoComplete="organization"
          help="Your company or the properties you manage."
          error={errors.organizationName?.message}
          {...register("organizationName")}
        />
        <SubmitButton pending={isSubmitting} pendingLabel="Creating account…">
          Create account
        </SubmitButton>
      </form>
    </AuthCard>
  );
}
