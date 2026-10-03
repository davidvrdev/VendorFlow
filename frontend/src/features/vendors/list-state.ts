// The URL is the single source of truth for the vendors list. These pure helpers parse it (never trusting
// it: unknown values fall back to defaults so a hand-edited URL cannot trigger a backend 400) and build it.

export const PAGE_SIZE = 25;
export const STATUS_FILTERS = ["ACTIVE", "INACTIVE", "ALL"] as const;
export type StatusFilter = (typeof STATUS_FILTERS)[number];

export const SORT_FIELDS = ["companyName", "createdAt", "updatedAt"] as const;
export type SortField = (typeof SORT_FIELDS)[number];
export type SortDirection = "asc" | "desc";
export type SortValue = `${SortField},${SortDirection}`;

export const DEFAULT_SORT: SortValue = "companyName,asc";
const MAX_Q = 200;
const MAX_CATEGORY = 60;

export interface VendorListState {
  q: string;
  status: StatusFilter;
  category: string;
  sort: SortValue;
  /** 1-based (the API is 0-based; see toApiQuery). */
  page: number;
}

export const DEFAULT_LIST_STATE: VendorListState = { q: "", status: "ACTIVE", category: "", sort: DEFAULT_SORT, page: 1 };

type RawParams = Record<string, string | string[] | undefined>;

function first(value: string | string[] | undefined): string {
  return (Array.isArray(value) ? value[0] : value) ?? "";
}

function isSortValue(value: string): value is SortValue {
  const [field, direction, ...rest] = value.split(",");
  return rest.length === 0 && (SORT_FIELDS as readonly string[]).includes(field) && (direction === "asc" || direction === "desc");
}

export function parseListState(params: RawParams): VendorListState {
  const status = first(params.status).toUpperCase();
  const sort = first(params.sort);
  const page = Number.parseInt(first(params.page), 10);
  return {
    q: first(params.q).trim().slice(0, MAX_Q),
    status: (STATUS_FILTERS as readonly string[]).includes(status) ? (status as StatusFilter) : "ACTIVE",
    category: first(params.category).trim().slice(0, MAX_CATEGORY),
    sort: isSortValue(sort) ? sort : DEFAULT_SORT,
    page: Number.isFinite(page) && page >= 1 ? page : 1,
  };
}

/** "?q=..." for the browser URL. Defaults are omitted so the plain list has a clean URL. */
export function toSearchString(state: VendorListState): string {
  const params = new URLSearchParams();
  if (state.q) params.set("q", state.q);
  if (state.status !== "ACTIVE") params.set("status", state.status);
  if (state.category) params.set("category", state.category);
  if (state.sort !== DEFAULT_SORT) params.set("sort", state.sort);
  if (state.page > 1) params.set("page", String(state.page));
  const text = params.toString();
  return text ? `?${text}` : "";
}

/** Any filter/sort change goes back to the first page. */
export function withFilterChange(state: VendorListState, patch: Partial<Omit<VendorListState, "page">>): VendorListState {
  return { ...state, ...patch, page: 1 };
}

export function withPage(state: VendorListState, page: number): VendorListState {
  return { ...state, page: Math.max(1, page) };
}

export function sortDirectionFor(state: VendorListState, field: SortField): SortDirection | null {
  const [current, direction] = state.sort.split(",") as [SortField, SortDirection];
  return current === field ? direction : null;
}

/** Clicking a header: same column flips direction; a new column starts ascending (dates start newest first). */
export function nextSort(state: VendorListState, field: SortField): SortValue {
  const current = sortDirectionFor(state, field);
  if (current) return `${field},${current === "asc" ? "desc" : "asc"}`;
  return field === "companyName" ? `${field},asc` : `${field},desc`;
}

export function ariaSortFor(state: VendorListState, field: SortField): "ascending" | "descending" | "none" {
  const direction = sortDirectionFor(state, field);
  return direction === "asc" ? "ascending" : direction === "desc" ? "descending" : "none";
}

/** True when no filter narrows the list (sort and page do not count). */
export function hasDefaultFilters(state: VendorListState): boolean {
  return state.q === "" && state.category === "" && state.status === "ACTIVE";
}

/** Query string for GET /vendors (page is 0-based there). */
export function toApiQuery(state: VendorListState): string {
  const params = new URLSearchParams();
  if (state.q) params.set("q", state.q);
  params.set("status", state.status);
  if (state.category) params.set("category", state.category);
  params.set("sort", state.sort);
  params.set("page", String(state.page - 1));
  params.set("size", String(PAGE_SIZE));
  return `?${params.toString()}`;
}
