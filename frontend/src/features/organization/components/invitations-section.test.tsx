import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Invitation } from "../types";
import { InvitationsSection } from "./invitations-section";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function problem(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

const invitation: Invitation = {
  id: "i1",
  email: "pending@example.com",
  role: "MEMBER",
  expiresAt: "2030-01-01T00:00:00Z",
  createdAt: "2029-12-25T00:00:00Z",
  invitedBy: { fullName: "Olive Owner" },
};

function send(email = "new@example.com") {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: email } });
  fireEvent.click(screen.getByRole("button", { name: "Send invitation" }));
}

describe("InvitationsSection", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("shows 'Showing N of M' only when the server has more rows", () => {
    const { rerender } = render(<InvitationsSection invitations={[invitation]} totalItems={1} myRole="OWNER" />);
    expect(screen.queryByText(/Showing/)).not.toBeInTheDocument();
    rerender(<InvitationsSection invitations={[invitation]} totalItems={80} myRole="OWNER" />);
    expect(screen.getByText("Showing 1 of 80")).toBeInTheDocument();
  });

  it.each([
    [409, "Already a member", "Already a member"],
    [409, "Invitation already pending", "Invitation already pending"],
    [403, "Access denied", "Only owners can invite administrators."],
  ])("shows the server message for %i %s", async (status, title, detail) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(status, { title, detail })));
    render(<InvitationsSection invitations={[]} totalItems={0} myRole="OWNER" />);
    send();
    expect(await screen.findByRole("alert")).toHaveTextContent(detail);
  });

  it("422 Email not verified tells the user what to do", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(422, { title: "Email not verified" })));
    render(<InvitationsSection invitations={[]} totalItems={0} myRole="OWNER" />);
    send();
    expect(await screen.findByRole("alert")).toHaveTextContent("Verify your own email address");
  });

  it("400 on role is shown on the role field", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(problem(400, { title: "Validation failed", errors: [{ field: "role", message: "Role is not allowed." }] })),
    );
    render(<InvitationsSection invitations={[]} totalItems={0} myRole="OWNER" />);
    send();
    expect(await screen.findByText("Role is not allowed.")).toBeInTheDocument();
  });
});
