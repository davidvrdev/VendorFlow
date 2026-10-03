const dateFormat = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeZone: "UTC" });

/**
 * Format an ISO timestamp as a date. Pinned to UTC so server render and browser hydrate to the same
 * text regardless of the viewer's time zone (avoids hydration mismatches).
 */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "" : dateFormat.format(date);
}

const dateTimeFormat = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" });

/** Date and time, pinned to UTC for the same hydration-safety reason as `formatDate`. Labelled so it is not ambiguous. */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "" : `${dateTimeFormat.format(date)} UTC`;
}
