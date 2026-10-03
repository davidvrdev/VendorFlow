import type { ComplianceSummary } from "./types";

const plural = (n: number, unit: string) => `${n} ${unit}${n === 1 ? "" : "s"}`;

/** "today" / "in 3 days" / "3 days ago". Display only: never decides a status. */
export function relativeDays(days: number): string {
  if (days === 0) return "today";
  return days > 0 ? `in ${plural(days, "day")}` : `${plural(-days, "day")} ago`;
}

/** Requirement row text from the server's daysUntilExpiration: "Expires in 12 days" / "Expired 3 days ago" / "Expires today". */
export function expirationText(days: number | null): string | null {
  if (days === null || !Number.isFinite(days)) return null;
  if (days === 0) return "Expires today";
  return days > 0 ? `Expires ${relativeDays(days)}` : `Expired ${relativeDays(days)}`;
}

/** Relative label for a vendor's next expiration, from the server's `daysUntilNextExpiration` (computed in the
 *  organization's time zone; the client never derives day counts from its own clock). */
export function nextExpirationLabel(days: number | null): string | null {
  return days === null || !Number.isFinite(days) ? null : relativeDays(days);
}

/** Compact non-zero counts in urgency order, at most `max` ("2 missing", "1 expiring"). Display only. */
export function compactCounts(summary: ComplianceSummary, max = 2): string[] {
  const all: [number, string][] = [
    [summary.missing, "missing"],
    [summary.expired, "expired"],
    [summary.reviewRequired, "to review"],
    [summary.expiring, "expiring"],
  ];
  return all.filter(([n]) => n > 0).slice(0, max).map(([n, label]) => `${n} ${label}`);
}

/** Full counts line for the vendor header: "1 missing · 2 expiring · 3 OK". Only non-zero counts. */
export function complianceSummaryText(summary: ComplianceSummary): string {
  const all: [number, string][] = [
    [summary.missing, "missing"],
    [summary.expired, "expired"],
    [summary.reviewRequired, "to review"],
    [summary.expiring, "expiring"],
    [summary.ok, "OK"],
  ];
  return all.filter(([n]) => n > 0).map(([n, label]) => `${n} ${label}`).join(" · ");
}
