import { z } from "zod";
import { COMPLIANCE_STATUSES } from "@/features/compliance/types";
import { MAX_DATE, MIN_DATE, validateDates, validateFile } from "@/features/documents/schemas";

export { MAX_DATE, MIN_DATE };

// ---- Responses (docs/API.md "Vendor portal (Phase 14)"). Parsed at the boundary so contract drift fails loudly. ----

export const portalInfoSchema = z.object({
  organizationName: z.string(),
  vendorName: z.string(),
  expiresAt: z.string(),
  remainingUploads: z.number().int(),
  acceptingUploads: z.boolean(),
  documentTypes: z.array(
    z.object({
      id: z.string(),
      name: z.string(),
      hasExpiration: z.boolean(),
      status: z.enum(COMPLIANCE_STATUSES),
      expirationDate: z.string().nullable(),
    }),
  ),
});
export type PortalInfo = z.infer<typeof portalInfoSchema>;
export type PortalDocumentType = PortalInfo["documentTypes"][number];

export const portalUploadResultSchema = z.object({
  documentType: z.object({ id: z.string(), name: z.string() }),
  originalFilename: z.string(),
  status: z.string(),
  uploadedAt: z.string(),
  remainingUploads: z.number().int(),
});
export type PortalUploadResult = z.infer<typeof portalUploadResultSchema>;

export const UPLOAD_LINK_STATUSES = ["ACTIVE", "EXPIRED", "REVOKED", "EXHAUSTED"] as const;
export type UploadLinkStatus = (typeof UPLOAD_LINK_STATUSES)[number];

export const uploadLinkSchema = z.object({
  id: z.string(),
  vendorId: z.string(),
  documentTypes: z.array(z.object({ id: z.string(), name: z.string() })),
  status: z.enum(UPLOAD_LINK_STATUSES),
  createdBy: z.object({ fullName: z.string() }).nullable(),
  createdAt: z.string(),
  expiresAt: z.string(),
  revokedAt: z.string().nullable(),
  lastUsedAt: z.string().nullable(),
  useCount: z.number().int(),
  maxUploads: z.number().int(),
});
export type UploadLink = z.infer<typeof uploadLinkSchema>;

export const uploadLinkPageSchema = z.object({
  items: z.array(uploadLinkSchema),
  page: z.number().int(),
  size: z.number().int(),
  totalItems: z.number().int(),
  totalPages: z.number().int(),
});
export type UploadLinkPage = z.infer<typeof uploadLinkPageSchema>;

export const createdUploadLinkSchema = z.object({
  link: uploadLinkSchema,
  url: z.string(),
  emailQueued: z.boolean(),
});
export type CreatedUploadLink = z.infer<typeof createdUploadLinkSchema>;

// ---- Forms ----

/** Vendor upload form, one per requested type; `hasExpiration` decides whether the expiration date is shown/required. */
export function buildPortalUploadSchema(hasExpiration: boolean) {
  return z
    .object({
      file: z.custom<File | null>(() => true),
      issueDate: z.string().trim(),
      expirationDate: z.string().trim(),
    })
    .superRefine((value, context) => {
      const fileProblem = validateFile(value.file);
      if (fileProblem) context.addIssue({ code: "custom", path: ["file"], message: fileProblem });
      const problems = validateDates(value.issueDate, hasExpiration ? value.expirationDate : "", hasExpiration);
      if (problems.issueDate) context.addIssue({ code: "custom", path: ["issueDate"], message: problems.issueDate });
      if (problems.expirationDate) context.addIssue({ code: "custom", path: ["expirationDate"], message: problems.expirationDate });
    })
    .transform((value) => ({
      file: value.file as File,
      issueDate: value.issueDate || null,
      expirationDate: hasExpiration ? value.expirationDate || null : null,
    }));
}
export type PortalUploadFormValues = z.input<ReturnType<typeof buildPortalUploadSchema>>;
export type PortalUploadFormOutput = z.output<ReturnType<typeof buildPortalUploadSchema>>;

const wholeNumber = (label: string, min: number, max: number) =>
  z
    .string()
    .trim()
    .refine((value) => /^\d+$/.test(value) && Number(value) >= min && Number(value) <= max, `${label} must be a whole number from ${min} to ${max}.`)
    .transform(Number);

/** Staff "Send upload link" form. The server re-validates (and checks the types are this vendor's requirements). */
export const sendLinkFormSchema = z.object({
  documentTypeIds: z.array(z.string()).min(1, "Choose at least one document type.").max(20, "Choose at most 20 document types."),
  expiresInDays: wholeNumber("Expiry", 1, 30),
  maxUploads: wholeNumber("Upload limit", 1, 50),
  sendEmail: z.boolean(),
});
export type SendLinkFormValues = z.input<typeof sendLinkFormSchema>;
export type SendLinkFormOutput = z.output<typeof sendLinkFormSchema>;
