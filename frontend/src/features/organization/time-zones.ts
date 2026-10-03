// Curated US zones first (the target market), then every other IANA zone the runtime knows about.
export const US_TIME_ZONES: { value: string; label: string }[] = [
  { value: "America/New_York", label: "Eastern (New York)" },
  { value: "America/Chicago", label: "Central (Chicago)" },
  { value: "America/Denver", label: "Mountain (Denver)" },
  { value: "America/Phoenix", label: "Mountain, no DST (Phoenix)" },
  { value: "America/Los_Angeles", label: "Pacific (Los Angeles)" },
  { value: "America/Anchorage", label: "Alaska (Anchorage)" },
  { value: "Pacific/Honolulu", label: "Hawaii (Honolulu)" },
];

export interface TimeZoneOptions {
  us: { value: string; label: string }[];
  other: string[];
}

/** Always includes `current` so an org with an unusual zone never shows a blank select. */
export function buildTimeZoneOptions(current: string, supported?: string[]): TimeZoneOptions {
  let all = supported;
  if (!all) {
    try {
      const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
      all = intl.supportedValuesOf?.("timeZone") ?? [];
    } catch {
      all = [];
    }
  }
  const usValues = new Set(US_TIME_ZONES.map((z) => z.value));
  const other = new Set(all.filter((z) => !usValues.has(z)));
  if (current && !usValues.has(current)) other.add(current);
  return { us: US_TIME_ZONES, other: [...other].sort() };
}
