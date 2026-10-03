"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { ApiError } from "@/lib/api/errors";
import { applyApiError } from "@/lib/forms/api-errors";
import { documentTypeFormSchema, type DocumentTypeFormValues } from "../schemas";

export const DUPLICATE_TYPE_MESSAGE = "A document type with this name already exists.";

interface DocumentTypeFormProps {
  /** Unique prefix for element ids (several forms can be on one page). */
  idPrefix: string;
  defaultValues?: DocumentTypeFormValues;
  submitLabel: string;
  pendingLabel: string;
  /** Persist the values; throw an ApiError to show it. Resolve to reset/close. */
  onSubmit: (values: DocumentTypeFormValues) => Promise<void>;
  onCancel?: () => void;
  /** Clear the form after a successful submit (create mode). */
  resetOnSuccess?: boolean;
}

/** Create and rename form for document types: name, "has expiration", "required by default". */
export function DocumentTypeForm({
  idPrefix,
  defaultValues = { name: "", hasExpiration: false, requiredByDefault: false },
  submitLabel,
  pendingLabel,
  onSubmit,
  onCancel,
  resetOnSuccess = false,
}: DocumentTypeFormProps) {
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<DocumentTypeFormValues>({ resolver: zodResolver(documentTypeFormSchema), defaultValues });

  async function submit(values: DocumentTypeFormValues) {
    setFormError(null);
    try {
      await onSubmit(values);
      if (resetOnSuccess) reset();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setError("name", { type: "server", message: DUPLICATE_TYPE_MESSAGE }, { shouldFocus: true });
        return;
      }
      setFormError(
        applyApiError<DocumentTypeFormValues>(error, setError, {
          fields: ["name", "hasExpiration", "requiredByDefault"],
          statusMessages: { 403: "You do not have permission to manage document types." },
        }),
      );
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="grid gap-4">
      <FormAlert message={formError} />
      <TextField
        id={`${idPrefix}-name`}
        label="Name"
        autoComplete="off"
        required
        aria-required="true"
        error={errors.name?.message}
        {...register("name")}
      />
      <div className="flex flex-wrap gap-x-6 gap-y-2">
        <Controller
          control={control}
          name="hasExpiration"
          render={({ field }) => (
            <div className="flex items-center gap-2">
              <Checkbox id={`${idPrefix}-has-expiration`} checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />
              <Label htmlFor={`${idPrefix}-has-expiration`} className="font-normal">
                Has an expiration date
              </Label>
            </div>
          )}
        />
        <Controller
          control={control}
          name="requiredByDefault"
          render={({ field }) => (
            <div className="flex items-center gap-2">
              <Checkbox id={`${idPrefix}-required`} checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />
              <Label htmlFor={`${idPrefix}-required`} className="font-normal">
                Required for new vendors by default
              </Label>
            </div>
          )}
        />
      </div>
      <div className="flex flex-wrap gap-2">
        <SubmitButton pending={isSubmitting} pendingLabel={pendingLabel} size="sm">
          {submitLabel}
        </SubmitButton>
        {onCancel ? (
          <Button type="button" variant="outline" size="sm" disabled={isSubmitting} onClick={onCancel}>
            Cancel
          </Button>
        ) : null}
      </div>
    </form>
  );
}
