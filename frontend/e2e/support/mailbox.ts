import { expect, type APIRequestContext } from "@playwright/test";

// The backend (profile "e2e") exposes an in-memory mailbox. It is addressed directly on :8080,
// not through the Next proxy, because it is a test-only endpoint outside /api/v1.
const BACKEND = "http://localhost:8080";

export type MailKind = "EMAIL_VERIFICATION" | "PASSWORD_RESET" | "INVITATION" | "DOCUMENT_REQUEST" | "COMPLIANCE_DIGEST" | "VENDOR_CHASE" | "CHASING_STAFF_NOTICE";

interface StoredMessage {
  kind: MailKind;
  to: string;
  subject: string;
  links: string[];
  receivedAt: string;
  /** Body text, when the e2e mailbox exposes it (assumed optional). */
  text?: string;
  body?: string;
  html?: string;
}

export interface MailSummary {
  subject: string;
  /** subject + any body fields the mailbox returned, for "mentions X" assertions. */
  content: string;
}

/**
 * Wait for the newest email of `kind` sent to `to` and return its first link.
 * `minCount` lets a caller wait for the Nth message of that kind (e.g. after "resend").
 */
export async function latestLink(
  request: APIRequestContext,
  to: string,
  kind: MailKind,
  minCount = 1,
): Promise<string> {
  let link = "";
  await expect
    .poll(
      async () => {
        const response = await request.get(`${BACKEND}/api/test/mailbox`, { params: { to } });
        if (!response.ok()) return 0;
        const messages = ((await response.json()) as StoredMessage[]).filter((m) => m.kind === kind);
        link = messages.at(-1)?.links[0] ?? "";
        return messages.length;
      },
      { message: `waiting for ${kind} email to ${to}`, timeout: 20_000, intervals: [300, 500, 1000] },
    )
    .toBeGreaterThanOrEqual(minCount);
  return link;
}

/** Wait for the Nth message of `kind` to `to` and return its subject and searchable content. */
export async function waitForMessage(request: APIRequestContext, to: string, kind: MailKind, minCount = 1): Promise<MailSummary> {
  let latest: StoredMessage | undefined;
  await expect
    .poll(
      async () => {
        const response = await request.get(`${BACKEND}/api/test/mailbox`, { params: { to } });
        if (!response.ok()) return 0;
        const messages = ((await response.json()) as StoredMessage[]).filter((m) => m.kind === kind);
        latest = messages.at(-1);
        return messages.length;
      },
      { message: `waiting for ${kind} email to ${to}`, timeout: 30_000, intervals: [300, 500, 1000] },
    )
    .toBeGreaterThanOrEqual(minCount);
  const message = latest as StoredMessage;
  return { subject: message.subject, content: [message.subject, message.text, message.body, message.html].filter(Boolean).join(" ") };
}

/** Wait for the Nth message of `kind` to `to` and return ALL its links (a chase email has an upload and an unsubscribe link). */
export async function allLinks(request: APIRequestContext, to: string, kind: MailKind, minCount = 1): Promise<string[]> {
  let links: string[] = [];
  await expect
    .poll(
      async () => {
        const response = await request.get(`${BACKEND}/api/test/mailbox`, { params: { to } });
        if (!response.ok()) return 0;
        const messages = ((await response.json()) as StoredMessage[]).filter((m) => m.kind === kind);
        links = messages.at(-1)?.links ?? [];
        return messages.length;
      },
      { message: `waiting for ${kind} email to ${to}`, timeout: 30_000, intervals: [300, 500, 1000] },
    )
    .toBeGreaterThanOrEqual(minCount);
  return links;
}
