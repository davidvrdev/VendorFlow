import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { VendorDetail } from "../types";
import { VendorForm } from "./vendor-form";

const push = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push, refresh }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function problem(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

const created: VendorDetail = {
  id: "v-1",
  companyName: "Acme",
  contactName: null,
  email: null,
  phone: null,
  category: null,
  status: "ACTIVE",
  requirementCount: 3,
  compliance: { status: "COMPLIANT", missing: 0, expired: 0, expiring: 0, reviewRequired: 0, ok: 3, nextExpiration: null, daysUntilNextExpiration: null },
  createdAt: "2030-01-01T00:00:00Z",
  updatedAt: "2030-01-01T00:00:00Z",
  notes: null,
  createdBy: null,
  requirements: [],
  otherDocuments: [],
};

function submit(companyName: string) {
  fireEvent.change(screen.getByLabelText(/Company name/), { target: { value: companyName } });
  fireEvent.click(screen.getByRole("button", { name: "Add vendor" }));
}

describe("VendorForm", () => {
  beforeEach(() => {
    push.mockReset();
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("requires a company name and does not call the API", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<VendorForm />);
    fireEvent.click(screen.getByRole("button", { name: "Add vendor" }));
    expect(await screen.findByText("Enter the company name.")).toBeInTheDocument();
    expect(screen.getByLabelText(/Company name/)).toHaveAttribute("aria-invalid", "true");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("shows client validation for a bad email and phone", async () => {
    vi.stubGlobal("fetch", vi.fn());
    render(<VendorForm />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "nope" } });
    fireEvent.change(screen.getByLabelText("Phone"), { target: { value: "call me" } });
    submit("Acme");
    expect(await screen.findByText("Enter a valid email address.")).toBeInTheDocument();
    expect(screen.getByText(/Use only digits/, { selector: "p#phone-error" })).toBeInTheDocument();
  });

  it("posts trimmed values (empty optional fields as null) and goes to the new vendor", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(created), { status: 201, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    render(<VendorForm />);
    submit("  Acme  ");
    await waitFor(() => expect(push).toHaveBeenCalledWith("/vendors/v-1"));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/vendors");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      companyName: "Acme",
      contactName: null,
      email: null,
      phone: null,
      category: null,
      notes: null,
    });
  });

  it("maps 409 to a company name field error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(409, { title: "Vendor already exists" })));
    render(<VendorForm />);
    submit("Acme");
    expect(await screen.findByText("A vendor with this name already exists.")).toBeInTheDocument();
    expect(screen.getByLabelText(/Company name/)).toHaveAttribute("aria-invalid", "true");
    expect(push).not.toHaveBeenCalled();
  });

  it("maps server field errors onto fields", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(problem(400, { title: "Validation failed", errors: [{ field: "email", message: "must be a well-formed email address" }] })),
    );
    render(<VendorForm />);
    submit("Acme");
    expect(await screen.findByText("must be a well-formed email address")).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toHaveAttribute("aria-invalid", "true");
  });

  it("prefills and PUTs in edit mode", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(created), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    render(<VendorForm vendor={{ ...created, contactName: "Dana" }} />);
    expect(screen.getByLabelText("Contact name")).toHaveValue("Dana");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/vendors/v-1"));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/vendors/v-1");
    expect(init.method).toBe("PUT");
  });
});
