"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { ComplianceStatusBadge } from "@/components/compliance-status-badge";
import { FieldShell, TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Input } from "@/components/ui/input";
import { FILE_ACCEPT, validateFile } from "@/features/documents/schemas";
import { formatCalendarDate, formatFileSize, truncateFilename } from "@/lib/format";
import { applyApiError } from "@/lib/forms/api-errors";
import { uploadPortalDocument } from "../api";
import { classifyPortalError, PORTAL_UPLOAD_FIELDS, portalUploadStatusMessages } from "../errors";
import {
  buildPortalUploadSchema,
  MAX_DATE,
  MIN_DATE,
  type PortalDocumentType,
  type PortalUploadFormOutput,
  type PortalUploadFormValues,
} from "../schemas";

interface PortalDocumentCardProps {
  type: PortalDocumentType;
  acceptingUploads: boolean;
  token: string;
  /** Called after a successful upload so the parent refreshes statuses and the remaining count. */
  onUploaded: () => void;
  /** The link stopped working (revoked/expired while the page was open). */
  onInvalid: () => void;
  /** Uploads became unavailable or the limit was hit: refresh to show the closed state. */
  onClosed: () => void;
}

/** One requested document: status (icon + text + color) and, while uploads are open, its upload form. */
export function PortalDocumentCard({ type, acceptingUploads, token, onUploaded, onInvalid, onClosed }: PortalDocumentCardProps) {
  const [formError, setFormError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const schema = useMemo(() => buildPortalUploadSchema(type.hasExpiration), [type.hasExpiration]);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    clearErrors,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<PortalUploadFormValues, unknown, PortalUploadFormOutput>({
    resolver: zodResolver(schema),
    defaultValues: { file: null, issueDate: "", expirationDate: "" },
  });
  const [selected, setSelected] = useState<File | null>(null);
  const [inputKey, setInputKey] = useState(0);
  const idBase = `portal-${type.id}`;

  function onFileChange(file: File | null) {
    setSelected(file);
    setValue("file", file);
    setSuccess(null);
    const problem = file ? validateFile(file) : null;
    if (problem) setError("file", { type: "client", message: problem });
    else clearErrors("file");
  }

  async function onSubmit(values: PortalUploadFormOutput) {
    setFormError(null);
    setSuccess(null);
    try {
      const result = await uploadPortalDocument(token, { documentTypeId: type.id, ...values });
      setSuccess(`Uploaded ${truncateFilename(result.originalFilename, 40)}. The company will review it.`);
      reset();
      setSelected(null);
      setInputKey((value) => value + 1); // a file input cannot be cleared through React state
      onUploaded();
    } catch (error) {
      const failure = classifyPortalError(error);
      if (failure === "invalid") return onInvalid();
      setFormError(
        applyApiError<PortalUploadFormValues>(error, setError, {
          fields: PORTAL_UPLOAD_FIELDS,
          statusMessages: portalUploadStatusMessages(error),
        }),
      );
      if (failure === "unavailable" || failure === "limit") onClosed();
    }
  }

  return (
    <article className="grid gap-4 rounded-lg border bg-card p-4" aria-labelledby={`${idBase}-title`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 id={`${idBase}-title`} className="font-medium">
          {type.name}
        </h3>
        <ComplianceStatusBadge status={type.status} label={type.status === "REVIEW_REQUIRED" ? "Received, awaiting review" : undefined} />
      </div>
      {type.expirationDate ? <p className="-mt-2 text-sm text-muted-foreground">Current document expires {formatCalendarDate(type.expirationDate)}.</p> : null}

      {success ? (
        <p role="status" className="flex items-start gap-2 text-sm font-medium text-emerald-800 dark:text-emerald-200">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          {success}
        </p>
      ) : null}

      {acceptingUploads ? (
        <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
          <FormAlert message={formError} />
          <FieldShell id={`${idBase}-file`} label={`File for ${type.name}`} error={errors.file?.message} help="PDF, PNG or JPG, up to 15 MB.">
            {(aria) => (
              <Input
                {...aria}
                key={inputKey}
                type="file"
                accept={FILE_ACCEPT}
                disabled={isSubmitting}
                onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
              />
            )}
          </FieldShell>
          {selected ? (
            <p className="-mt-2 text-sm text-muted-foreground">
              Selected: <span className="font-medium text-foreground">{truncateFilename(selected.name, 40)}</span> ({formatFileSize(selected.size)})
            </p>
          ) : null}
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField
              id={`${idBase}-issue`}
              label="Issue date (optional)"
              type="date"
              min={MIN_DATE}
              max={MAX_DATE}
              disabled={isSubmitting}
              error={errors.issueDate?.message}
              {...register("issueDate")}
            />
            {type.hasExpiration ? (
              <TextField
                id={`${idBase}-expiration`}
                label="Expiration date"
                type="date"
                min={MIN_DATE}
                max={MAX_DATE}
                required
                aria-required="true"
                disabled={isSubmitting}
                help="Required for this document."
                error={errors.expirationDate?.message}
                {...register("expirationDate")}
              />
            ) : null}
          </div>
          {isSubmitting ? (
            <p role="status" className="text-sm text-muted-foreground">
              Uploading… large files can take a moment. Keep this page open.
            </p>
          ) : null}
          <div>
            <SubmitButton pending={isSubmitting} pendingLabel="Uploading…" aria-label={`Upload ${type.name}`}>
              Upload
            </SubmitButton>
          </div>
        </form>
      ) : null}
    </article>
  );
}
