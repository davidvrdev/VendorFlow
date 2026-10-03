import type { NotificationStatus } from "./types";

const KIND_LABELS: Record<string, string> = {
  DOCUMENT_REQUEST: "Document request",
  COMPLIANCE_DIGEST: "Compliance digest",
  INVITATION: "Invitation",
  EMAIL_VERIFICATION: "Email verification",
  PASSWORD_RESET: "Password reset",
};

/** "DOCUMENT_REQUEST" -> "Document request"; unknown kinds are humanized rather than hidden. */
export function kindLabel(kind: string): string {
  const known = KIND_LABELS[kind];
  if (known) return known;
  const words = kind.toLowerCase().replace(/_/g, " ").trim();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : "Email";
}

export type StatusTone = "success" | "pending" | "warning" | "danger";

export interface StatusLabel {
  text: string;
  tone: StatusTone;
}

/** DEAD is shown as "Failed" (no more retries); FAILED is still being retried. */
export function statusLabel(status: NotificationStatus): StatusLabel {
  switch (status) {
    case "SENT":
      return { text: "Sent", tone: "success" };
    case "PENDING":
    case "SENDING":
      return { text: "Queued", tone: "pending" };
    case "FAILED":
      return { text: "Retrying", tone: "warning" };
    case "DEAD":
      return { text: "Failed", tone: "danger" };
    default:
      return { text: "Unknown", tone: "pending" };
  }
}
