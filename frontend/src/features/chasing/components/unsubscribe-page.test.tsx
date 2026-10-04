import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { optOutInfo } from "../fixtures";
import { resetOptOutTokenForTests } from "../token";
import { UnsubscribePage } from "./unsubscribe-page";

const api = vi.hoisted(() => ({ fetchOptOutInfo: vi.fn(), confirmOptOut: vi.fn() }));
vi.mock("../api", () => api);

const TOKEN = "abcDEF1234567890_-optout";
const open = (hash: string) => window.history.replaceState(null, "", `/portal/unsubscribe${hash}`);
const notFound = () => new ApiError({ status: 404, title: "x", type: "https://x/problems/chasing-opt-out-invalid" });

describe("UnsubscribePage", () => {
  beforeEach(() => {
    resetOptOutTokenForTests();
    Object.values(api).forEach((fn) => fn.mockReset());
  });
  afterEach(() => window.history.replaceState(null, "", "/"));

  it("strips the token from the URL, loads info and does NOT change state on load", async () => {
    open(`#token=${TOKEN}`);
    const replace = vi.spyOn(window.history, "replaceState");
    api.fetchOptOutInfo.mockResolvedValue(optOutInfo());
    render(<UnsubscribePage />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading");
    expect(await screen.findByRole("heading", { name: "Unsubscribe Acme Plumbing?" })).toBeInTheDocument();
    expect(window.location.hash).toBe("");
    expect(replace).toHaveBeenCalledWith(null, "", "/portal/unsubscribe");
    expect(api.fetchOptOutInfo).toHaveBeenCalledWith(TOKEN);
    expect(api.confirmOptOut).not.toHaveBeenCalled();
    expect(screen.getByText(/Sunrise HOA uses VendorFlow/)).toBeInTheDocument();
    replace.mockRestore();
  });

  it("confirms with the button: pending state, then success", async () => {
    open(`#token=${TOKEN}`);
    api.fetchOptOutInfo.mockResolvedValue(optOutInfo());
    let finish: (value: unknown) => void = () => {};
    api.confirmOptOut.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    render(<UnsubscribePage />);
    fireEvent.click(await screen.findByRole("button", { name: "Unsubscribe from reminders" }));
    expect(await screen.findByRole("button", { name: "Unsubscribing…" })).toBeDisabled();
    expect(api.confirmOptOut).toHaveBeenCalledWith(TOKEN);
    finish(optOutInfo({ optedOut: true }));
    expect(await screen.findByText("You are unsubscribed")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows already unsubscribed without a button", async () => {
    open(`#token=${TOKEN}`);
    api.fetchOptOutInfo.mockResolvedValue(optOutInfo({ optedOut: true }));
    render(<UnsubscribePage />);
    expect(await screen.findByText("Already unsubscribed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Unsubscribe from reminders" })).not.toBeInTheDocument();
  });

  it("shows one generic message for any invalid link (404 or malformed token)", async () => {
    open(`#token=${TOKEN}`);
    api.fetchOptOutInfo.mockRejectedValue(notFound());
    const { unmount } = render(<UnsubscribePage />);
    expect(await screen.findByText(/This link is invalid or has expired/)).toBeInTheDocument();
    unmount();
    resetOptOutTokenForTests();
    open("#token=short");
    render(<UnsubscribePage />);
    expect(await screen.findByText(/This link is invalid or has expired/)).toBeInTheDocument();
    expect(api.fetchOptOutInfo).toHaveBeenCalledTimes(1);
  });

  it("asks to reopen the link when there is no token (reload)", async () => {
    open("");
    render(<UnsubscribePage />);
    expect(await screen.findByText(/Open the unsubscribe link from your email again/)).toBeInTheDocument();
    expect(api.fetchOptOutInfo).not.toHaveBeenCalled();
  });

  it("shows a retryable error for server problems on load and a message on confirm failure", async () => {
    open(`#token=${TOKEN}`);
    api.fetchOptOutInfo.mockRejectedValueOnce(new ApiError({ status: 500, title: "Something went wrong on our side. Please try again." }));
    render(<UnsubscribePage />);
    expect(await screen.findByText("We could not load this page")).toBeInTheDocument();
    api.fetchOptOutInfo.mockResolvedValue(optOutInfo());
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    api.confirmOptOut.mockRejectedValueOnce(new ApiError({ status: 429, title: "x" }));
    fireEvent.click(await screen.findByRole("button", { name: "Unsubscribe from reminders" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many requests");
    expect(screen.getByRole("button", { name: "Unsubscribe from reminders" })).toBeEnabled();
  });

  it("loads a new link pasted into the open tab (hashchange)", async () => {
    open(`#token=${TOKEN}`);
    api.fetchOptOutInfo.mockResolvedValueOnce(optOutInfo());
    render(<UnsubscribePage />);
    await screen.findByText("Unsubscribe Acme Plumbing?");
    api.fetchOptOutInfo.mockResolvedValueOnce(optOutInfo({ vendorName: "Other LLC" }));
    window.location.hash = "#token=zzzNEW1234567890_-optout";
    expect(await screen.findByText("Unsubscribe Other LLC?")).toBeInTheDocument();
    await waitFor(() => expect(window.location.hash).toBe(""));
    expect(api.fetchOptOutInfo).toHaveBeenLastCalledWith("zzzNEW1234567890_-optout");
  });
});
