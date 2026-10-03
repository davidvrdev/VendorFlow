import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DocumentType, VendorDetail } from "../types";
import { RequirementsCard } from "./requirements-card";
import { RequirementsDialog, toRequirementPayload } from "./requirements-dialog";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh, push: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const types: DocumentType[] = [
  { id: "t1", code: "COI", name: "Certificate of Insurance", hasExpiration: true, requiredByDefault: true, sortOrder: 1 },
  { id: "t2", code: "W9", name: "W-9", hasExpiration: false, requiredByDefault: true, sortOrder: 2 },
  { id: "t3", code: "GL", name: "General Liability", hasExpiration: true, requiredByDefault: false, sortOrder: 3 },
];

// Radix Checkbox measures itself with ResizeObserver, which jsdom lacks.
vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });

describe("toRequirementPayload", () => {
  it("returns selected ids in document type order", () => {
    expect(toRequirementPayload(new Set(["t3", "t1"]), types)).toEqual(["t1", "t3"]);
    expect(toRequirementPayload(new Set(), types)).toEqual([]);
  });
});

describe("RequirementsDialog", () => {
  beforeEach(() => {
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.stubGlobal("fetch", undefined);
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  function open() {
    render(<RequirementsDialog vendorId="v-1" companyName="Acme" documentTypes={types} currentIds={["t1", "t2"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Edit requirements" }));
  }

  it("pre-checks the current requirements in an accessible dialog", async () => {
    open();
    expect(await screen.findByRole("dialog", { name: "Edit required documents" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Certificate of Insurance/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /W-9/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /General Liability/ })).not.toBeChecked();
  });

  it("sends the checked ids with PUT and refreshes", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    open();
    fireEvent.click(await screen.findByRole("checkbox", { name: /General Liability/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: /W-9/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save requirements" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/vendors/v-1/requirements");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ documentTypeIds: ["t1", "t3"] });
  });

  it("shows the server's documentTypeIds field error and stays open", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: "Validation failed", errors: [{ field: "documentTypeIds", message: "Unknown document type." }] }), {
          status: 400,
          headers: { "Content-Type": "application/problem+json" },
        }),
      ),
    );
    open();
    fireEvent.click(await screen.findByRole("button", { name: "Save requirements" }));
    expect(await screen.findByText("Unknown document type.")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });
});

describe("RequirementsCard", () => {
  const vendor: Pick<VendorDetail, "id" | "companyName" | "requirements"> = {
    id: "v-1",
    companyName: "Acme",
    requirements: [{ documentTypeId: "t1", code: "COI", name: "Certificate of Insurance", hasExpiration: true }],
  };

  it("lists requirements with an Expires hint", () => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role="VIEWER" />);
    expect(screen.getByText("Certificate of Insurance")).toBeInTheDocument();
    expect(screen.getByText("Expires")).toBeInTheDocument();
  });

  it("says so when nothing is required", () => {
    render(<RequirementsCard vendor={{ ...vendor, requirements: [] }} documentTypes={types} role="VIEWER" />);
    expect(screen.getByText("No documents are required for this vendor.")).toBeInTheDocument();
  });

  it.each([
    ["VIEWER", false],
    ["MEMBER", false],
    ["ADMIN", true],
    ["OWNER", true],
  ] as const)("shows 'Edit requirements' for %s: %s", (role, visible) => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role={role} />);
    expect(screen.queryByRole("button", { name: "Edit requirements" }) !== null).toBe(visible);
  });
});
