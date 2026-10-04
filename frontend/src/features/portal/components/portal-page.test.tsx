import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { portalInfo } from "../fixtures";
import { resetPortalTokenForTests } from "../token";
import { PortalPage } from "./portal-page";

const api = vi.hoisted(() => ({ fetchPortalInfo: vi.fn(), uploadPortalDocument: vi.fn() }));
vi.mock("../api", () => api);

const TOKEN = "abcDEF1234567890_-token";

function openPortal(hash: string) {
  window.history.replaceState(null, "", `/portal${hash}`);
}

const pdf = () => new File(["%PDF-1.4"], "coi.pdf", { type: "application/pdf" });

describe("PortalPage", () => {
  beforeEach(() => {
    resetPortalTokenForTests();
    api.fetchPortalInfo.mockReset();
    api.uploadPortalDocument.mockReset();
  });
  afterEach(() => window.history.replaceState(null, "", "/"));

  it("moves the token out of the URL before fetching and sends it to the API", async () => {
    openPortal(`#token=${TOKEN}`);
    const replace = vi.spyOn(window.history, "replaceState");
    api.fetchPortalInfo.mockResolvedValue(portalInfo());
    render(<PortalPage />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading");
    await screen.findByRole("heading", { name: "Documents for Sunrise HOA" });
    expect(window.location.hash).toBe("");
    expect(window.location.pathname).toBe("/portal");
    expect(replace).toHaveBeenCalledWith(null, "", "/portal");
    expect(api.fetchPortalInfo).toHaveBeenCalledWith(TOKEN);
    replace.mockRestore();
  });

  it("loads a new link pasted into an already open tab (hashchange), strips it again and ignores the old result", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValueOnce(portalInfo());
    render(<PortalPage />);
    await screen.findByText("Acme Plumbing");

    const NEW_TOKEN = "zzzNEW1234567890_-token";
    api.fetchPortalInfo.mockResolvedValueOnce(portalInfo({ vendorName: "Other Vendor LLC" }));
    window.location.hash = `#token=${NEW_TOKEN}`; // jsdom fires hashchange
    expect(await screen.findByText("Other Vendor LLC")).toBeInTheDocument();
    expect(api.fetchPortalInfo).toHaveBeenLastCalledWith(NEW_TOKEN);
    expect(window.location.hash).toBe("");
    expect(window.location.pathname).toBe("/portal");
  });

  it("shows org, vendor, expiry, remaining uploads and each type with icon+text status", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValue(portalInfo());
    render(<PortalPage />);
    await screen.findByText("Acme Plumbing");
    expect(screen.getByText("Uploads remaining:").parentElement).toHaveTextContent("5");
    expect(screen.getByText("Missing")).toBeInTheDocument();
    expect(screen.getByText("Received, awaiting review")).toBeInTheDocument();
    expect(screen.getByLabelText("File for Certificate of Insurance")).toHaveAttribute("accept", ".pdf,.png,.jpg,.jpeg");
    // Expiration date only for types that expire.
    expect(screen.getAllByLabelText("Expiration date")).toHaveLength(1);
  });

  it("asks to reopen the link when there is no token (reload)", async () => {
    openPortal("");
    render(<PortalPage />);
    expect(await screen.findByText(/Open the link from your email again/)).toBeInTheDocument();
    expect(api.fetchPortalInfo).not.toHaveBeenCalled();
  });

  it("shows the generic message for a 404", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockRejectedValue(new ApiError({ status: 404, title: "x" }));
    render(<PortalPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("This link is invalid or has expired. Ask the company that sent it for a new one.");
  });

  it("offers retry on a server error", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockRejectedValueOnce(new ApiError({ status: 500, title: "Something went wrong on our side. Please try again." }));
    api.fetchPortalInfo.mockResolvedValueOnce(portalInfo());
    render(<PortalPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByText("Acme Plumbing");
  });

  it("hides the upload forms when the link is not accepting uploads", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValue(portalInfo({ acceptingUploads: false, remainingUploads: 0 }));
    render(<PortalPage />);
    expect(await screen.findByText("Uploads are closed")).toBeInTheDocument();
    expect(screen.queryByLabelText(/File for/)).not.toBeInTheDocument();
    expect(screen.getByText("Missing")).toBeInTheDocument();
  });

  it("validates client-side and does not call the API for a bad file", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValue(portalInfo());
    render(<PortalPage />);
    const input = await screen.findByLabelText("File for W-9");
    fireEvent.change(input, { target: { files: [new File(["x"], "notes.txt")] } });
    expect(await screen.findByText("Only PDF, PNG or JPG files are accepted.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Upload W-9" }));
    await waitFor(() => expect(screen.getAllByText("Only PDF, PNG or JPG files are accepted.").length).toBeGreaterThan(0));
    expect(api.uploadPortalDocument).not.toHaveBeenCalled();
  });

  it("requires the expiration date for types that expire", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValue(portalInfo());
    render(<PortalPage />);
    const input = await screen.findByLabelText("File for Certificate of Insurance");
    fireEvent.change(input, { target: { files: [pdf()] } });
    fireEvent.click(screen.getByRole("button", { name: "Upload Certificate of Insurance" }));
    expect(await screen.findByText("Enter the expiration date for this document type.")).toBeInTheDocument();
    expect(api.uploadPortalDocument).not.toHaveBeenCalled();
  });

  it("uploads, shows success and refreshes the list", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValueOnce(portalInfo());
    api.fetchPortalInfo.mockResolvedValueOnce(
      portalInfo({
        remainingUploads: 4,
        documentTypes: [
          { id: "t1", name: "Certificate of Insurance", hasExpiration: true, status: "REVIEW_REQUIRED", expirationDate: "2031-01-01" },
          { id: "t2", name: "W-9", hasExpiration: false, status: "REVIEW_REQUIRED", expirationDate: null },
        ],
      }),
    );
    api.uploadPortalDocument.mockResolvedValue({
      documentType: { id: "t1", name: "x" },
      originalFilename: "coi.pdf",
      status: "PENDING_REVIEW",
      uploadedAt: "",
      remainingUploads: 4,
    });
    render(<PortalPage />);
    fireEvent.change(await screen.findByLabelText("File for Certificate of Insurance"), { target: { files: [pdf()] } });
    fireEvent.change(screen.getByLabelText("Expiration date"), { target: { value: "2031-01-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Upload Certificate of Insurance" }));
    expect(await screen.findByText(/Uploaded coi\.pdf/)).toBeInTheDocument();
    expect(api.uploadPortalDocument).toHaveBeenCalledWith(TOKEN, expect.objectContaining({ documentTypeId: "t1", expirationDate: "2031-01-01", issueDate: null }));
    await waitFor(() => expect(api.fetchPortalInfo).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getAllByText("Received, awaiting review")).toHaveLength(2));
  });

  it.each([
    [413, undefined, "File is larger than 15 MB."],
    [415, undefined, "Only PDF, PNG or JPG files are accepted."],
    [422, "https://x/problems/file-rejected", "This file was rejected"],
    [503, undefined, "We cannot check files right now"],
    [429, undefined, "Too many requests"],
  ])("maps upload status %s to a form message", async (status, type, message) => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValue(portalInfo());
    api.uploadPortalDocument.mockRejectedValue(new ApiError({ status, title: "t", type }));
    render(<PortalPage />);
    fireEvent.change(await screen.findByLabelText("File for W-9"), { target: { files: [pdf()] } });
    fireEvent.click(screen.getByRole("button", { name: "Upload W-9" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
  });

  it("maps the portal upload limit and refreshes into the closed state", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValueOnce(portalInfo());
    api.fetchPortalInfo.mockResolvedValueOnce(portalInfo({ acceptingUploads: false, remainingUploads: 0 }));
    api.uploadPortalDocument.mockRejectedValue(new ApiError({ status: 422, title: "t", type: "https://x/problems/portal-upload-limit" }));
    render(<PortalPage />);
    fireEvent.change(await screen.findByLabelText("File for W-9"), { target: { files: [pdf()] } });
    fireEvent.click(screen.getByRole("button", { name: "Upload W-9" }));
    expect(await screen.findByText("Uploads are closed")).toBeInTheDocument();
  });

  it("falls back to the invalid state when the link dies during an upload", async () => {
    openPortal(`#token=${TOKEN}`);
    api.fetchPortalInfo.mockResolvedValue(portalInfo());
    api.uploadPortalDocument.mockRejectedValue(new ApiError({ status: 404, title: "t" }));
    render(<PortalPage />);
    fireEvent.change(await screen.findByLabelText("File for W-9"), { target: { files: [pdf()] } });
    fireEvent.click(screen.getByRole("button", { name: "Upload W-9" }));
    expect(await screen.findByText("Link not valid")).toBeInTheDocument();
  });
});
