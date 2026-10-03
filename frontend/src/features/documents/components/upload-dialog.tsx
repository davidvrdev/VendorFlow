"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { TriangleAlert } from "lucide-react";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";
import { FieldShell, TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { applyApiError } from "@/lib/forms/api-errors";
import { formatFileSize, truncateFilename } from "@/lib/format";
import { uploadDocument } from "../api";
import { UPLOAD_FIELDS, UPLOAD_STATUS_MESSAGES } from "../errors";
import {
  buildUploadSchema,
  FILE_ACCEPT,
  MAX_DATE,
  MIN_DATE,
  validateFile,
  type UploadFormOutput,
  type UploadFormValues,
} from "../schemas";
import type { DocumentSummary, DocumentType } from "../types";

interface UploadDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  vendorId: string;
  companyName: string;
  /** Active types only (inactive ones cannot receive uploads). */
  documentTypes: DocumentType[];
  /** Preselected when opened from a row. */
  initialTypeId?: string;
  /** CURRENT documents by type id, to warn that an upload replaces one. */
  currentByType: Record<string, DocumentSummary>;
}

/** Controlled dialog. The form is mounted only while open, so every opening starts clean. */
export function UploadDialog({ open, onOpenChange, ...formProps }: UploadDialogProps) {
  const [pending, setPending] = useState(false);
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (pending) return; // do not close mid-upload
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <UploadForm {...formProps} onPendingChange={setPending} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

type UploadFormProps = Omit<UploadDialogProps, "open" | "onOpenChange"> & {
  onPendingChange: (pending: boolean) => void;
  onDone: () => void;
};

function UploadForm({ vendorId, companyName, documentTypes, initialTypeId, currentByType, onPendingChange, onDone }: UploadFormProps) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const schema = useMemo(() => buildUploadSchema(documentTypes), [documentTypes]);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    clearErrors,
    control,
    formState: { errors, isSubmitting },
  } = useForm<UploadFormValues, unknown, UploadFormOutput>({
    resolver: zodResolver(schema),
    defaultValues: { documentTypeId: initialTypeId ?? "", file: null, issueDate: "", expirationDate: "" },
  });

  const typeId = useWatch({ control, name: "documentTypeId" });
  const file = useWatch({ control, name: "file" });
  const selectedType = documentTypes.find((type) => type.id === typeId);
  const replacing = typeId ? currentByType[typeId] : undefined;

  function onFileChange(selected: File | null) {
    setValue("file", selected);
    // Early UX feedback only; submit re-validates and the server is authoritative.
    const problem = selected ? validateFile(selected) : null;
    if (problem) setError("file", { type: "client", message: problem });
    else clearErrors("file");
  }

  async function onSubmit(values: UploadFormOutput) {
    setFormError(null);
    onPendingChange(true);
    try {
      await uploadDocument(vendorId, values);
      toast.success(replacing ? "Document replaced." : "Document uploaded.");
      onDone();
      router.refresh();
    } catch (error) {
      setFormError(
        applyApiError<UploadFormValues>(error, setError, {
          fields: UPLOAD_FIELDS,
          statusMessages: UPLOAD_STATUS_MESSAGES,
        }),
      );
    } finally {
      onPendingChange(false);
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Upload document</DialogTitle>
        <DialogDescription>Add a document for {companyName}.</DialogDescription>
      </DialogHeader>
      <FormAlert message={formError} />

      <FieldShell id="upload-type" label="Document type" error={errors.documentTypeId?.message}>
        {(aria) => (
          <Select
            value={typeId}
            disabled={isSubmitting}
            onValueChange={(value) => {
              setValue("documentTypeId", value, { shouldValidate: Boolean(errors.documentTypeId) });
            }}
          >
            <SelectTrigger {...aria} className="w-full">
              <SelectValue placeholder="Choose a document type" />
            </SelectTrigger>
            <SelectContent>
              {documentTypes.map((type) => (
                <SelectItem key={type.id} value={type.id}>
                  {type.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </FieldShell>

      {replacing ? (
        <p className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span>This will replace the current document (kept in history).</span>
        </p>
      ) : null}

      <FieldShell
        id="upload-file"
        label="File"
        error={errors.file?.message}
        help="PDF, PNG or JPG, up to 15 MB."
      >
        {(aria) => (
          <Input
            {...aria}
            type="file"
            accept={FILE_ACCEPT}
            disabled={isSubmitting}
            onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
          />
        )}
      </FieldShell>
      {file ? (
        <p className="-mt-2 text-sm text-muted-foreground" data-testid="selected-file">
          Selected: <span className="font-medium text-foreground" title={file.name}>{truncateFilename(file.name, 48)}</span> ({formatFileSize(file.size)})
        </p>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          id="upload-issue"
          label="Issue date"
          type="date"
          min={MIN_DATE}
          max={MAX_DATE}
          error={errors.issueDate?.message}
          {...register("issueDate")}
        />
        <TextField
          id="upload-expiration"
          label="Expiration date"
          type="date"
          min={MIN_DATE}
          max={MAX_DATE}
          required={selectedType?.hasExpiration}
          aria-required={selectedType?.hasExpiration ? "true" : undefined}
          help={selectedType?.hasExpiration ? "Required for this document type." : "Optional for this document type."}
          error={errors.expirationDate?.message}
          {...register("expirationDate")}
        />
      </div>

      {isSubmitting ? (
        <p role="status" className="text-sm text-muted-foreground">
          Uploading… large files can take a moment. Keep this window open.
        </p>
      ) : null}

      <DialogFooter>
        <Button type="button" variant="outline" disabled={isSubmitting} onClick={onDone}>
          Cancel
        </Button>
        <SubmitButton pending={isSubmitting} pendingLabel="Uploading…">
          {replacing ? "Replace document" : "Upload document"}
        </SubmitButton>
      </DialogFooter>
    </form>
  );
}
