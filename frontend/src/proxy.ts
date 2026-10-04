import { NextResponse, type NextRequest } from "next/server";
import { buildCsp, generateNonce } from "@/lib/security/headers";

/**
 * Next 16 `proxy` (successor of middleware). Two jobs:
 * 1. Content-Security-Policy with a per-request nonce for every page (Next reads the nonce from the request's CSP
 *    header and applies it to its own scripts/styles). Pages must render dynamically; the root layout opts in.
 * 2. UX-only fast path: send anonymous visitors of app pages to /login before rendering. It only checks that a
 *    session cookie (`__Host-VF_SESSION` over https, `VF_SESSION` on plain http) *exists*; it cannot tell whether it is still valid. The authoritative check is requireMe()
 *    in (app)/layout.tsx and the backend.
 *
 * It also forwards the requested path in a request header so the layout (which cannot see the URL)
 * can build `/login?next=<path>` when the session turns out to be expired.
 */
const PROTECTED = /^\/(dashboard|vendors|settings)(\/|$)/;

export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const csp = buildCsp(generateNonce(), process.env.NODE_ENV === "production");

  if (PROTECTED.test(pathname) && !(request.cookies.has("__Host-VF_SESSION") || request.cookies.has("VF_SESSION"))) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname + search);
    const redirect = NextResponse.redirect(login);
    redirect.headers.set("Content-Security-Policy", csp);
    return redirect;
  }

  const headers = new Headers(request.headers);
  headers.set("x-vf-pathname", pathname + search);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export const config = {
  // Pages only: skip API rewrites, static assets and prefetches (docs: content-security-policy.md).
  matcher: [
    {
      source: "/((?!api|_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
