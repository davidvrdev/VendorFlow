import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Member } from "../types";
import { MembersTable } from "./members-table";

const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function problem(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

const owner: Member = { membershipId: "m1", userId: "u1", fullName: "Olive Owner", email: "o@example.com", role: "OWNER", joinedAt: "2030-01-01T00:00:00Z" };
const viewer: Member = { membershipId: "m2", userId: "u2", fullName: "Vic Viewer", email: "v@example.com", role: "VIEWER", joinedAt: "2030-01-02T00:00:00Z" };

describe("MembersTable", () => {
  beforeEach(() => {
    replace.mockReset();
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("shows 'Showing N of M' when there are more members than rendered", () => {
    render(<MembersTable members={[owner, viewer]} totalItems={120} currentUserId="u1" myRole="OWNER" />);
    expect(screen.getByText("Showing 2 of 120")).toBeInTheDocument();
  });

  it("shows no actions or role selects to a viewer (read-only)", () => {
    render(<MembersTable members={[owner, viewer]} totalItems={2} currentUserId="u2" myRole="VIEWER" />);
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  });

  it("shows the server's 'Last owner' message when leaving is refused (409)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(409, { title: "Last owner" })));
    render(<MembersTable members={[owner]} totalItems={1} currentUserId="u1" myRole="OWNER" />);
    fireEvent.click(screen.getByRole("button", { name: "Leave organization" }));
    const buttons = await screen.findAllByRole("button", { name: "Leave organization" });
    fireEvent.click(buttons[buttons.length - 1]);
    expect(await screen.findByText(/needs at least one owner/)).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("after leaving, goes through /dashboard so the layout re-reads /me", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    render(<MembersTable members={[owner, viewer]} totalItems={2} currentUserId="u2" myRole="VIEWER" />);
    fireEvent.click(screen.getByRole("button", { name: "Leave organization" }));
    const buttons = await screen.findAllByRole("button", { name: "Leave organization" });
    fireEvent.click(buttons[buttons.length - 1]);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    expect(refresh).toHaveBeenCalled();
  });
});
