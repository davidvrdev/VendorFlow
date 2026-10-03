import type { InvitationLookup } from "./types";

export type LookupResult =
  | { status: "pending" }
  | { status: "invalid" } // 404: invalid, expired, revoked or already accepted (same body by design)
  | { status: "failed"; message: string } // rate limit, 5xx, network
  | { status: "ok"; data: InvitationLookup };

export type SessionResult =
  | { status: "pending" }
  | { status: "signed-out" }
  | { status: "signed-in"; email: string };

export interface InviteInputs {
  /** False until the URL fragment has been read on the client. */
  tokenReady: boolean;
  token: string | null;
  lookup: LookupResult;
  session: SessionResult;
}

export type InviteViewState =
  | { kind: "loading" }
  | { kind: "invalid" }
  | { kind: "failed"; message: string }
  | { kind: "accept"; invitation: InvitationLookup } // signed in as the invited email
  | { kind: "wrong-account"; invitation: InvitationLookup; signedInAs: string }
  | { kind: "create-account"; invitation: InvitationLookup } // signed out, no account yet
  | { kind: "sign-in-required"; invitation: InvitationLookup }; // signed out, account exists

/** Pure decision of what the /invite page shows. Kept free of React so every branch is unit-tested. */
export function selectInviteState({ tokenReady, token, lookup, session }: InviteInputs): InviteViewState {
  if (!tokenReady) return { kind: "loading" };
  if (!token) return { kind: "invalid" };
  if (lookup.status === "invalid") return { kind: "invalid" };
  if (lookup.status === "failed") return { kind: "failed", message: lookup.message };
  if (lookup.status === "pending" || session.status === "pending") return { kind: "loading" };

  const invitation = lookup.data;
  if (session.status === "signed-in") {
    return session.email.trim().toLowerCase() === invitation.email.trim().toLowerCase()
      ? { kind: "accept", invitation }
      : { kind: "wrong-account", invitation, signedInAs: session.email };
  }
  return invitation.accountExists ? { kind: "sign-in-required", invitation } : { kind: "create-account", invitation };
}

// The token is kept in sessionStorage only for the "sign in to accept" detour (login -> back to
// /invite). Trade-off: sessionStorage is readable by any script on our origin (XSS), unlike the URL
// fragment which we strip immediately. Mitigations: tab-scoped, cleared right after use or on any
// invalid result, and an invitation token alone cannot join anyone: accepting still needs a session
// whose email matches the invitation.
const STORAGE_KEY = "vf.invite-token";

export function stashInviteToken(token: string): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, token);
  } catch {
    /* storage unavailable (private mode): the user will need to reopen the email link */
  }
}

export function readStashedInviteToken(): string | null {
  try {
    return window.sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function clearStashedInviteToken(): void {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}
