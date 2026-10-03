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


/**
 * Document events (docs/API.md Phase 3 only lists their audit actions, not the exact metadata shape), so everything
 * read here is optional and has a fallback. Assumed: values live either flat (`changes.decision = "APPROVED"`) or as
 * `{before, after}` entries under keys like decision, note, filename/originalFilename, typeCode/documentTypeCode,
 * issueDate, expirationDate. Unknown shapes still yield a readable label with no details.
 */
function metaValue(changes: HistoryEvent["changes"], keys: string[]): unknown {
  if (!changes) return undefined;
  for (const key of keys) {
    const entry = (changes as Record<string, unknown>)[key];
    if (entry === undefined) continue;
    if (entry && typeof entry === "object" && !Array.isArray(entry) && ("after" in entry || "before" in entry)) {
      const { before, after } = entry as { before?: unknown; after?: unknown };
      return after ?? before;
    }
    return entry;
  }
  return undefined;
}

const asText = (value: unknown): string | undefined => (typeof value === "string" && value.trim() !== "" ? value.trim() : undefined);

function documentLine(changes: HistoryEvent["changes"], typeNames: Record<string, string>): string[] {
  const filename = asText(metaValue(changes, ["originalFilename", "filename", "fileName"]));
  const code = asText(metaValue(changes, ["documentTypeCode", "typeCode", "documentType", "type"]));
  const type = code ? (typeNames[code] ?? code) : undefined;
  if (filename && type) return [`${type}: ${filename}`];
  if (filename || type) return [(filename ?? type) as string];
  return [];
}

const DATE_FIELD_LABELS: Record<string, string> = { issueDate: "Issue date", expirationDate: "Expiration date" };

function describeDocumentEvent(event: HistoryEvent, typeNames: Record<string, string>): HistoryDescription | null {
  const base = documentLine(event.changes, typeNames);
  switch (event.action) {
    case "document.uploaded":
      return { label: "Document uploaded", details: base };
    case "document.superseded":
      return { label: "Document replaced by a newer upload", details: base };
    case "document.archived":
      return { label: "Document archived", details: base };
    case "document.downloaded":
      return { label: "Document downloaded", details: base };
    case "document.reviewed": {
      const decision = asText(metaValue(event.changes, ["decision", "reviewStatus", "status"]))?.toUpperCase();
      const note = asText(metaValue(event.changes, ["note", "reviewNote"]));
      const label = decision === "APPROVED" ? "Document approved" : decision === "REJECTED" ? "Document rejected" : "Document reviewed";
      return { label, details: [...base, ...(note ? [`Note: ${note}`] : [])] };
    }
    case "document.dates_changed": {
      const changes: string[] = [];
      for (const [field, label] of Object.entries(DATE_FIELD_LABELS)) {
        const entry = event.changes?.[field] as { before?: unknown; after?: unknown } | undefined;
        if (entry && typeof entry === "object" && ("before" in entry || "after" in entry)) {
          changes.push(`${label}: ${formatValue(entry.before)} → ${formatValue(entry.after)}`);
        }
      }
      return { label: "Document dates changed", details: [...base, ...changes] };
    }
    default:
      return null;
  }
}

/** Pure: turns an audit event into human-readable text. `typeNames` maps document type code -> name. */
export function describeHistoryEvent(event: HistoryEvent, typeNames: Record<string, string> = {}): HistoryDescription {
  switch (event.action) {
    case "vendor.created":
      return { label: "Vendor created", details: [] };
    case "vendor.document_requested": {
      const code = asText(metaValue(event.changes, ["documentTypeCode", "typeCode", "documentType", "type"]));
      const type = code ? (typeNames[code] ?? code) : undefined;
      const email = asText(metaValue(event.changes, ["recipientEmail", "recipient", "email", "to"]));
      const label = `Requested ${type ?? "a document"}${email ? ` from ${email}` : ""}`;
      return { label, details: [] };
    }
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
    default: {
      const document = describeDocumentEvent(event, typeNames);
      if (document) return document;
      return { label: "Activity recorded", details: [] };
    }
  }
}
