import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Table, TableBody, TableRow } from "@/components/ui/table";
import { DocumentCells } from "@/features/documents/components/document-cells";
import { doc } from "@/features/documents/test-fixtures";
import { UploadLinkStatusBadge } from "./upload-link-status-badge";

const renderCells = (source?: "STAFF" | "PORTAL") =>
  render(
    <Table>
      <TableBody>
        <TableRow>
          <DocumentCells document={doc({ source })} />
        </TableRow>
      </TableBody>
    </Table>,
  );

describe("Via portal badge", () => {
  it("appears for portal uploads only", () => {
    renderCells("PORTAL");
    expect(screen.getByText("Via portal")).toBeInTheDocument();
  });
  it("is absent for staff uploads and legacy payloads", () => {
    renderCells("STAFF");
    expect(screen.queryByText("Via portal")).not.toBeInTheDocument();
  });
});

describe("UploadLinkStatusBadge", () => {
  it.each([["ACTIVE", "Active"], ["EXPIRED", "Expired"], ["REVOKED", "Revoked"], ["EXHAUSTED", "Limit reached"]] as const)("labels %s", (status, label) => {
    render(<UploadLinkStatusBadge status={status} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
