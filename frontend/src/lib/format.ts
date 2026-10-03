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

/** "850 B", "12 KB", "1.4 MB" (1024-based, like the 15 MB cap). */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  const mb = bytes / (1024 * 1024);
  return `${mb >= 10 ? Math.round(mb) : Math.round(mb * 10) / 10} MB`;
}

/**
 * Shorten a long filename in the middle, keeping the extension visible ("very-long-na….pdf").
 * Display only: render the full name in a `title`.
 */
export function truncateFilename(name: string, max = 36): string {
  if (name.length <= max) return name;
  const dot = name.lastIndexOf(".");
  const extension = dot > 0 && name.length - dot <= 6 ? name.slice(dot) : "";
  const keep = Math.max(max - extension.length - 1, 1);
  return `${name.slice(0, keep)}…${extension}`;
}

/** yyyy-MM-dd (a calendar date without time zone) as "Jan 5, 2030"; empty string when missing/invalid. */
export function formatCalendarDate(date: string | null | undefined): string {
  return date ? formatDate(date) : "";
}
