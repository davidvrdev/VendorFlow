import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SwitchOrganizationList } from "./switch-organization-list";

const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh }) }));

const orgs = [
  { id: "o1", name: "Acme HOA", role: "ADMIN" as const },
  { id: "o2", name: "Beta Towers", role: "VIEWER" as const },
];

describe("SwitchOrganizationList", () => {
  beforeEach(() => {
    replace.mockReset();
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("switches to the chosen organization and goes to the dashboard", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ user: {}, activeOrganization: orgs[1], organizations: orgs }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    render(<SwitchOrganizationList organizations={orgs} />);
    fireEvent.click(screen.getByRole("button", { name: /Beta Towers/ }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ organizationId: "o2" });
  });

  it("shows the server error and re-enables the buttons", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ title: "Not found", detail: "Organization not found." }), { status: 404, headers: { "Content-Type": "application/problem+json" } })));
    render(<SwitchOrganizationList organizations={orgs} />);
    fireEvent.click(screen.getByRole("button", { name: /Acme HOA/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Organization not found.");
    expect(screen.getByRole("button", { name: /Acme HOA/ })).toBeEnabled();
  });
});
