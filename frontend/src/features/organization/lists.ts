/**
 * API.md does not state whether the members/invitations lists are bare arrays or the paged
 * `{ items }` envelope used by other endpoints, so accept both. Orgs are small; these screens do not page.
 */
export function unwrapList<T>(value: T[] | { items: T[] }): T[] {
  return Array.isArray(value) ? value : value.items;
}
