import { describe, expect, it } from "vitest";
import { selectInviteState, type InviteInputs } from "./invite-state";
import type { InvitationLookup } from "./types";

const invitation: InvitationLookup = {
  organizationName: "Acme HOA",
  role: "MEMBER",
  email: "Sam@Example.com",
  accountExists: false,
};

const base: InviteInputs = {
  tokenReady: true,
  token: "t",
  lookup: { status: "ok", data: invitation },
  session: { status: "signed-out" },
};

describe("selectInviteState", () => {
  it("is loading until the fragment was read, lookup and session resolved", () => {
    expect(selectInviteState({ ...base, tokenReady: false }).kind).toBe("loading");
    expect(selectInviteState({ ...base, lookup: { status: "pending" } }).kind).toBe("loading");
    expect(selectInviteState({ ...base, session: { status: "pending" } }).kind).toBe("loading");
  });

  it("is invalid with no token or a 404 lookup", () => {
    expect(selectInviteState({ ...base, token: null }).kind).toBe("invalid");
    expect(selectInviteState({ ...base, lookup: { status: "invalid" } }).kind).toBe("invalid");
  });

  it("surfaces transient lookup failures separately from invalid invitations", () => {
    expect(selectInviteState({ ...base, lookup: { status: "failed", message: "Try later" } })).toEqual({
      kind: "failed",
      message: "Try later",
    });
  });

  it("offers a create-account form when signed out and no account exists", () => {
    expect(selectInviteState(base).kind).toBe("create-account");
  });

  it("asks to sign in when signed out and the account exists", () => {
    const state = selectInviteState({ ...base, lookup: { status: "ok", data: { ...invitation, accountExists: true } } });
    expect(state.kind).toBe("sign-in-required");
  });

  it("offers Accept when signed in as the invited email (case-insensitive)", () => {
    expect(selectInviteState({ ...base, session: { status: "signed-in", email: "sam@example.COM" } }).kind).toBe("accept");
  });

  it("warns when signed in as a different account", () => {
    const state = selectInviteState({ ...base, session: { status: "signed-in", email: "other@example.com" } });
    expect(state).toMatchObject({ kind: "wrong-account", signedInAs: "other@example.com" });
  });
});
