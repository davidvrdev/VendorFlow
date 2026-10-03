import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { RequestFromVendor } from "./request-from-vendor";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const props = { vendorId: "v1", vendorName: "Acme", documentTypeId: "t1", documentTypeName: "W-9", status: "MISSING", role: "MEMBER" as const, vendorEmail: "v@example.com" };

function json(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

describe("RequestFromVendor", () => {
  beforeEach(() => {
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("confirms with the recipient, posts and toasts", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(202, { requestedAt: "2030-01-01T00:00:00Z", recipientEmail: "v@example.com" }));
    vi.stubGlobal("fetch", fetchMock);
    render(<RequestFromVendor {...props} />);
    fireEvent.click(screen.getByRole("button", { name: "Request from vendor: W-9" }));
    expect(screen.getByText("v@example.com")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Send request" }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Request sent to v@example.com"));
    expect(fetchMock.mock.calls[0][0]).toContain("/vendors/v1/document-requests");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ documentTypeId: "t1" });
    expect(refresh).toHaveBeenCalled();
  });

  it("shows 'Already requested today' on 409 and keeps the dialog open", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(409, { title: "Already requested today", status: 409 })));
    render(<RequestFromVendor {...props} />);
    fireEvent.click(screen.getByRole("button", { name: "Request from vendor: W-9" }));
    fireEvent.click(screen.getByRole("button", { name: "Send request" }));
    expect(await screen.findByText("Already requested today")).toBeInTheDocument();
    expect(toast.success).not.toHaveBeenCalled();
  });

  it("renders nothing for viewers and OK status", () => {
    const { container, rerender } = render(<RequestFromVendor {...props} role="VIEWER" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<RequestFromVendor {...props} status="OK" />);
    expect(container).toBeEmptyDOMElement();
  });
});
