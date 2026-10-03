import type { DocumentTypeAdmin } from "./types";

/** Display order: sortOrder, then name (the API's own order, repeated so ties are stable on the client). */
export function sortTypes<T extends Pick<DocumentTypeAdmin, "sortOrder" | "name">>(types: T[]): T[] {
  return [...types].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
}

export interface SortChange {
  id: string;
  sortOrder: number;
}

/**
 * Changes needed to move one type a step up or down. Normally the two neighbours swap sortOrder values; if they
 * tie (the contract does not promise unique values) the mover is nudged past the neighbour by one instead.
 * Returns [] when the type is already at that end of the list.
 */
export function computeMove(types: Pick<DocumentTypeAdmin, "id" | "sortOrder" | "name">[], id: string, direction: "up" | "down"): SortChange[] {
  const ordered = sortTypes(types);
  const index = ordered.findIndex((type) => type.id === id);
  const neighbour = ordered[direction === "up" ? index - 1 : index + 1];
  if (index < 0 || !neighbour) return [];
  const mover = ordered[index];
  if (mover.sortOrder === neighbour.sortOrder) {
    return [{ id: mover.id, sortOrder: neighbour.sortOrder + (direction === "up" ? -1 : 1) }];
  }
  return [
    { id: mover.id, sortOrder: neighbour.sortOrder },
    { id: neighbour.id, sortOrder: mover.sortOrder },
  ];
}
