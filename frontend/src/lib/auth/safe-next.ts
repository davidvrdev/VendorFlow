/**
 * Validate the `next` redirect target after login (open-redirect prevention).
 * Only same-site relative paths are allowed: they must start with a single "/", and must not contain
 * a backslash (browsers treat "\" like "/", so "/\evil.com" becomes "//evil.com"), a scheme, control
 * characters or whitespace. Returns null when unsafe so callers fall back to a default.
 */
export function safeNextPath(next: string | null | undefined): string | null {
  if (typeof next !== "string" || next.length === 0 || next.length > 2048) return null;
  if (!next.startsWith("/") || next.startsWith("//")) return null;
  if (next.includes("\\") || next.includes("://")) return null;
  if (/[\u0000-\u001f\u007f\s]/.test(next)) return null;
  // Percent-encoded slashes/backslashes at the start can be decoded into "//" or "/\" by some proxies.
  if (/^\/(%2f|%5c)/i.test(next)) return null;
  try {
    const parsed = new URL(next, "http://placeholder.invalid");
    if (parsed.origin !== "http://placeholder.invalid") return null;
  } catch {
    return null;
  }
  return next;
}
