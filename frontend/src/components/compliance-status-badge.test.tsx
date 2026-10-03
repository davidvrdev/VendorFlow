import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ComplianceStatusBadge } from "./compliance-status-badge";
import { COMPLIANCE_STATUSES, type ComplianceStatus } from "@/features/compliance/types";

const LABELS: Record<ComplianceStatus, string> = {
  MISSING: "Missing",
  OK: "OK",
  EXPIRING: "Expiring soon",
  EXPIRED: "Expired",
  REVIEW_REQUIRED: "Needs review",
};

describe("ComplianceStatusBadge", () => {
  it.each(COMPLIANCE_STATUSES)("renders the visible label for %s", (status) => {
    render(<ComplianceStatusBadge status={status} />);
    expect(screen.getByText(LABELS[status])).toBeVisible();
  });

  it.each(COMPLIANCE_STATUSES)("hides the icon from assistive tech for %s", (status) => {
    const { container } = render(<ComplianceStatusBadge status={status} />);
    const icon = container.querySelector("svg");
    expect(icon).not.toBeNull();
    expect(icon).toHaveAttribute("aria-hidden", "true");
  });
});
