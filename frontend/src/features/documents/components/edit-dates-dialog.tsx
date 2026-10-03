"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { applyApiError } from "@/lib/forms/api-errors";
import { updateDocumentDates } from "../api";
import { buildDatesSchema, MAX_DATE, MIN_DATE, type DatesFormOutput, type DatesFormValues } from "../schemas";
import type { DocumentSummary } from "../types";

interface EditDatesDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  document: DocumentSummary;
}

export function EditDatesDialog({ open, onOpenChange, document }: EditDatesDialogProps) {
  const [pending, setPending] = useState(false);
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (pending) return;
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <DatesForm document={document} onPendingChange={setPending} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

function DatesForm({
  document,
  onPendingChange,
  onDone,
}: {
  document: DocumentSummary;
  onPendingChange: (pending: boolean) => void;
  onDone: () => void;
}) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const requiresExpiration = document.documentType.hasExpiration;
  const schema = useMemo(() => buildDatesSchema(requiresExpiration), [requiresExpiration]);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<DatesFormValues, unknown, DatesFormOutput>({
    resolver: zodResolver(schema),
    defaultValues: { issueDate: document.issueDate ?? "", expirationDate: document.expirationDate ?? "" },
  });

  async function onSubmit(values: DatesFormOutput) {
    setFormError(null);
    onPendingChange(true);
    try {
      // Explicit null clears the issue date; the expiration date is only ever null for types without expiration.
      await updateDocumentDates(document.id, values);
      toast.success("Document dates updated.");
      onDone();
      router.refresh();
    } catch (error) {
      setFormError(
        applyApiError<DatesFormValues>(error, setError, {
          fields: ["issueDate", "expirationDate"],
          statusMessages: {
            403: "You do not have permission to edit documents.",
            409: "This document is no longer current, so its dates cannot be changed.",
          },
        }),
      );
    } finally {
      onPendingChange(false);
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Edit dates</DialogTitle>
        <DialogDescription>
          {document.documentType.name} · {document.originalFilename}. Changing dates does not change the review status.
        </DialogDescription>
      </DialogHeader>
      <FormAlert message={formError} />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          id="dates-issue"
          label="Issue date"
          type="date"
          min={MIN_DATE}
          max={MAX_DATE}
          error={errors.issueDate?.message}
          {...register("issueDate")}
        />
        <TextField
          id="dates-expiration"
          label="Expiration date"
          type="date"
          min={MIN_DATE}
          max={MAX_DATE}
          required={requiresExpiration}
          aria-required={requiresExpiration ? "true" : undefined}
          help={requiresExpiration ? "Required for this document type." : undefined}
          error={errors.expirationDate?.message}
          {...register("expirationDate")}
        />
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" disabled={isSubmitting} onClick={onDone}>
          Cancel
        </Button>
        <SubmitButton pending={isSubmitting} pendingLabel="Saving…">
          Save dates
        </SubmitButton>
      </DialogFooter>
    </form>
  );
}
