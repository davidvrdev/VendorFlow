import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import type { ImportPreview } from "../types";
import { ImportWizard } from "./import-wizard";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh, push: vi.fn() }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
const previewImport = vi.fn();
const commitImport = vi.fn();
vi.mock("../api", () => ({
  TEMPLATE_URL: "/api/v1/vendors/import/template.csv",
  previewImport: (file: File) => previewImport(file),
  commitImport: (id: string) => commitImport(id),
}));

const good: ImportPreview = {
  importId: "i-1",
  expiresAt: "2030-01-01T10:00:00Z",
  summary: { total: 3, create: 2, update: 1, unchanged: 0, error: 0 },
  rows: [
    { rowNumber: 1, companyName: "Alpha", action: "CREATE", changes: [], errors: [] },
    { rowNumber: 2, companyName: "Beta", action: "CREATE", changes: [], errors: [] },
    { rowNumber: 3, companyName: "Gamma", action: "UPDATE", changes: ["phone"], errors: [] },
  ],
};
const bad: ImportPreview = {
  importId: "i-2",
  expiresAt: "2030-01-01T10:00:00Z",
  summary: { total: 1, create: 0, update: 0, unchanged: 0, error: 1 },
  rows: [{ rowNumber: 1, companyName: "Delta", action: "ERROR", changes: [], errors: [{ field: "email", message: "Invalid email" }] }],
};

function chooseFile(name = "vendors.csv") {
  const input = screen.getByLabelText("CSV file");
  fireEvent.change(input, { target: { files: [new File(["company_name\nAlpha\n"], name, { type: "text/csv" })] } });
}

describe("ImportWizard", () => {
  beforeEach(() => {
    previewImport.mockReset();
    commitImport.mockReset();
    refresh.mockReset();
  });

  it("rejects a non-csv file before uploading", () => {
    render(<ImportWizard />);
    chooseFile("vendors.xlsx");
    fireEvent.click(screen.getByRole("button", { name: "Upload & preview" }));
    expect(screen.getByText("Only .csv files are accepted.")).toBeInTheDocument();
    expect(previewImport).not.toHaveBeenCalled();
  });

  it("shows a mapped message when the server rejects the upload", async () => {
    previewImport.mockRejectedValue(new ApiError({ status: 413, title: "Payload too large" }));
    render(<ImportWizard />);
    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "Upload & preview" }));
    expect(await screen.findByText("The file is larger than 1 MB.")).toBeInTheDocument();
  });

  it("an error preview disables commit and lets the user start over", async () => {
    previewImport.mockResolvedValue(bad);
    render(<ImportWizard />);
    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "Upload & preview" }));
    expect(await screen.findByText("Fix the errors in your file and upload it again.")).toBeInTheDocument();
    expect(screen.getByText("email: Invalid email")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import vendors" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Upload a different file" }));
    expect(screen.getByLabelText("CSV file")).toBeInTheDocument();
  });

  it("previews, confirms and commits", async () => {
    previewImport.mockResolvedValue(good);
    commitImport.mockResolvedValue({ created: 2, updated: 1, unchanged: 0 });
    render(<ImportWizard />);
    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "Upload & preview" }));
    expect(await screen.findByText("2 to create")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^Update/ }));
    expect(screen.queryByText("Alpha")).not.toBeInTheDocument();
    expect(screen.getByText("Gamma")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Import vendors" }));
    expect(await screen.findByText("Create 2 vendors and update 1 vendor?")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Import vendors" }).at(-1)!);
    expect(await screen.findByText("2 vendors created, 1 vendor updated, 0 unchanged.")).toBeInTheDocument();
    expect(commitImport).toHaveBeenCalledWith("i-1");
    expect(refresh).toHaveBeenCalled();
    expect(screen.getByRole("link", { name: "Go to vendors" })).toHaveAttribute("href", "/vendors");
  });

  it("a 409 data-changed commit keeps the dialog message and blocks the preview", async () => {
    previewImport.mockResolvedValue(good);
    commitImport.mockRejectedValue(new ApiError({ status: 409, title: "Data changed since preview" }));
    render(<ImportWizard />);
    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: "Upload & preview" }));
    await screen.findByText("2 to create");
    fireEvent.click(screen.getByRole("button", { name: "Import vendors" }));
    await screen.findByText("Create 2 vendors and update 1 vendor?");
    fireEvent.click(screen.getAllByRole("button", { name: "Import vendors" }).at(-1)!);
    await waitFor(() => expect(screen.getAllByText(/vendor data changed since this preview/).length).toBeGreaterThan(0));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Import vendors" })).toBeDisabled());
  });
});
