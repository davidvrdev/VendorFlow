import { ApiError } from "./errors";
import { toApiPath } from "./path";

const CSRF_COOKIE = "XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";
/** Fired (browser only) when any request gets 402; the subscription provider listens. */
export const SUBSCRIPTION_INACTIVE_EVENT = "vendorflow:subscription-inactive";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export type ApiRequestInit = Omit<RequestInit, "body"> & {
  /** Serialized as JSON with the right Content-Type. Use `body` for FormData uploads. */
  json?: unknown;
  body?: BodyInit | null;
};

export function isUnsafeMethod(method: string): boolean {
  return !SAFE_METHODS.has(method.toUpperCase());
}

/** Read one cookie from a `document.cookie`-style string. */
export function readCookie(name: string, cookieString: string): string | undefined {
  for (const part of cookieString.split(";")) {
    const separator = part.indexOf("=");
    if (separator === -1) continue;
    if (part.slice(0, separator).trim() === name) {
      try {
        return decodeURIComponent(part.slice(separator + 1).trim());
      } catch {
        return undefined;
      }
    }
  }
  return undefined;
}

/**
 * Build request headers. For unsafe methods the CSRF token is copied from the XSRF-TOKEN cookie
 * into X-XSRF-TOKEN (double-submit pattern expected by Spring Security).
 */
export function buildHeaders(
  method: string,
  base: HeadersInit | undefined,
  hasJsonBody: boolean,
  cookieString: string,
): Headers {
  const headers = new Headers(base);
  if (!headers.has("Accept")) headers.set("Accept", "application/json");
  if (hasJsonBody && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (isUnsafeMethod(method)) {
    const token = readCookie(CSRF_COOKIE, cookieString);
    if (token) headers.set(CSRF_HEADER, token);
  }
  return headers;
}

let csrfPriming: Promise<void> | null = null;

/**
 * Make sure an XSRF-TOKEN cookie exists before an unsafe request (first visit, expired cookie).
 * Concurrent callers share one in-flight request. Failure is swallowed: the real request then
 * fails with the server's own 403, which is the honest error to show.
 */
async function primeCsrf(): Promise<void> {
  csrfPriming ??= fetch(toApiPath("/auth/csrf"), {
    method: "GET",
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  })
    .then(() => undefined)
    .catch(() => undefined)
    .finally(() => {
      csrfPriming = null;
    });
  await csrfPriming;
}

function currentCookies(): string {
  return typeof document === "undefined" ? "" : document.cookie;
}

/**
 * Browser-side API call. `path` is relative to /api/v1 (e.g. "/vendors"). Same-origin, so the
 * session cookie travels automatically; no token is ever stored in JS-accessible storage.
 */
export async function apiFetch<T = void>(path: string, init: ApiRequestInit = {}): Promise<T> {
  const { json, body, headers: initHeaders, method: initMethod, ...rest } = init;
  const method = (initMethod ?? "GET").toUpperCase();
  const hasJsonBody = json !== undefined;

  if (isUnsafeMethod(method) && !readCookie(CSRF_COOKIE, currentCookies())) await primeCsrf();

  // Read the cookie fresh right before sending: responses may have rotated the token.
  const response = await fetch(toApiPath(path), {
    ...rest,
    method,
    credentials: "same-origin",
    headers: buildHeaders(method, initHeaders, hasJsonBody, currentCookies()),
    body: hasJsonBody ? JSON.stringify(json) : body,
  });

  if (!response.ok) {
    if (response.status === 402 && typeof window !== "undefined") window.dispatchEvent(new Event(SUBSCRIPTION_INACTIVE_EVENT));
    throw await ApiError.fromResponse(response);
  }
  if (response.status === 204 || response.status === 205) return undefined as T;

  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
