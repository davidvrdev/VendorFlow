import { z } from "zod";

// ---- Contract ranges (docs/API.md "Automated chasing"). The server re-validates; these give field errors early. ----
export const CADENCE_RANGE = { min: 3, max: 30 } as const;
export const ATTEMPTS_RANGE = { min: 1, max: 10 } as const;
export const LEAD_RANGE = { min: 7, max: 90 } as const;
export const HOUR_RANGE = { min: 0, max: 23 } as const;

// ---- Responses, parsed at the boundary so contract drift fails loudly ----

export const chasingSettingsSchema = z.object({
  enabled: z.boolean(),
  cadenceDays: z.number().int(),
  maxAttempts: z.number().int(),
  leadDays: z.number().int(),
  sendHourLocal: z.number().int(),
  ccStaff: z.boolean(),
});
export type ChasingSettings = z.infer<typeof chasingSettingsSchema>;

export const CHASING_STATUSES = ["IDLE", "ACTIVE", "EXHAUSTED", "PAUSED", "NO_EMAIL"] as const;
export type ChasingStatus = (typeof CHASING_STATUSES)[number];

export const vendorChasingStateSchema = z.object({
  paused: z.boolean(),
  pausedReason: z.enum(["MANUAL", "OPT_OUT"]).nullable(),
  lastChasedAt: z.string().nullable(),
  attempts: z.number().int(),
  maxAttempts: z.number().int(),
  nextChaseAt: z.string().nullable(),
  status: z.enum(CHASING_STATUSES),
});
export type VendorChasingState = z.infer<typeof vendorChasingStateSchema>;

export const CHASE_LINK_STATUSES = ["ACTIVE", "EXHAUSTED", "EXPIRED", "REVOKED"] as const;
export const CHASE_EMAIL_STATUSES = ["PENDING", "SENT", "FAILED", "DEAD"] as const;

export const chaseSchema = z.object({
  id: z.string(),
  date: z.string(),
  attempt: z.number().int(),
  types: z.array(z.object({ id: z.string(), name: z.string(), status: z.enum(["MISSING", "EXPIRED", "EXPIRING"]) })),
  createdAt: z.string(),
  linkStatus: z.enum(CHASE_LINK_STATUSES).nullable(),
  emailStatus: z.enum(CHASE_EMAIL_STATUSES).nullable(),
});
export type Chase = z.infer<typeof chaseSchema>;

export const chasePageSchema = z.object({
  items: z.array(chaseSchema),
  page: z.number().int(),
  size: z.number().int(),
  totalItems: z.number().int(),
  totalPages: z.number().int(),
});
export type ChasePage = z.infer<typeof chasePageSchema>;

export const optOutInfoSchema = z.object({
  organizationName: z.string(),
  vendorName: z.string(),
  optedOut: z.boolean(),
});
export type OptOutInfo = z.infer<typeof optOutInfoSchema>;

// ---- Settings form (numbers are text in the form, like the organization form) ----

const wholeNumber = (label: string, min: number, max: number) =>
  z
    .string()
    .trim()
    .regex(/^\d+$/, `${label} must be a whole number from ${min} to ${max}.`)
    .refine((value) => Number(value) >= min && Number(value) <= max, `${label} must be a whole number from ${min} to ${max}.`);

export const chasingSettingsFormSchema = z.object({
  enabled: z.boolean(),
  cadenceDays: wholeNumber("Days between follow-ups", CADENCE_RANGE.min, CADENCE_RANGE.max),
  maxAttempts: wholeNumber("Maximum follow-ups", ATTEMPTS_RANGE.min, ATTEMPTS_RANGE.max),
  leadDays: wholeNumber("Days before expiry", LEAD_RANGE.min, LEAD_RANGE.max),
  sendHourLocal: wholeNumber("Send hour", HOUR_RANGE.min, HOUR_RANGE.max),
  ccStaff: z.boolean(),
});
export type ChasingSettingsFormValues = z.infer<typeof chasingSettingsFormSchema>;

export function toChasingFormValues(settings: ChasingSettings): ChasingSettingsFormValues {
  return {
    enabled: settings.enabled,
    cadenceDays: String(settings.cadenceDays),
    maxAttempts: String(settings.maxAttempts),
    leadDays: String(settings.leadDays),
    sendHourLocal: String(settings.sendHourLocal),
    ccStaff: settings.ccStaff,
  };
}

/** PUT is a full replace: every field is always sent. */
export function toChasingRequest(values: ChasingSettingsFormValues): ChasingSettings {
  return {
    enabled: values.enabled,
    cadenceDays: Number(values.cadenceDays),
    maxAttempts: Number(values.maxAttempts),
    leadDays: Number(values.leadDays),
    sendHourLocal: Number(values.sendHourLocal),
    ccStaff: values.ccStaff,
  };
}
