"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { changePassword } from "@/features/auth/api";
import { changePasswordSchema, PASSWORD_HELP, type ChangePasswordValues } from "@/features/auth/schemas";
import { applyApiError } from "@/lib/forms/api-errors";
import { PasswordField } from "./password-field";

const EMPTY = { currentPassword: "", newPassword: "", confirmPassword: "" };

export function ChangePasswordForm() {
  const [formError, setFormError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordValues>({ resolver: zodResolver(changePasswordSchema), defaultValues: EMPTY });

  async function onSubmit(values: ChangePasswordValues) {
    setFormError(null);
    setDone(false);
    try {
      await changePassword(values.currentPassword, values.newPassword);
    } catch (error) {
      setFormError(
        applyApiError<ChangePasswordValues>(error, setError, {
          fields: ["currentPassword", "newPassword"],
          statusMessages: {
            // Same generic wording whether the current password was wrong or the account is locked.
            401: "Your current password is incorrect.",
            429: "Too many attempts. Try again in a minute.",
          },
        }),
      );
      return;
    }
    reset(EMPTY);
    setDone(true);
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid max-w-md gap-4" aria-label="Change password">
      <p className="text-sm text-muted-foreground">
        Changing your password signs you out of all your other devices.
      </p>
      {done ? (
        <p role="status" className="flex items-start gap-2 text-sm">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          Your password has been changed. Other sessions were signed out.
        </p>
      ) : null}
      <FormAlert message={formError} />
      <PasswordField
        id="currentPassword"
        label="Current password"
        autoComplete="current-password"
        error={errors.currentPassword?.message}
        {...register("currentPassword")}
      />
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
      <SubmitButton pending={isSubmitting} pendingLabel="Changing password…">
        Change password
      </SubmitButton>
    </form>
  );
}
