const API_PREFIX = "/api/v1";

/**
 * Turn a path relative to the API root ("/vendors?page=0") into "/api/v1/vendors?page=0".
 * Rejects absolute URLs and protocol-relative paths so a caller can never point the client
 * (or the server-side fetch carrying the user's cookies) at another host.
 */
export function toApiPath(path: string): string {
  if (!path.startsWith("/") || path.startsWith("//") || path.includes("://") || path.includes("\\")) {
    throw new Error(`API path must be relative to ${API_PREFIX} and start with a single "/": ${path}`);
  }
  return `${API_PREFIX}${path}`;
}
