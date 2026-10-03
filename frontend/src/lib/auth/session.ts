import "server-only";
import { cache } from "react";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError } from "@/lib/api/errors";
import { serverApiFetch } from "@/lib/api/server";
import type { Me } from "@/features/auth/types";

/** Current user, or null when not signed in (401). Other failures (5xx, network) propagate to error.tsx. */
// cache(): the layout and a page both call this in one request; only one backend call is made.
export const getMe = cache(async (): Promise<Me | null> => {
  try {
    return await serverApiFetch<Me>("/me");
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
});

/**
 * Authoritative server-side session gate for the (app) group. Redirects to /login?next=<path>.
 * The path comes from the `x-pathname` header set by proxy.ts; if absent we just go to /login.
 */
export async function requireMe(): Promise<Me> {
  const me = await getMe();
  if (me) return me;
  const path = (await headers()).get("x-vf-pathname");
  const safe = path && path.startsWith("/") && !path.startsWith("//") ? path : null;
  redirect(safe ? `/login?next=${encodeURIComponent(safe)}` : "/login");
}
