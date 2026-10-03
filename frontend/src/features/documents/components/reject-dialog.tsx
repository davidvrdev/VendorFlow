"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { FieldShell } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { applyApiError } from "@/lib/forms/api-errors";
import { reviewDocument } from "../api";
import { rejectNoteSchema, type RejectNoteValues } from "../schemas";
/** Only what the dialog shows, so the dashboard can reject without loading the full document. */
export interface RejectTarget {
  id: string;
  documentType: { name: string };
  originalFilename?: string;
}

interface RejectDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  document: RejectTarget;
}

/** Rejecting requires a note (1-1000 chars) so the uploader knows what to fix. */
export function RejectDialog({ open, onOpenChange, document }: RejectDialogProps) {
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
        <RejectForm document={document} onPendingChange={setPending} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

function RejectForm({
  document,
  onPendingChange,
  onDone,
}: {
  document: RejectTarget;
  onPendingChange: (pending: boolean) => void;
  onDone: () => void;
}) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RejectNoteValues, unknown, { note: string }>({
    resolver: zodResolver(rejectNoteSchema),
    defaultValues: { note: "" },
  });

  async function onSubmit({ note }: { note: string }) {
    setFormError(null);
    onPendingChange(true);
    try {
      await reviewDocument(document.id, "REJECTED", note);
      toast.success("Document rejected.");
      onDone();
      router.refresh();
    } catch (error) {
      setFormError(
        applyApiError<RejectNoteValues>(error, setError, {
          fields: ["note"],
          statusMessages: {
            403: "You do not have permission to review documents.",
            409: "This document is no longer current, so it cannot be reviewed.",
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
        <DialogTitle>Reject document</DialogTitle>
        <DialogDescription>
          {document.originalFilename ? `${document.documentType.name} · ${document.originalFilename}` : document.documentType.name}
        </DialogDescription>
      </DialogHeader>
      <FormAlert message={formError} />
      <FieldShell id="reject-note" label="Reason for rejection" error={errors.note?.message} help="Required. Up to 1000 characters.">
        {(aria) => <Textarea {...aria} rows={4} required aria-required="true" {...register("note")} />}
      </FieldShell>
      <DialogFooter>
        <Button type="button" variant="outline" disabled={isSubmitting} onClick={onDone}>
          Cancel
        </Button>
        <SubmitButton pending={isSubmitting} pendingLabel="Rejecting…" variant="destructive">
          Reject document
        </SubmitButton>
      </DialogFooter>
    </form>
  );
}
