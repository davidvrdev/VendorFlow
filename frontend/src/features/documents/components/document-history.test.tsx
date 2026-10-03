import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { doc } from "../test-fixtures";
import { DocumentHistory } from "./document-history";

afterEach(() => vi.unstubAllGlobals());

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("DocumentHistory", () => {
  it("loads on demand with includeHistory=true and lists only superseded/archived documents with download links", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json([
        doc({ id: "cur", state: "CURRENT", originalFilename: "current.pdf" }),
        doc({ id: "old", state: "SUPERSEDED", originalFilename: "old.png" }),
        doc({ id: "arch", state: "ARCHIVED", originalFilename: "archived.pdf" }),
      ]),
    );
    vi.stubGlobal("fetch", fetchMock);
    render(<DocumentHistory vendorId="v-1" version="a" />);
    expect(fetchMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Show document history" }));
    expect(await screen.findByText("Superseded")).toBeInTheDocument();
    expect(screen.getByText("Archived")).toBeInTheDocument();
    expect(screen.queryByText("current.pdf")).not.toBeInTheDocument();
    expect((fetchMock.mock.calls[0] as [string])[0]).toBe("/api/v1/vendors/v-1/documents?includeHistory=true");
    expect(screen.getAllByRole("link", { name: /Download/ }).map((a) => a.getAttribute("href"))).toEqual([
      "/api/v1/documents/old/download",
      "/api/v1/documents/arch/download",
    ]);
  });

  it("says so when there is no history, and shows an error with retry", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json([])));
    const { unmount } = render(<DocumentHistory vendorId="v-1" version="a" />);
    fireEvent.click(screen.getByRole("button", { name: "Show document history" }));
    expect(await screen.findByText("No replaced or archived documents yet.")).toBeInTheDocument();
    unmount();

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    render(<DocumentHistory vendorId="v-1" version="a" />);
    fireEvent.click(screen.getByRole("button", { name: "Show document history" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/could not reach the server/);
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
