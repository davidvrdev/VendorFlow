/**
 * The portal token lives only in memory. It arrives in the URL fragment (`/portal#token=...`; fragments are never sent
 * to a server or in a Referrer), is moved into this module variable and is removed from the address bar and history
 * entry at once. It is never put in storage, cookies or a URL. A reload therefore asks the vendor to reopen the link.
 */
let memoryToken: string | null = null;

// Generous: the backend's token format may change; this only rejects values that cannot be a header value.
const TOKEN_SHAPE = /^[A-Za-z0-9._~+/=-]{8,512}$/;

/** Read `#token=` once, strip it from the URL and return the token (from memory on later calls). */
export function takePortalToken(): string | null {
  const hash = window.location.hash;
  if (hash.length > 1) {
    const candidate = new URLSearchParams(hash.slice(1)).get("token");
    // Strip the fragment whatever it contained: it is not ours to keep around.
    window.history.replaceState(null, "", window.location.pathname);
    if (candidate !== null) memoryToken = candidate;
  }
  return memoryToken;
}

export function isPlausibleToken(token: string): boolean {
  return TOKEN_SHAPE.test(token);
}

/** Tests only. */
export function resetPortalTokenForTests() {
  memoryToken = null;
}
