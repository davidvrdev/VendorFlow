const dateFormat = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeZone: "UTC" });

/**
 * Format an ISO timestamp as a date. Pinned to UTC so server render and browser hydrate to the same
 * text regardless of the viewer's time zone (avoids hydration mismatches).
 */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "" : dateFormat.format(date);
}
