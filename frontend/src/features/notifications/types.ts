// Mirrors docs/API.md "Phase 6 contract details" (authoritative).
import type { Page } from "@/features/organization/types";

export type { Page };

export type NotificationStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "DEAD";

export interface NotificationView {
  id: string;
  kind: string;
  recipientEmail: string;
  status: NotificationStatus;
  attempts: number;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
}

export interface DocumentRequestResult {
  requestedAt: string;
  recipientEmail: string;
}
