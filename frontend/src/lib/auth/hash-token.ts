"use client";

import { useEffect, useRef, useState } from "react";

/** Pull `token` out of a `#token=...` fragment. Pure, so it is unit-tested. */
export function extractTokenFromHash(hash: string): string | null {
  const params = new URLSearchParams(hash.startsWith("#") ? hash.slice(1) : hash);
  const token = params.get("token");
  return token && token.length > 0 && token.length <= 512 ? token : null;
}

/**
 * Email links carry one-time tokens in the URL fragment (never sent to servers, access logs or
 * Referer). Read it once on mount, then strip it from the address bar and history entry right away.
 * The token is never logged. `ready` is false until the effect ran (SSR/first render).
 */
export function useHashToken(): { token: string | null; ready: boolean } {
  const [state, setState] = useState<{ token: string | null; ready: boolean }>({ token: null, ready: false });
  const consumed = useRef(false);

  useEffect(() => {
    // StrictMode runs effects twice with the same refs; the second pass would see an empty hash.
    if (consumed.current) return;
    consumed.current = true;
    const token = extractTokenFromHash(window.location.hash);
    if (window.location.hash) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
    // Browser-only location can only be read after mount, hence state set from an effect.
    setState({ token, ready: true });
  }, []);

  return state;
}
