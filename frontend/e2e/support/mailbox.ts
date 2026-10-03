import { expect, type APIRequestContext } from "@playwright/test";

// The backend (profile "e2e") exposes an in-memory mailbox. It is addressed directly on :8080,
// not through the Next proxy, because it is a test-only endpoint outside /api/v1.
const BACKEND = "http://localhost:8080";

export type MailKind = "EMAIL_VERIFICATION" | "PASSWORD_RESET" | "INVITATION";

interface StoredMessage {
  kind: MailKind;
  to: string;
  subject: string;
  links: string[];
  receivedAt: string;
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
