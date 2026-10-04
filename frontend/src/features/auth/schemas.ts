import { z } from "zod";

// Client validation mirrors the backend rules in docs/API.md. The server stays authoritative
// (it also rejects common passwords and password == email, which we cannot check here).
const email = z
  .string()
  .trim()
  .min(1, "Enter your email address.")
  .max(254, "Email must be 254 characters or fewer.")
  .pipe(z.email("Enter a valid email address."));

// Mirrors the backend: control characters and bidi overrides/isolates are rejected in display names.
export const NO_CONTROL_CHARS = /^[^\p{Cc}\u202A-\u202E\u2066-\u2069]*$/u;
export const NAME_CHARS_MESSAGE = "Remove special control characters.";

const newPassword = z
  .string()
  .min(12, "Password must be at least 12 characters.")
  .max(128, "Password must be 128 characters or fewer.");

export const PASSWORD_HELP = "Use 12 to 128 characters. A long passphrase works well.";

export const loginSchema = z.object({
  email,
  password: z.string().min(1, "Enter your password."),
});
export type LoginValues = z.infer<typeof loginSchema>;

export const signupSchema = z.object({
  fullName: z
    .string()
    .trim()
    .min(1, "Enter your full name.")
    .max(100, "Name must be 100 characters or fewer.")
    .regex(NO_CONTROL_CHARS, NAME_CHARS_MESSAGE),
  email,
  password: newPassword,
  organizationName: z
    .string()
    .trim()
    .min(1, "Enter your organization name.")
    .max(120, "Organization name must be 120 characters or fewer.")
    .regex(NO_CONTROL_CHARS, NAME_CHARS_MESSAGE),
});
export type SignupValues = z.infer<typeof signupSchema>;

export const forgotPasswordSchema = z.object({ email });
export type ForgotPasswordValues = z.infer<typeof forgotPasswordSchema>;

export const resetPasswordSchema = z
  .object({ newPassword, confirmPassword: z.string().min(1, "Confirm your new password.") })
  .refine((v) => v.newPassword === v.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match.",
  });
export type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;

export const inviteAccountSchema = z.object({
  fullName: signupSchema.shape.fullName,
  password: newPassword,
});
export type InviteAccountValues = z.infer<typeof inviteAccountSchema>;

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, "Enter your current password.").max(128, "Password must be 128 characters or fewer."),
    newPassword,
    confirmPassword: z.string().min(1, "Confirm your new password."),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match.",
  });
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;
