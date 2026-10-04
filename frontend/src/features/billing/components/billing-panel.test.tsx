import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import type { Subscription } from "../schemas";
import { BillingPanel, redirectTo } from "./billing-panel";

const refresh = vi.fn();
let query = "";
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }), useSearchParams: () => new URLSearchParams(query) }));

const checkout = vi.fn();
const portal = vi.fn();
vi.mock("../api", () => ({ createCheckoutSession: () => checkout(), createPortalSession: () => portal() }));

const refetch = vi.fn();
let state: { subscription: Subscription | null; loading: boolean; error: boolean };
vi.mock("./subscription-provider", () => ({ useSubscription: () => ({ ...state, refetch }) }));

const trial: Subscription = { status: "TRIALING", plan: "Pro", trialEndsAt: "2099-01-01T00:00:00Z", currentPeriodEnd: null, cancelAtPeriodEnd: false, readOnly: false, canManage: true };

describe("BillingPanel", () => {
  beforeEach(() => {
    query = "";
    state = { subscription: trial, loading: false, error: false };
    checkout.mockReset();
    portal.mockReset();
    refetch.mockReset();
    refresh.mockReset();
  });

  it("shows loading", () => {
    state = { subscription: null, loading: true, error: false };
    render(<BillingPanel />);
    expect(screen.getByLabelText("Loading billing")).toHaveAttribute("aria-busy", "true");
  });

  it("shows an error with retry", () => {
    state = { subscription: null, loading: false, error: true };
    render(<BillingPanel />);
    expect(screen.getByText("We could not load billing")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(refetch).toHaveBeenCalled();
  });

  it("owner on trial sees status text and Subscribe", () => {
    render(<BillingPanel />);
    expect(screen.getByText("Trial")).toBeInTheDocument();
    expect(screen.getByText("Trial ends")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Subscribe" })).toBeEnabled();
  });

  it("active owner sees Manage billing and the cancel date", () => {
    state.subscription = { ...trial, status: "ACTIVE", trialEndsAt: null, currentPeriodEnd: "2030-03-01T12:00:00Z", cancelAtPeriodEnd: true };
    render(<BillingPanel />);
    expect(screen.getByRole("button", { name: "Manage billing" })).toBeInTheDocument();
    expect(screen.getByText("Cancels on")).toBeInTheDocument();
  });

  it("non-owner sees Ask an owner and no buttons", () => {
    state.subscription = { ...trial, canManage: false };
    render(<BillingPanel />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText(/Ask an owner/)).toBeInTheDocument();
  });

  it("503 shows the friendly not-configured message and re-enables the button", async () => {
    checkout.mockRejectedValue(new ApiError({ status: 503, title: "Billing not configured" }));
    render(<BillingPanel />);
    fireEvent.click(screen.getByRole("button", { name: "Subscribe" }));
    expect(await screen.findByText(/Billing isn't configured yet/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Subscribe" })).toBeEnabled());
  });

  it("refuses to follow a non-Stripe redirect url", async () => {
    checkout.mockResolvedValue("https://evil.example.com/pay");
    render(<BillingPanel />);
    fireEvent.click(screen.getByRole("button", { name: "Subscribe" }));
    expect(await screen.findByText(/unexpected billing address/)).toBeInTheDocument();
  });

  it("disables the button while the request is pending", () => {
    checkout.mockReturnValue(new Promise(() => {}));
    render(<BillingPanel />);
    fireEvent.click(screen.getByRole("button", { name: "Subscribe" }));
    expect(screen.getByRole("button", { name: "Redirecting…" })).toBeDisabled();
  });

  it("?checkout=success shows the notice and refetches once", () => {
    query = "checkout=success";
    render(<BillingPanel />);
    expect(screen.getByText("Payment received")).toBeInTheDocument();
    expect(refetch).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("?checkout=canceled shows the canceled notice without refetching", () => {
    query = "checkout=canceled";
    render(<BillingPanel />);
    expect(screen.getByText("Checkout canceled")).toBeInTheDocument();
    expect(refetch).not.toHaveBeenCalled();
  });
});

describe("redirectTo", () => {
  it("assigns only allowed urls", () => {
    const assign = vi.fn();
    expect(redirectTo("https://checkout.stripe.com/x", assign)).toBe(true);
    expect(redirectTo("https://evil.com", assign)).toBe(false);
    expect(assign).toHaveBeenCalledTimes(1);
  });
});
