import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { chase, chasePage, chasingState } from "../fixtures";
import { VendorChasingCard } from "./vendor-chasing-card";

const api = vi.hoisted(() => ({ getVendorChasing: vi.fn(), setVendorChasingPaused: vi.fn(), listChases: vi.fn() }));
vi.mock("../api", () => api);
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  api.listChases.mockResolvedValue(chasePage());
});

const renderCard = (role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER" = "MEMBER", organizationEnabled: boolean | null = true) =>
  render(<VendorChasingCard vendorId="v1" role={role} organizationEnabled={organizationEnabled} />);

describe("VendorChasingCard", () => {
  it("shows a loading state, then status, attempts and dates, and the history row", async () => {
    api.getVendorChasing.mockResolvedValue(chasingState());
    renderCard();
    expect(screen.getByRole("status", { name: "Loading follow-up status" })).toBeInTheDocument();
    expect(await screen.findByText("Active")).toBeInTheDocument();
    expect(screen.getByText("1 of 4")).toBeInTheDocument();
    expect(screen.getByText("Jan 10, 2030, 2:00 PM UTC")).toBeInTheDocument();
    expect(screen.getByText("Jan 17, 2030, 2:00 PM UTC")).toBeInTheDocument();
    const row = (await screen.findByRole("list", { name: "Follow-up history" })).querySelector("li") as HTMLElement;
    expect(row).toHaveTextContent("Jan 10, 2030 · Follow-up 1");
    expect(row).toHaveTextContent("Certificate of Insurance (missing)");
    expect(row).toHaveTextContent("Link active");
    expect(row).toHaveTextContent("Email sent");
  });

  it.each([
    ["EXHAUSTED", "Follow-ups used up"],
    ["NO_EMAIL", "No email address"],
    ["IDLE", "Nothing to chase"],
  ] as const)("renders %s as text", async (status, label) => {
    api.getVendorChasing.mockResolvedValue(chasingState({ status, nextChaseAt: null }));
    renderCard();
    expect(await screen.findByText(label)).toBeInTheDocument();
    expect(screen.getByText("Not scheduled")).toBeInTheDocument();
  });

  it("pauses (no confirmation), refetches and shows the toast path", async () => {
    api.getVendorChasing.mockResolvedValueOnce(chasingState());
    api.setVendorChasingPaused.mockResolvedValue(chasingState({ paused: true, status: "PAUSED", pausedReason: "MANUAL" }));
    api.getVendorChasing.mockResolvedValueOnce(chasingState({ paused: true, status: "PAUSED", pausedReason: "MANUAL", nextChaseAt: null }));
    renderCard();
    fireEvent.click(await screen.findByRole("button", { name: "Pause follow-ups" }));
    expect(api.setVendorChasingPaused).toHaveBeenCalledWith("v1", true);
    expect(await screen.findByRole("button", { name: "Resume follow-ups" })).toBeEnabled();
    expect(screen.getByText("Paused")).toBeInTheDocument();
  });

  it("shows Vendor unsubscribed and disables resume for an opt-out", async () => {
    api.getVendorChasing.mockResolvedValue(chasingState({ paused: true, status: "PAUSED", pausedReason: "OPT_OUT", nextChaseAt: null }));
    renderCard();
    expect(await screen.findByText("Vendor unsubscribed")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resume follow-ups" })).toBeDisabled();
  });

  it("handles 422 vendor-opted-out on resume with a clear message and refetches", async () => {
    api.getVendorChasing.mockResolvedValueOnce(chasingState({ paused: true, status: "PAUSED", pausedReason: "MANUAL", nextChaseAt: null }));
    api.setVendorChasingPaused.mockRejectedValue(new ApiError({ status: 422, title: "x", type: "https://x/problems/vendor-opted-out" }));
    api.getVendorChasing.mockResolvedValueOnce(chasingState({ paused: true, status: "PAUSED", pausedReason: "OPT_OUT", nextChaseAt: null }));
    renderCard();
    fireEvent.click(await screen.findByRole("button", { name: "Resume follow-ups" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This vendor unsubscribed from reminders.");
    await waitFor(() => expect(screen.getByRole("button", { name: "Resume follow-ups" })).toBeDisabled());
    expect(screen.getByText("Vendor unsubscribed")).toBeInTheDocument();
  });

  it("hides Pause/Resume for viewers", async () => {
    api.getVendorChasing.mockResolvedValue(chasingState());
    renderCard("VIEWER");
    await screen.findByText("Active");
    expect(screen.queryByRole("button", { name: /follow-ups/ })).not.toBeInTheDocument();
  });

  it("hints with a settings link for owners/admins when the organization has chasing off, plain text for members", async () => {
    api.getVendorChasing.mockResolvedValue(chasingState({ status: "IDLE", attempts: 0, nextChaseAt: null, lastChasedAt: null }));
    const { unmount } = renderCard("ADMIN", false);
    expect(await screen.findByRole("link", { name: "Turn them on in settings" })).toHaveAttribute("href", "/settings/chasing");
    unmount();
    renderCard("MEMBER", false);
    await screen.findByText("Nothing to chase");
    expect(screen.queryByRole("link", { name: "Turn them on in settings" })).not.toBeInTheDocument();
    expect(screen.getByText(/Ask an owner or admin/)).toBeInTheDocument();
  });

  it("shows an error with retry for the state and for the history, and an empty history", async () => {
    api.getVendorChasing.mockRejectedValueOnce(new ApiError({ status: 500, title: "Something went wrong on our side. Please try again." }));
    api.listChases.mockRejectedValueOnce(new ApiError({ status: 500, title: "History broke" }));
    renderCard();
    expect(await screen.findByText(/Something went wrong on our side/)).toBeInTheDocument();
    expect(await screen.findByText(/History broke/)).toBeInTheDocument();

    api.getVendorChasing.mockResolvedValue(chasingState({ status: "IDLE", attempts: 0 }));
    api.listChases.mockResolvedValue(chasePage([]));
    const retries = screen.getAllByRole("button", { name: "Try again" });
    retries.forEach((button) => fireEvent.click(button));
    expect(await screen.findByText("No follow-ups have been sent yet.")).toBeInTheDocument();
  });

  it("paginates the history", async () => {
    api.getVendorChasing.mockResolvedValue(chasingState());
    api.listChases.mockResolvedValueOnce(chasePage([chase()], { totalItems: 12, totalPages: 2 }));
    api.listChases.mockResolvedValueOnce(chasePage([chase({ id: "c2", attempt: 2 })], { page: 1, totalItems: 12, totalPages: 2 }));
    renderCard();
    expect(await screen.findByText("Page 1 of 2 · 12 follow-ups")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 2 of 2 · 12 follow-ups")).toBeInTheDocument();
    expect(api.listChases).toHaveBeenLastCalledWith("v1", 1);
  });
});
