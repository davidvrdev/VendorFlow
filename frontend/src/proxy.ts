import { NextResponse, type NextRequest } from "next/server";

/**
 * Next 16 `proxy` (successor of middleware). UX-only fast path: send anonymous visitors of app
 * pages to /login before rendering. It only checks that a VF_SESSION cookie *exists*; it cannot tell
 * whether it is still valid. The authoritative check is requireMe() in (app)/layout.tsx and the backend.
 *
 * It also forwards the requested path in a request header so the layout (which cannot see the URL)
 * can build `/login?next=<path>` when the session turns out to be expired.
 * Keep `matcher` in sync with the routes under src/app/(app).
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;

  if (!request.cookies.has("VF_SESSION")) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname + search);
    return NextResponse.redirect(login);
  }

  const headers = new Headers(request.headers);
  headers.set("x-vf-pathname", pathname + search);
  return NextResponse.next({ request: { headers } });
}

export const config = {
  matcher: ["/dashboard/:path*", "/vendors/:path*", "/settings/:path*"],
};
