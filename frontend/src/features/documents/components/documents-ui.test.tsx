import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RequirementsCard } from "@/features/vendors/components/requirements-card";
import type { DocumentType, VendorDetail } from "@/features/vendors/types";
import { doc } from "../test-fixtures";
import { DocumentTypeForm } from "./document-type-form";
import { UploadDialog } from "./upload-dialog";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh, push: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });

const types: DocumentType[] = [
  { id: "t1", code: "COI", name: "Certificate of Insurance", hasExpiration: true, requiredByDefault: true, sortOrder: 1 },
  { id: "t2", code: "W9", name: "W-9", hasExpiration: false, requiredByDefault: true, sortOrder: 2 },
];

const vendor: Pick<VendorDetail, "id" | "companyName" | "requirements" | "otherDocuments"> = {
  id: "v-1",
  companyName: "Acme",
  requirements: [
    { documentTypeId: "t1", code: "COI", name: "Certificate of Insurance", hasExpiration: true, currentDocument: doc({ originalFilename: "coi-2030.pdf" }) },
    { documentTypeId: "t2", code: "W9", name: "W-9", hasExpiration: false, currentDocument: null },
    // Type 't9' is not in the active list: an inactive requirement.
    { documentTypeId: "t9", code: "OLD", name: "Legacy Form", hasExpiration: false, currentDocument: null },
  ],
  otherDocuments: [],
};

function openMenu(name: string) {
  const trigger = screen.getByRole("button", { name });
  // Radix opens the menu on Enter/Space; jsdom has no pointer-capture support for the pointer path.
  fireEvent.keyDown(trigger, { key: "Enter" });
}

describe("RequirementsCard as a documents table", () => {
  it("shows filename, upload info, Pending review and a Missing state", () => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role="VIEWER" />);
    const table = screen.getByRole("table", { name: /Required documents for Acme/ });
    expect(within(table).getByText("coi-2030.pdf")).toBeInTheDocument();
    expect(within(table).getByText("Pending review")).toBeInTheDocument();
    expect(within(table).getAllByText("Missing")).toHaveLength(2);
    expect(within(table).getByText("Inactive type: ignored for compliance.")).toBeInTheDocument();
  });

  it("shows the rejection note next to the Rejected status", () => {
    const rejected = { ...vendor, requirements: [{ ...vendor.requirements[0], currentDocument: doc({ reviewStatus: "REJECTED", reviewNote: "Illegible scan" }) }] };
    render(<RequirementsCard vendor={rejected} documentTypes={types} role="VIEWER" />);
    expect(screen.getByText("Rejected")).toBeInTheDocument();
    expect(screen.getByText("Note: Illegible scan")).toBeInTheDocument();
  });

  it("VIEWER: only Download, and no rows menu where nothing is possible", () => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role="VIEWER" />);
    expect(screen.queryByRole("button", { name: "Upload document" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Actions for W-9" })).not.toBeInTheDocument();
    openMenu("Actions for Certificate of Insurance");
    const items = screen.getAllByRole("menuitem").map((item) => item.textContent);
    expect(items).toEqual(["Download"]);
    expect(screen.getByRole("menuitem", { name: "Download" })).toHaveAttribute("href", "/api/v1/documents/d1/download");
  });

  it("MEMBER: replace, download, approve, reject, edit dates; no archive", () => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role="MEMBER" />);
    expect(screen.getByRole("button", { name: "Upload document" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Actions for W-9" })).toBeInTheDocument();
    openMenu("Actions for Certificate of Insurance");
    expect(screen.getAllByRole("menuitem").map((item) => item.textContent)).toEqual(["Replace", "Download", "Approve", "Reject…", "Edit dates"]);
  });

  it("ADMIN: also archive", () => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role="ADMIN" />);
    openMenu("Actions for Certificate of Insurance");
    expect(screen.getAllByRole("menuitem").map((item) => item.textContent)).toContain("Archive…");
  });

  it("offers no upload for the inactive requirement", () => {
    render(<RequirementsCard vendor={vendor} documentTypes={types} role="OWNER" />);
    expect(screen.queryByRole("button", { name: "Actions for Legacy Form" })).not.toBeInTheDocument();
  });
});

describe("UploadDialog", () => {
  beforeEach(() => {
    refresh.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  function renderDialog(current: Record<string, ReturnType<typeof doc>> = {}, initialTypeId = "t2") {
    render(
      <UploadDialog open onOpenChange={vi.fn()} vendorId="v-1" companyName="Acme" documentTypes={types} initialTypeId={initialTypeId} currentByType={current} />,
    );
  }

  const pdf = () => new File(["%PDF-1.4 test"], "w9.pdf", { type: "application/pdf" });
  const problem = (status: number, body: object = {}) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });

  function choose(file: File) {
    fireEvent.change(screen.getByLabelText("File"), { target: { files: [file] } });
  }

  it("has a labelled file input restricted to the accepted extensions", async () => {
    renderDialog();
    expect(await screen.findByRole("dialog", { name: "Upload document" })).toBeInTheDocument();
    expect(screen.getByLabelText("File")).toHaveAttribute("accept", ".pdf,.png,.jpg,.jpeg");
  });

  it("warns that an upload replaces the current document", async () => {
    renderDialog({ t2: doc() });
    expect(await screen.findByText("This will replace the current document (kept in history).")).toBeInTheDocument();
  });

  it("shows the selected file name and size and rejects a wrong extension before sending", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderDialog();
    choose(new File(["x"], "notes.exe"));
    expect(await screen.findByText("Only PDF, PNG or JPG files are accepted.")).toBeInTheDocument();
    expect(screen.getByTestId("selected-file")).toHaveTextContent("notes.exe");
    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    await waitFor(() => expect(screen.getByLabelText("File")).toHaveAttribute("aria-invalid", "true"));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("requires the expiration date for types that expire", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderDialog({}, "t1");
    choose(pdf());
    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    expect(await screen.findByText("Enter the expiration date for this document type.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("posts multipart FormData without a manual Content-Type, then refreshes", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 201, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    renderDialog();
    choose(pdf());
    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/vendors/v-1/documents");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    const form = init.body as FormData;
    expect(form.get("documentTypeId")).toBe("t2");
    expect((form.get("file") as File).name).toBe("w9.pdf");
    expect(form.has("expirationDate")).toBe(false);
    expect(new Headers(init.headers).has("Content-Type")).toBe(false);
    expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("t");
  });

  it.each([
    [413, "File is larger than 15 MB."],
    [415, "Only PDF, PNG or JPG files are accepted."],
    [422, "File rejected."],
  ])("shows the mapped message for a %i response and stays open", async (status, text) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(status, { title: "Whatever" })));
    renderDialog();
    choose(pdf());
    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("maps 400 field errors onto the date field", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(problem(400, { title: "Validation failed", errors: [{ field: "issueDate", message: "Issue date is out of range." }] })),
    );
    renderDialog();
    choose(pdf());
    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    expect(await screen.findByText("Issue date is out of range.")).toBeInTheDocument();
    expect(screen.getByLabelText("Issue date")).toHaveAttribute("aria-invalid", "true");
  });
});

describe("DocumentTypeForm", () => {
  it("validates the name before submitting", async () => {
    const onSubmit = vi.fn();
    render(<DocumentTypeForm idPrefix="t" submitLabel="Add document type" pendingLabel="Adding…" onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "Add document type" }));
    expect(await screen.findByText("Enter a name for the document type.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits trimmed values including the toggles", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<DocumentTypeForm idPrefix="t" submitLabel="Add document type" pendingLabel="Adding…" onSubmit={onSubmit} resetOnSuccess />);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "  Pollution Liability " } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Has an expiration date" }));
    fireEvent.click(screen.getByRole("button", { name: "Add document type" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toEqual({ name: "Pollution Liability", hasExpiration: true, requiredByDefault: false });
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue(""));
  });

  it("turns a 409 into a field error on the name", async () => {
    const { ApiError } = await import("@/lib/api/errors");
    const onSubmit = vi.fn().mockRejectedValue(new ApiError({ status: 409, title: "Document type already exists" }));
    render(<DocumentTypeForm idPrefix="t" submitLabel="Add document type" pendingLabel="Adding…" onSubmit={onSubmit} />);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "W-9" } });
    fireEvent.click(screen.getByRole("button", { name: "Add document type" }));
    expect(await screen.findByText("A document type with this name already exists.")).toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
  });
});
