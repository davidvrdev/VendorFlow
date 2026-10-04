"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { toast } from "sonner";
import { SUBSCRIPTION_INACTIVE_EVENT } from "@/lib/api/client";
import { fetchSubscriptionClient } from "../api";
import type { Subscription } from "../schemas";

interface SubscriptionState {
  subscription: Subscription | null;
  loading: boolean;
  error: boolean;
  refetch: () => Promise<void>;
}

const SubscriptionContext = createContext<SubscriptionState | null>(null);

export const INACTIVE_MESSAGE = "Your workspace is read-only because the subscription is inactive. Subscribe to make changes.";

export function useSubscription(): SubscriptionState {
  const value = useContext(SubscriptionContext);
  if (!value) throw new Error("useSubscription must be used inside SubscriptionProvider");
  return value;
}

/** Holds the one subscription query for the app shell: server-provided first value, client refetch afterwards. */
export function SubscriptionProvider({ initial, children }: { initial: Subscription | null; children: ReactNode }) {
  const [subscription, setSubscription] = useState<Subscription | null>(initial);
  const [loading, setLoading] = useState(initial === null);
  const [error, setError] = useState(false);

  // router.refresh() re-renders the layout with a fresh server value: adopt it (adjust-during-render pattern).
  const [seenInitial, setSeenInitial] = useState(initial);
  if (initial !== seenInitial) {
    setSeenInitial(initial);
    if (initial) setSubscription(initial);
  }

  // No synchronous setState here, so it is safe to call from an effect.
  const load = useCallback(async () => {
    try {
      setSubscription(await fetchSubscriptionClient());
      setError(false);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  const refetch = useCallback(() => {
    setLoading(true);
    return load();
  }, [load]);

  // Mount-only fetch when the server gave nothing; state is set in promise callbacks, never synchronously.
  useEffect(() => {
    if (initial !== null) return;
    let live = true;
    fetchSubscriptionClient()
      .then((next) => live && (setSubscription(next), setError(false)))
      .catch(() => live && setError(true))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
    // Only on mount: later server values arrive through the prop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Global 402: any mutation rejected for an inactive subscription tells the user why, once, and refreshes status.
  useEffect(() => {
    const onInactive = () => {
      toast.error(INACTIVE_MESSAGE, { id: "subscription-inactive" });
      void refetch();
    };
    window.addEventListener(SUBSCRIPTION_INACTIVE_EVENT, onInactive);
    return () => window.removeEventListener(SUBSCRIPTION_INACTIVE_EVENT, onInactive);
  }, [refetch]);

  const value = useMemo(() => ({ subscription, loading, error, refetch }), [subscription, loading, error, refetch]);
  return <SubscriptionContext.Provider value={value}>{children}</SubscriptionContext.Provider>;
}
