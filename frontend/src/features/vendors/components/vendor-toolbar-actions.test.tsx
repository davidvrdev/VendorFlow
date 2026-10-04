import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DEFAULT_LIST_STATE } from "../list-state";
import { VendorToolbarActions } from "./vendor-toolbar-actions";

const base = { state: DEFAULT_LIST_STATE, totalItems: 5, canCreate: true, canImport: true };

describe("VendorToolbarActions", () => {
  it("export is a plain download link for every role", () => {
    render(<VendorToolbarActions {...base} canCreate={false} canImport={false} />);
    const link = screen.getByRole("link", { name: "Export CSV" });
    expect(link).toHaveAttribute("href", "/api/v1/vendors/export.csv?status=ACTIVE");
    expect(link).toHaveAttribute("download");
    expect(screen.queryByRole("link", { name: "Import CSV" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Add vendor" })).not.toBeInTheDocument();
  });
  it("shows Import CSV only when permitted", () => {
    render(<VendorToolbarActions {...base} />);
    expect(screen.getByRole("link", { name: "Import CSV" })).toHaveAttribute("href", "/vendors/import");
  });
  it("disables export with an explanation above the 10,000-row cap", () => {
    render(<VendorToolbarActions {...base} totalItems={10_001} />);
    expect(screen.queryByRole("link", { name: "Export CSV" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Export CSV" })).toBeDisabled();
    expect(screen.getByText(/Narrow the filters/)).toBeInTheDocument();
  });
});
