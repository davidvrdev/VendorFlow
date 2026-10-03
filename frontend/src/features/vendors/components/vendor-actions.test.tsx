import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { VendorActions } from "./vendor-actions";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh, push: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const active = { id: "v-1", companyName: "Acme", status: "ACTIVE" as const };

describe("VendorActions permissions", () => {
  it("shows nothing to a viewer", () => {
    const { container } = render(<VendorActions vendor={active} role="VIEWER" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("lets a member edit but not deactivate", () => {
    render(<VendorActions vendor={active} role="MEMBER" />);
    expect(screen.getByRole("link", { name: "Edit" })).toHaveAttribute("href", "/vendors/v-1/edit");
    expect(screen.queryByRole("button", { name: "Deactivate" })).not.toBeInTheDocument();
  });

  it("lets an admin edit and deactivate", () => {
    render(<VendorActions vendor={active} role="ADMIN" />);
    expect(screen.getByRole("link", { name: "Edit" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Deactivate" })).toBeInTheDocument();
  });

  it("offers Reactivate for an inactive vendor", () => {
    render(<VendorActions vendor={{ ...active, status: "INACTIVE" }} role="OWNER" />);
    expect(screen.getByRole("button", { name: "Reactivate" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Deactivate" })).not.toBeInTheDocument();
  });
});

describe("VendorActions deactivate flow", () => {
  beforeEach(() => {
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("asks for confirmation before calling the API", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    render(<VendorActions vendor={active} role="ADMIN" />);
    fireEvent.click(screen.getByRole("button", { name: "Deactivate" }));
    expect(await screen.findByRole("alertdialog", { name: "Deactivate Acme?" })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Deactivate vendor" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect((fetchMock.mock.calls[0] as [string])[0]).toBe("/api/v1/vendors/v-1/deactivate");
  });
});
