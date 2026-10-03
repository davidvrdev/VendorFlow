import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { VENDOR_COMPLIANCES, type VendorCompliance } from "@/features/compliance/types";
import { VendorComplianceBadge } from "./vendor-compliance-badge";

const LABELS: Record<VendorCompliance, string> = { COMPLIANT: "Compliant", ATTENTION: "Needs attention", NON_COMPLIANT: "Non-compliant" };

describe("VendorComplianceBadge", () => {
  it.each(VENDOR_COMPLIANCES)("renders text and a decorative icon for %s", (status) => {
    const { container } = render(<VendorComplianceBadge status={status} requirementCount={3} />);
    expect(screen.getByText(LABELS[status])).toBeVisible();
    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("says 'No requirements set' instead of Compliant when nothing is required", () => {
    render(<VendorComplianceBadge status="COMPLIANT" requirementCount={0} />);
    expect(screen.getByText("No requirements set")).toBeVisible();
    expect(screen.queryByText("Compliant")).not.toBeInTheDocument();
  });
});
