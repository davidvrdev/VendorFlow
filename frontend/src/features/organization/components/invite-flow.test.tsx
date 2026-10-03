import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InviteFlow } from "./invite-flow";

const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh }) }));

const lookup = { organizationName: "Acme HOA", role: "MEMBER", email: "sam@example.com", accountExists: false };
const me = (email: string) => ({
  user: { id: "u1", email, fullName: "Sam", emailVerified: true },
  activeOrganization: null,
  organizations: [],
});

function json(status: number, body?: object) {
  return new Response(body ? JSON.stringify(body) : null, {
    status,
    headers: body ? { "Content-Type": status >= 400 ? "application/problem+json" : "application/json" } : {},
  });
}

/** Route fetch by URL suffix. */
function stubApi(routes: Record<string, () => Response>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    void init;
    const url = String(input);
    const key = Object.keys(routes).find((k) => url.endsWith(k));
    return key ? routes[key]() : json(404, { title: "Not found" });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("InviteFlow", () => {
  beforeEach(() => {
    replace.mockReset();
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
    window.sessionStorage.clear();
    window.history.replaceState(null, "", "/invite#token=inv-1");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
    window.history.replaceState(null, "", "/");
  });

  it("creates an account (200 Me) and goes to the dashboard", async () => {
    const fetchMock = stubApi({
      "/invitations/lookup": () => json(200, lookup),
      "/me": () => json(401, { title: "Authentication required" }),
      "/invitations/accept": () => json(200, me("sam@example.com")),
    });
    render(<InviteFlow />);
    fireEvent.change(await screen.findByLabelText("Full name"), { target: { value: "Sam Rivera" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-long-passphrase-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    const acceptCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/invitations/accept"))!;
    expect(JSON.parse(String(acceptCall[1]?.body))).toEqual({
      token: "inv-1",
      fullName: "Sam Rivera",
      password: "a-long-passphrase-123",
    });
  });

  it("maps 400 field errors from the server onto fullName / password", async () => {
    stubApi({
      "/invitations/lookup": () => json(200, lookup),
      "/me": () => json(401, { title: "Authentication required" }),
      "/invitations/accept": () =>
        json(400, { title: "Validation failed", errors: [{ field: "password", message: "This password is too common." }] }),
    });
    render(<InviteFlow />);
    fireEvent.change(await screen.findByLabelText("Full name"), { target: { value: "Sam" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-long-passphrase-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }));
    expect(await screen.findByText("This password is too common.")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("409 'Sign in to accept' switches to the sign-in step and keeps the token for the login detour", async () => {
    stubApi({
      "/invitations/lookup": () => json(200, lookup),
      "/me": () => json(401, { title: "Authentication required" }),
      "/invitations/accept": () => json(409, { title: "Sign in to accept" }),
    });
    render(<InviteFlow />);
    fireEvent.change(await screen.findByLabelText("Full name"), { target: { value: "Sam" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-long-passphrase-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account and join" }));
    const link = await screen.findByRole("link", { name: "Sign in to accept" });
    expect(link).toHaveAttribute("href", "/login?next=/invite");
    fireEvent.click(link);
    expect(window.sessionStorage.getItem("vf.invite-token")).toBe("inv-1");
  });

  it("uses the stashed token after the sign-in detour and accepts with it (200 Me, no new account fields)", async () => {
    window.history.replaceState(null, "", "/invite");
    window.sessionStorage.setItem("vf.invite-token", "inv-1");
    const fetchMock = stubApi({
      "/invitations/lookup": () => json(200, { ...lookup, accountExists: true }),
      "/me": () => json(200, me("sam@example.com")),
      "/invitations/accept": () => json(200, me("sam@example.com")),
    });
    render(<InviteFlow />);
    fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    const acceptCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/invitations/accept"))!;
    expect(JSON.parse(String(acceptCall[1]?.body))).toEqual({ token: "inv-1" });
    expect(window.sessionStorage.getItem("vf.invite-token")).toBeNull();
  });

  it("signed in with another email: offers sign out; a server 403 'Wrong account' also lands there", async () => {
    stubApi({
      "/invitations/lookup": () => json(200, { ...lookup, accountExists: true }),
      "/me": () => json(200, me("other@example.com")),
    });
    render(<InviteFlow />);
    expect(await screen.findByRole("alert")).toHaveTextContent("You are signed in as other@example.com");
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });

  it("404 on accept shows the invalid-invitation screen", async () => {
    stubApi({
      "/invitations/lookup": () => json(200, { ...lookup, accountExists: true }),
      "/me": () => json(200, me("sam@example.com")),
      "/invitations/accept": () => json(404, { title: "Not found" }),
    });
    render(<InviteFlow />);
    fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));
    expect(await screen.findByText("This invitation is invalid or has expired.")).toBeInTheDocument();
  });
});
