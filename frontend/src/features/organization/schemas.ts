import { z } from "zod";
import type { Organization, Role, UpdateOrganizationRequest } from "./types";

const MAX_OFFSETS = 5;

export type OffsetsParse = { ok: true; values: number[] } | { ok: false; error: string };

/** Parse "30, 14, 7" into numbers. Rules mirror the backend: 1-5 unique whole numbers, each 1-180. */
export function parseReminderOffsets(text: string): OffsetsParse {
  const parts = text
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
  if (parts.length === 0) return { ok: false, error: "Enter at least one reminder day, for example 30, 14, 7." };
  if (parts.some((part) => !/^\d+$/.test(part))) return { ok: false, error: "Use whole numbers separated by commas." };
  const values = parts.map(Number);
  if (values.some((n) => n < 1 || n > 180)) return { ok: false, error: "Each value must be between 1 and 180 days." };
  if (new Set(values).size !== values.length) return { ok: false, error: "Remove duplicate values." };
  if (values.length > MAX_OFFSETS) return { ok: false, error: `Use at most ${MAX_OFFSETS} reminder days.` };
  // Largest first reads naturally: 30, 14, 7 days before expiry.
  return { ok: true, values: [...values].sort((a, b) => b - a) };
}

export const organizationFormSchema = z.object({
  name: z.string().trim().min(1, "Enter an organization name.").max(120, "Name must be 120 characters or fewer."),
  timeZone: z.string().min(1, "Choose a time zone."),
  expiringWindowDays: z
    .string()
    .trim()
    .regex(/^\d+$/, "Enter a whole number of days.")
    .refine((v) => Number(v) >= 1 && Number(v) <= 180, "Enter a number from 1 to 180."),
  reminderOffsets: z.string().superRefine((value, ctx) => {
    const result = parseReminderOffsets(value);
    if (!result.ok) ctx.addIssue({ code: "custom", message: result.error });
  }),
  remindersEnabled: z.boolean(),
});
export type OrganizationFormValues = z.infer<typeof organizationFormSchema>;

export function toFormValues(org: Organization): OrganizationFormValues {
  return {
    name: org.name,
    timeZone: org.timeZone,
    expiringWindowDays: String(org.expiringWindowDays),
    reminderOffsets: org.reminderOffsetsDays.join(", "),
    remindersEnabled: org.remindersEnabled,
  };
}

export function toUpdateRequest(values: OrganizationFormValues): UpdateOrganizationRequest {
  const offsets = parseReminderOffsets(values.reminderOffsets);
  return {
    name: values.name.trim(),
    timeZone: values.timeZone,
    expiringWindowDays: Number(values.expiringWindowDays),
    reminderOffsetsDays: offsets.ok ? offsets.values : [],
    remindersEnabled: values.remindersEnabled,
  };
}

export const inviteFormSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, "Enter an email address.")
    .max(254, "Email must be 254 characters or fewer.")
    .pipe(z.email("Enter a valid email address.")),
  role: z.enum(["ADMIN", "MEMBER", "VIEWER"] satisfies Exclude<Role, "OWNER">[], { error: "Choose a role." }),
});
export type InviteFormValues = z.infer<typeof inviteFormSchema>;
