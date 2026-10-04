/**
 * Unsubscribe-link token handling. Same rules as the portal token (features/portal/token.ts): it arrives in the URL
 * fragment (never sent to a server or a Referrer), is moved into memory and stripped from the address bar and history
 * entry at once, and is never stored. It has its own variable so an upload token and an unsubscribe token can never
 * be mixed up in one tab.
 */
let memoryToken: string | null = null;

const TOKEN_SHAPE = /^[A-Za-z0-9._~+/=-]{8,512}$/;

export function takeOptOutToken(): string | null {
  const hash = window.location.hash;
  if (hash.length > 1) {
    const candidate = new URLSearchParams(hash.slice(1)).get("token");
    window.history.replaceState(null, "", window.location.pathname);
    if (candidate !== null) memoryToken = candidate;
  }
  return memoryToken;
}

export function isPlausibleOptOutToken(token: string): boolean {
  return TOKEN_SHAPE.test(token);
}

/** Tests only. */
export function resetOptOutTokenForTests() {
  memoryToken = null;
}
