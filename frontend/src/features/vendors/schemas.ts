import { z } from "zod";
import { NAME_CHARS_MESSAGE, NO_CONTROL_CHARS } from "@/features/auth/schemas";
import type { VendorDetail, VendorInput } from "./types";

// Mirrors the backend rules in docs/API.md "Phase 2 contract details". The server stays authoritative.

/** Notes are multi-line, so line breaks and tabs are allowed; every other control/bidi character is not. */
const NOTES_CHARS = /^(?:[^\p{Cc}‪-‮⁦-⁩]|[\n\r\t])*$/u;
/** Digits, "+", "(", ")", ".", "-", space, "x" and the word "ext" (case-insensitive). */
export const PHONE_PATTERN = /^(?:[0-9+().\- x]|ext)*$/i;

const emptyToNull = (value: string) => (value === "" ? null : value);

const optionalText = (max: number, label: string) =>
  z
    .string()
    .trim()
    .max(max, `${label} must be ${max} characters or fewer.`)
    .regex(NO_CONTROL_CHARS, NAME_CHARS_MESSAGE)
    .transform(emptyToNull);

const emailText = z
  .string()
  .trim()
  .max(254, "Email must be 254 characters or fewer.")
  .refine((value) => value === "" || z.email().safeParse(value).success, "Enter a valid email address.")
  .transform(emptyToNull);

export const vendorFormSchema = z.object({
  companyName: z
    .string()
    .trim()
    .min(1, "Enter the company name.")
    .max(200, "Company name must be 200 characters or fewer.")
    .regex(NO_CONTROL_CHARS, NAME_CHARS_MESSAGE),
  contactName: optionalText(120, "Contact name"),
  email: emailText,
  phone: z
    .string()
    .trim()
    .max(40, "Phone must be 40 characters or fewer.")
    .regex(PHONE_PATTERN, "Use only digits, spaces and + ( ) . - x ext.")
    .transform(emptyToNull),
  category: optionalText(60, "Category"),
  notes: z
    .string()
    .trim()
    .max(5000, "Notes must be 5000 characters or fewer.")
    .regex(NOTES_CHARS, NAME_CHARS_MESSAGE)
    .transform(emptyToNull),
});

/** What the inputs hold (always strings). */
export type VendorFormValues = z.input<typeof vendorFormSchema>;
/** What is sent to the API (trimmed, empty optional fields -> null). */
export type VendorFormOutput = z.output<typeof vendorFormSchema>;

export const EMPTY_VENDOR_FORM: VendorFormValues = {
  companyName: "",
  contactName: "",
  email: "",
  phone: "",
  category: "",
  notes: "",
};

export function vendorToFormValues(vendor: VendorDetail): VendorFormValues {
  return {
    companyName: vendor.companyName,
    contactName: vendor.contactName ?? "",
    email: vendor.email ?? "",
    phone: vendor.phone ?? "",
    category: vendor.category ?? "",
    notes: vendor.notes ?? "",
  };
}

export function toVendorInput(values: VendorFormOutput): VendorInput {
  return { ...values };
}
