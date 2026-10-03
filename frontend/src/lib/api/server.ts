import "server-only";
import { cookies } from "next/headers";
import { ApiError } from "./errors";
import { toApiPath } from "./path";

/**
 * Server Component API read. Calls the Spring backend directly (private network, no rewrite hop)
 * and forwards the incoming request's cookies so the backend sees the user's session.
 * `cache: "no-store"`: responses are per-user and must never be shared or cached.
 * Writes do not go through here; they use `client.ts` (CSRF header) from the browser.
 */
export async function serverApiFetch<T>(path: string): Promise<T> {
  const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";
  // Next 16: cookies() is async.
  const cookieHeader = (await cookies()).toString();

  const response = await fetch(`${backendUrl}${toApiPath(path)}`, {
    method: "GET",
    cache: "no-store",
    headers: {
      Accept: "application/json",
      ...(cookieHeader ? { Cookie: cookieHeader } : {}),
    },
  });

  if (!response.ok) throw await ApiError.fromResponse(response);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
