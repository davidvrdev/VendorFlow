import { act, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, SUBSCRIPTION_INACTIVE_EVENT } from "@/lib/api/client";
import type { Subscription } from "../schemas";
import { SubscriptionProvider, useSubscription } from "./subscription-provider";

const toastError = vi.fn();
vi.mock("sonner", () => ({ toast: { error: (...a: unknown[]) => toastError(...a), success: vi.fn() } }));
const fetchSubscriptionClient = vi.fn();
vi.mock("../api", () => ({ fetchSubscriptionClient: () => fetchSubscriptionClient() }));

const sub: Subscription = { status: "ACTIVE", plan: "Pro", trialEndsAt: null, currentPeriodEnd: null, cancelAtPeriodEnd: false, readOnly: false, canManage: true };

function Probe() {
  const { subscription } = useSubscription();
  return <p>{subscription ? `${subscription.status}/${subscription.readOnly}` : "none"}</p>;
}

describe("SubscriptionProvider and global 402", () => {
  beforeEach(() => {
    toastError.mockReset();
    fetchSubscriptionClient.mockReset();
    vi.unstubAllGlobals();
  });

  it("on a 402 event toasts the message and refetches the subscription", async () => {
    fetchSubscriptionClient.mockResolvedValue({ ...sub, status: "CANCELED", readOnly: true });
    render(
      <SubscriptionProvider initial={sub}>
        <Probe />
      </SubscriptionProvider>,
    );
    expect(screen.getByText("ACTIVE/false")).toBeInTheDocument();
    act(() => {
      window.dispatchEvent(new Event(SUBSCRIPTION_INACTIVE_EVENT));
    });
    expect(toastError).toHaveBeenCalledWith(expect.stringContaining("read-only"), { id: "subscription-inactive" });
    await waitFor(() => expect(screen.getByText("CANCELED/true")).toBeInTheDocument());
  });

  it("fetches on mount when the server gave nothing", async () => {
    fetchSubscriptionClient.mockResolvedValue(sub);
    render(
      <SubscriptionProvider initial={null}>
        <Probe />
      </SubscriptionProvider>,
    );
    await waitFor(() => expect(screen.getByText("ACTIVE/false")).toBeInTheDocument());
  });

  it("apiFetch dispatches the event on 402 and still throws the ApiError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: "Subscription inactive", status: 402 }), { status: 402, headers: { "Content-Type": "application/problem+json" } }),
      ),
    );
    document.cookie = "XSRF-TOKEN=t";
    const listener = vi.fn();
    window.addEventListener(SUBSCRIPTION_INACTIVE_EVENT, listener);
    await expect(apiFetch("/vendors", { method: "POST", json: {} })).rejects.toMatchObject({ status: 402, title: "Subscription inactive" });
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(SUBSCRIPTION_INACTIVE_EVENT, listener);
  });
});
