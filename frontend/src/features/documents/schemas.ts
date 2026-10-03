import { z } from "zod";
import type { DocumentType } from "./types";

// Client-side mirror of the upload rules in docs/API.md (Phase 3). UX only: the server re-validates everything
// (size, extension, magic bytes, type, dates) and is the authority.

export const MAX_FILE_BYTES = 15 * 1024 * 1024;
export const ALLOWED_EXTENSIONS = ["pdf", "png", "jpg", "jpeg"] as const;
export const FILE_ACCEPT = ".pdf,.png,.jpg,.jpeg";
export const MIN_DATE = "1990-01-01";
export const MAX_DATE = "2100-12-31";

export const FILE_TOO_LARGE_MESSAGE = "File is larger than 15 MB.";
export const FILE_TYPE_MESSAGE = "Only PDF, PNG or JPG files are accepted.";

const DATE_SHAPE = /^\d{4}-\d{2}-\d{2}$/;

export function fileExtension(name: string): string {
  const dot = name.lastIndexOf(".");
  return dot < 0 ? "" : name.slice(dot + 1).toLowerCase();
}

/** Null when the file looks acceptable, otherwise the message to show. Order matches the server: empty, size, extension. */
export function validateFile(file: { name: string; size: number } | null | undefined): string | null {
  if (!file) return "Choose a file to upload.";
  if (file.size === 0) return "The selected file is empty.";
  if (file.size > MAX_FILE_BYTES) return FILE_TOO_LARGE_MESSAGE;
  if (!(ALLOWED_EXTENSIONS as readonly string[]).includes(fileExtension(file.name))) return FILE_TYPE_MESSAGE;
  return null;
}

/** True for a real calendar date written yyyy-MM-dd (rejects 2030-02-31). */
export function isCalendarDate(value: string): boolean {
  if (!DATE_SHAPE.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

/** Shared by the upload and edit-dates forms. Strings compare correctly because the format is fixed-width. */
export function validateDates(
  issueDate: string,
  expirationDate: string,
  requiresExpiration: boolean,
): { issueDate?: string; expirationDate?: string } {
  const problems: { issueDate?: string; expirationDate?: string } = {};
  const range = "must be between 1990 and 2100.";
  if (issueDate) {
    if (!isCalendarDate(issueDate)) problems.issueDate = "Enter a valid issue date.";
    else if (issueDate < MIN_DATE || issueDate > MAX_DATE) problems.issueDate = `Issue date ${range}`;
  }
  if (expirationDate) {
    if (!isCalendarDate(expirationDate)) problems.expirationDate = "Enter a valid expiration date.";
    else if (expirationDate < MIN_DATE || expirationDate > MAX_DATE) problems.expirationDate = `Expiration date ${range}`;
  } else if (requiresExpiration) {
    problems.expirationDate = "Enter the expiration date for this document type.";
  }
  if (!problems.issueDate && !problems.expirationDate && issueDate && expirationDate && issueDate > expirationDate) {
    problems.expirationDate = "Expiration date must be on or after the issue date.";
  }
  return problems;
}

const emptyToNull = (value: string) => (value === "" ? null : value);

/** Built per render because "expiration required" depends on the chosen type. */
export function buildUploadSchema(types: Pick<DocumentType, "id" | "hasExpiration">[]) {
  return z
    .object({
      documentTypeId: z.string().min(1, "Choose a document type."),
      file: z.custom<File | null>(() => true),
      issueDate: z.string().trim(),
      expirationDate: z.string().trim(),
    })
    .superRefine((value, context) => {
      const fileProblem = validateFile(value.file);
      if (fileProblem) context.addIssue({ code: "custom", path: ["file"], message: fileProblem });
      const type = types.find((candidate) => candidate.id === value.documentTypeId);
      const problems = validateDates(value.issueDate, value.expirationDate, type?.hasExpiration ?? false);
      if (problems.issueDate) context.addIssue({ code: "custom", path: ["issueDate"], message: problems.issueDate });
      if (problems.expirationDate) context.addIssue({ code: "custom", path: ["expirationDate"], message: problems.expirationDate });
    })
    .transform((value) => ({
      documentTypeId: value.documentTypeId,
      file: value.file as File,
      issueDate: emptyToNull(value.issueDate),
      expirationDate: emptyToNull(value.expirationDate),
    }));
}

export type UploadFormValues = z.input<ReturnType<typeof buildUploadSchema>>;
export type UploadFormOutput = z.output<ReturnType<typeof buildUploadSchema>>;

/** Edit-dates dialog: the type decides whether the expiration date is required. */
export function buildDatesSchema(requiresExpiration: boolean) {
  return z
    .object({ issueDate: z.string().trim(), expirationDate: z.string().trim() })
    .superRefine((value, context) => {
      const problems = validateDates(value.issueDate, value.expirationDate, requiresExpiration);
      if (problems.issueDate) context.addIssue({ code: "custom", path: ["issueDate"], message: problems.issueDate });
      if (problems.expirationDate) context.addIssue({ code: "custom", path: ["expirationDate"], message: problems.expirationDate });
    })
    .transform((value) => ({ issueDate: emptyToNull(value.issueDate), expirationDate: emptyToNull(value.expirationDate) }));
}

export type DatesFormValues = z.input<ReturnType<typeof buildDatesSchema>>;
export type DatesFormOutput = z.output<ReturnType<typeof buildDatesSchema>>;

export const rejectNoteSchema = z.object({
  note: z.string().trim().min(1, "Explain why the document is rejected.").max(1000, "The note must be 1000 characters or fewer."),
});
export type RejectNoteValues = z.input<typeof rejectNoteSchema>;

/** Document-type create/rename form. The 100-character cap is a UI guess; the server decides. */
export const documentTypeFormSchema = z.object({
  name: z.string().trim().min(1, "Enter a name for the document type.").max(100, "Name must be 100 characters or fewer."),
  hasExpiration: z.boolean(),
  requiredByDefault: z.boolean(),
});
export type DocumentTypeFormValues = z.input<typeof documentTypeFormSchema>;
