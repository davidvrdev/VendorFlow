import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { Subscription } from "../schemas";
import { SubscriptionBannerView } from "./subscription-banner";

vi.mock("next/link", () => ({ default: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a> }));

const NOW = new Date("2030-01-10T12:00:00Z");
const base: Subscription = { status: "ACTIVE", plan: "Pro", trialEndsAt: null, currentPeriodEnd: null, cancelAtPeriodEnd: false, readOnly: false, canManage: true };
const renderBanner = (over: Partial<Subscription>) => render(<SubscriptionBannerView subscription={{ ...base, ...over }} now={NOW} />);

describe("SubscriptionBannerView", () => {
  it("renders nothing for a healthy subscription", () => {
    const { container } = renderBanner({});
    expect(container).toBeEmptyDOMElement();
  });
  it("trial ending: polite status with day count and billing link", () => {
    renderBanner({ status: "TRIALING", trialEndsAt: "2030-01-13T12:00:00Z" });
    expect(screen.getByRole("status")).toHaveTextContent("Your trial ends in 3 days.");
    expect(screen.getByRole("link", { name: "View plan" })).toHaveAttribute("href", "/settings/billing");
  });
  it("past due: warning status", () => {
    renderBanner({ status: "PAST_DUE" });
    expect(screen.getByRole("status")).toHaveTextContent("payment failed");
  });
  it("read-only: assertive alert, link for owners", () => {
    renderBanner({ readOnly: true, status: "CANCELED" });
    expect(screen.getByRole("alert")).toHaveTextContent("Your workspace is read-only. Subscribe to make changes.");
    expect(screen.getByRole("link", { name: "Go to billing" })).toBeInTheDocument();
  });
  it("non-owners get no link, only a pointer to an owner", () => {
    renderBanner({ readOnly: true, status: "CANCELED", canManage: false });
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("Ask a workspace owner.")).toBeInTheDocument();
  });
});
