import type { HistoryEvent } from "./types";

export interface HistoryDescription {
  /** Headline, e.g. "Details updated". */
  label: string;
  /** Extra lines (changed fields). */
  details: string[];
}

const FIELD_LABELS: Record<string, string> = {
  companyName: "Company name",
  contactName: "Contact name",
  email: "Email",
  phone: "Phone",
  category: "Category",
  notes: "Notes",
};

const MAX_VALUE_LENGTH = 80;

function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "empty";
  const text = typeof value === "string" ? value : JSON.stringify(value);
  return text.length > MAX_VALUE_LENGTH ? `${text.slice(0, MAX_VALUE_LENGTH)}…` : text;
}

function toStrings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

/**
 * The backend records requirement changes as `added` / `removed` type codes. The exact envelope inside
 * `changes` is not pinned down by the contract, so accept the plausible shapes: `{added: {after: [...]}}`,
 * `{added: [...]}`, or `{requirements: {before, after}}` (diffed).
 */
function requirementCodes(changes: HistoryEvent["changes"]): { added: string[]; removed: string[] } {
  if (!changes) return { added: [], removed: [] };
  const pick = (key: string): string[] => {
    const entry = changes[key] as unknown;
    if (Array.isArray(entry)) return toStrings(entry);
    if (entry && typeof entry === "object") {
      const { before, after } = entry as { before?: unknown; after?: unknown };
      return toStrings(after).length > 0 ? toStrings(after) : toStrings(before);
    }
    return [];
  };
  const added = pick("added");
  const removed = pick("removed");
  if (added.length > 0 || removed.length > 0) return { added, removed };

  const whole = changes.requirements;
  if (whole) {
    const before = toStrings(whole.before);
    const after = toStrings(whole.after);
    return { added: after.filter((c) => !before.includes(c)), removed: before.filter((c) => !after.includes(c)) };
  }
  return { added: [], removed: [] };
}

/** Pure: turns an audit event into human-readable text. `typeNames` maps document type code -> name. */
export function describeHistoryEvent(event: HistoryEvent, typeNames: Record<string, string> = {}): HistoryDescription {
  switch (event.action) {
    case "vendor.created":
      return { label: "Vendor created", details: [] };
    case "vendor.deactivated":
      return { label: "Vendor deactivated", details: [] };
    case "vendor.reactivated":
      return { label: "Vendor reactivated", details: [] };
    case "vendor.updated": {
      const details = Object.entries(event.changes ?? {}).map(
        ([field, { before, after }]) => `${FIELD_LABELS[field] ?? field}: ${formatValue(before)} → ${formatValue(after)}`,
      );
      return { label: "Details updated", details };
    }
    case "vendor.requirements_changed": {
      const { added, removed } = requirementCodes(event.changes);
      const name = (code: string) => typeNames[code] ?? code;
      const parts: string[] = [];
      if (added.length > 0) parts.push(`added ${added.map(name).join(", ")}`);
      if (removed.length > 0) parts.push(`removed ${removed.map(name).join(", ")}`);
      return { label: parts.length > 0 ? `Requirements changed: ${parts.join(", ")}` : "Requirements changed", details: [] };
    }
    default:
      return { label: "Activity recorded", details: [] };
  }
}
