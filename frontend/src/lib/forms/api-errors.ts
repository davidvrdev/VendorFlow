import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiError } from "@/lib/api/errors";

export const NETWORK_ERROR_MESSAGE = "We could not reach the server. Check your connection and try again.";

interface ApplyOptions<T extends FieldValues> {
  /** Form field names the server may refer to. Server errors for other fields become form-level. */
  fields: readonly Path<T>[];
  /** Overrides by HTTP status for the form-level message (e.g. 401 -> "Invalid email or password."). */
  statusMessages?: Partial<Record<number, string>>;
  /** Server field name -> form field name, when they differ (e.g. reminderOffsetsDays -> reminderOffsets). */
  fieldAliases?: Record<string, Path<T>>;
  /** Overrides keyed by server field -> message (e.g. email 409). */
  fieldOverrides?: Partial<Record<Path<T>, string>>;
}

/**
 * Map an error thrown by the API layer onto a react-hook-form instance.
 * Field errors (400 validation) go to `setError`; everything else is returned as a string for a
 * form-level <Alert>. Only `detail`/`title` of the problem+json ever reach the user (ApiError already
 * guarantees that); unknown errors collapse to a generic message so nothing raw is rendered.
 */
export function applyApiError<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  { fields, statusMessages, fieldOverrides, fieldAliases }: ApplyOptions<T>,
): string | null {
  if (!(error instanceof ApiError)) return NETWORK_ERROR_MESSAGE;

  const override = statusMessages?.[error.status];
  if (override) return override;

  const unmatched: string[] = [];
  let mapped = 0;
  for (const { field, message } of error.errors ?? []) {
    const name = fields.find((candidate) => candidate === (fieldAliases?.[field] ?? field));
    if (name) {
      setError(name, { type: "server", message: fieldOverrides?.[name] ?? message });
      mapped += 1;
    } else {
      unmatched.push(message);
    }
  }
  if (mapped > 0 && unmatched.length === 0) return null;
  if (unmatched.length > 0) return unmatched.join(" ");
  return error.detail ?? error.title;
}

/** Plain message for non-form contexts (toasts, inline notes). */
export function errorMessage(error: unknown, statusMessages?: Partial<Record<number, string>>): string {
  if (!(error instanceof ApiError)) return NETWORK_ERROR_MESSAGE;
  return statusMessages?.[error.status] ?? error.detail ?? error.title;
}
