"use client";

import { Download } from "lucide-react";
import { useState, type FormEvent } from "react";
import { FieldShell } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatFileSize, truncateFilename } from "@/lib/format";
import { TEMPLATE_URL } from "../api";
import { CSV_ACCEPT, MAX_IMPORT_ROWS, validateCsvFile } from "../schemas";

const COLUMNS: { name: string; note: string }[] = [
  { name: "company_name", note: "Required. Used to match existing vendors (not case-sensitive)." },
  { name: "contact_name", note: "Optional." },
  { name: "email", note: "Optional. Must be a valid email address." },
  { name: "phone", note: "Optional." },
  { name: "category", note: "Optional, e.g. Plumbing." },
  { name: "notes", note: "Optional. May contain line breaks." },
  { name: "status", note: "Optional: ACTIVE or INACTIVE." },
];

interface UploadStepProps {
  pending: boolean;
  error: string | null;
  onUpload: (file: File) => void;
}

export function UploadStep({ pending, error, onUpload }: UploadStepProps) {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | undefined>();

  function onFileChange(selected: File | null) {
    setFile(selected);
    // Early feedback only; the server re-validates size, extension and encoding.
    setFileError(selected ? (validateCsvFile(selected) ?? undefined) : undefined);
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const problem = validateCsvFile(file);
    if (problem || !file) {
      setFileError(problem ?? undefined);
      return;
    }
    onUpload(file);
  }

  return (
    <div className="grid max-w-3xl gap-6">
      <section aria-labelledby="columns-heading" className="grid gap-3">
        <h2 id="columns-heading" className="text-base font-semibold">
          1. Prepare your file
        </h2>
        <p className="text-sm text-muted-foreground">
          Use a CSV file with a header row. Columns can be in any order. An import never erases data: empty cells leave existing values
          unchanged. Up to {MAX_IMPORT_ROWS.toLocaleString("en-US")} rows and 1 MB per file.
        </p>
        <dl className="grid gap-1 rounded-md border px-4 py-3 text-sm sm:grid-cols-[10rem_1fr]">
          {COLUMNS.map((column) => (
            <div key={column.name} className="contents">
              <dt className="font-mono text-xs leading-6 font-medium">{column.name}</dt>
              <dd className="text-muted-foreground">{column.note}</dd>
            </div>
          ))}
        </dl>
        <div>
          {/* Plain link to a GET endpoint: the browser downloads it. */}
          <a href={TEMPLATE_URL} download className={buttonVariants({ variant: "outline" })}>
            <Download aria-hidden="true" data-icon="inline-start" />
            Download template
          </a>
        </div>
      </section>

      <form onSubmit={onSubmit} noValidate className="grid gap-4">
        <h2 className="text-base font-semibold">2. Upload and preview</h2>
        <FormAlert message={error} />
        <FieldShell id="import-file" label="CSV file" error={fileError} help="A .csv file, up to 1 MB, saved as UTF-8.">
          {(aria) => (
            <Input
              {...aria}
              type="file"
              accept={CSV_ACCEPT}
              disabled={pending}
              onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
            />
          )}
        </FieldShell>
        {file ? (
          <p className="-mt-2 text-sm text-muted-foreground" data-testid="selected-file">
            Selected: <span className="font-medium text-foreground" title={file.name}>{truncateFilename(file.name, 48)}</span> (
            {formatFileSize(file.size)})
          </p>
        ) : null}
        {pending ? (
          <p role="status" className="text-sm text-muted-foreground">
            Checking your file… nothing is imported yet.
          </p>
        ) : null}
        <div>
          <SubmitButton pending={pending} pendingLabel="Uploading…">
            Upload &amp; preview
          </SubmitButton>
        </div>
      </form>
    </div>
  );
}
