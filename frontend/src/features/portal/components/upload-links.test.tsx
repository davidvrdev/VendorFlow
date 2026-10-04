import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { uploadLink } from "../fixtures";
import type { LinkRequirement } from "./send-upload-link-dialog";
import { SendUploadLinkDialog } from "./send-upload-link-dialog";
import { UploadLinksCard } from "./upload-links-card";

const api = vi.hoisted(() => ({ createUploadLink: vi.fn(), listUploadLinks: vi.fn(), revokeUploadLink: vi.fn() }));
vi.mock("../api", () => api);
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });

const requirements: LinkRequirement[] = [
  { documentTypeId: "t1", name: "Certificate of Insurance", status: "MISSING" },
  { documentTypeId: "t2", name: "W-9", status: "OK" },
  { documentTypeId: "t3", name: "Workers' Compensation", status: "EXPIRING" },
];

function renderDialog(vendorEmail: string | null = "v@acme.test", onCreated = vi.fn()) {
  render(
    <SendUploadLinkDialog open onOpenChange={vi.fn()} vendorId="v1" vendorName="Acme" vendorEmail={vendorEmail} requirements={requirements} onCreated={onCreated} />,
  );
  return onCreated;
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
});

describe("SendUploadLinkDialog", () => {
  it("preselects missing and expiring types with defaults 14 days / 20 uploads", () => {
    renderDialog();
    expect(screen.getByRole("checkbox", { name: "Certificate of Insurance" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Workers' Compensation" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "W-9" })).not.toBeChecked();
    expect(screen.getByLabelText("Expires in (days)")).toHaveValue(14);
    expect(screen.getByLabelText("Maximum uploads")).toHaveValue(20);
  });

  it("disables the email option with a hint when the vendor has no email", () => {
    renderDialog(null);
    expect(screen.getByRole("checkbox", { name: "Email the link to the vendor" })).toBeDisabled();
    expect(screen.getByText(/no email address/)).toBeInTheDocument();
  });

  it("validates: at least one type and numeric ranges", async () => {
    renderDialog();
    fireEvent.click(screen.getByRole("checkbox", { name: "Certificate of Insurance" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Workers' Compensation" }));
    fireEvent.change(screen.getByLabelText("Expires in (days)"), { target: { value: "45" } });
    fireEvent.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByText("Choose at least one document type.")).toBeInTheDocument();
    expect(screen.getByText("Expiry must be a whole number from 1 to 30.")).toBeInTheDocument();
    expect(api.createUploadLink).not.toHaveBeenCalled();
  });

  it("creates the link, shows the URL once with a copy button, and notifies the list", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    api.createUploadLink.mockResolvedValue({ link: uploadLink(), url: "https://app.test/portal#token=SECRET123", emailQueued: true });
    const onCreated = renderDialog();
    fireEvent.click(screen.getByRole("checkbox", { name: /Email the link to/ }));
    fireEvent.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByLabelText("Upload link")).toHaveValue("https://app.test/portal#token=SECRET123");
    expect(api.createUploadLink).toHaveBeenCalledWith("v1", { documentTypeIds: ["t1", "t3"], expiresInDays: 14, maxUploads: 20, sendEmail: true });
    expect(onCreated).toHaveBeenCalled();
    expect(screen.getByText(/shown only once/)).toBeInTheDocument();
    expect(screen.getByText(/email with the link is on its way/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("https://app.test/portal#token=SECRET123"));
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("shows server errors without losing the form", async () => {
    api.createUploadLink.mockRejectedValue(new ApiError({ status: 422, title: "t", type: "https://x/problems/vendor-no-email" }));
    renderDialog();
    fireEvent.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("no email address");
    expect(screen.getByRole("button", { name: "Create link" })).toBeEnabled();
  });
});

describe("UploadLinksCard", () => {
  const props = { vendorId: "v1", vendorName: "Acme", vendorEmail: "v@acme.test", vendorActive: true, requirements };
  const pageOf = (items: ReturnType<typeof uploadLink>[], extra = {}) => ({ items, page: 0, size: 10, totalItems: items.length, totalPages: 1, ...extra });

  it("shows loading, then rows with status, creator, expiry and uses", async () => {
    api.listUploadLinks.mockResolvedValue(pageOf([uploadLink(), uploadLink({ id: "l2", status: "REVOKED" })]));
    render(<UploadLinksCard {...props} role="MEMBER" />);
    expect(screen.getByRole("status", { name: "Loading upload links" })).toBeInTheDocument();
    const list = await screen.findByRole("list", { name: "Upload links" });
    expect(within(list).getByText("Active")).toBeInTheDocument();
    expect(within(list).getByText("Revoked")).toBeInTheDocument();
    expect(within(list).getAllByText(/by Ana Owner/)).toHaveLength(2);
    expect(within(list).getAllByText(/Uploads: 1 of 20/)).toHaveLength(2);
    // Only the active link can be revoked.
    expect(within(list).getAllByRole("button", { name: /Revoke/ })).toHaveLength(1);
  });

  it("shows empty and error states (with retry)", async () => {
    api.listUploadLinks.mockRejectedValueOnce(new ApiError({ status: 500, title: "Something went wrong on our side. Please try again." }));
    api.listUploadLinks.mockResolvedValueOnce(pageOf([]));
    render(<UploadLinksCard {...props} role="OWNER" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("No upload links yet.")).toBeInTheDocument();
  });

  it("hides create and revoke for a VIEWER but still lists", async () => {
    api.listUploadLinks.mockResolvedValue(pageOf([uploadLink()]));
    render(<UploadLinksCard {...props} role="VIEWER" />);
    await screen.findByRole("list", { name: "Upload links" });
    expect(screen.queryByRole("button", { name: "Send upload link" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Revoke/ })).not.toBeInTheDocument();
  });

  it("paginates", async () => {
    api.listUploadLinks.mockResolvedValueOnce(pageOf([uploadLink()], { totalItems: 12, totalPages: 2 }));
    api.listUploadLinks.mockResolvedValueOnce(pageOf([uploadLink({ id: "l9" })], { page: 1, totalItems: 12, totalPages: 2 }));
    render(<UploadLinksCard {...props} role="MEMBER" />);
    fireEvent.click(await screen.findByRole("button", { name: "Next" }));
    await screen.findByText(/Page 2 of 2/);
    expect(api.listUploadLinks).toHaveBeenLastCalledWith("v1", 1);
  });

  it("confirms before revoking, then reloads", async () => {
    api.listUploadLinks.mockResolvedValue(pageOf([uploadLink()]));
    api.revokeUploadLink.mockResolvedValue(uploadLink({ status: "REVOKED" }));
    render(<UploadLinksCard {...props} role="MEMBER" />);
    fireEvent.click(await screen.findByRole("button", { name: /Revoke link created/ }));
    const dialog = await screen.findByRole("alertdialog");
    expect(api.revokeUploadLink).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Revoke link" }));
    await waitFor(() => expect(api.revokeUploadLink).toHaveBeenCalledWith("v1", "l1"));
    await waitFor(() => expect(api.listUploadLinks).toHaveBeenCalledTimes(2));
  });

  it("disables sending for an inactive vendor", async () => {
    api.listUploadLinks.mockResolvedValue(pageOf([]));
    render(<UploadLinksCard {...props} vendorActive={false} role="MEMBER" />);
    expect(await screen.findByRole("button", { name: "Send upload link" })).toBeDisabled();
  });
});
