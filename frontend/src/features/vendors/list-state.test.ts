import { describe, expect, it } from "vitest";
import {
  ariaSortFor,
  DEFAULT_LIST_STATE,
  hasDefaultFilters,
  nextSort,
  parseListState,
  toApiQuery,
  toSearchString,
  withFilterChange,
  withPage,
} from "./list-state";

describe("parseListState", () => {
  it("returns defaults for an empty query", () => {
    expect(parseListState({})).toEqual(DEFAULT_LIST_STATE);
  });

  it("reads known values", () => {
    expect(parseListState({ q: " pipe ", status: "inactive", category: "Plumbing", sort: "updatedAt,desc", page: "3" })).toEqual({
      q: "pipe",
      status: "INACTIVE",
      category: "Plumbing",
      compliance: "",
      sort: "updatedAt,desc",
      page: 3,
    });
  });

  it("falls back to defaults for unknown or malformed values instead of forwarding them to the API", () => {
    const state = parseListState({ status: "DELETED", sort: "password,asc", page: "-4" });
    expect(state.status).toBe("ACTIVE");
    expect(state.sort).toBe("companyName,asc");
    expect(state.page).toBe(1);
    expect(parseListState({ sort: "companyName,sideways" }).sort).toBe("companyName,asc");
    expect(parseListState({ page: "abc" }).page).toBe(1);
  });

  it("reads the compliance filter case-insensitively and ignores invalid values", () => {
    expect(parseListState({ compliance: "attention" }).compliance).toBe("ATTENTION");
    expect(parseListState({ compliance: "BOGUS" }).compliance).toBe("");
    expect(parseListState({ sort: "compliance,asc" }).sort).toBe("compliance,asc");
    expect(parseListState({ sort: "compliance,up" }).sort).toBe("companyName,asc");
  });

  it("takes the first value of a repeated parameter", () => {
    expect(parseListState({ q: ["a", "b"] }).q).toBe("a");
  });
});

describe("toSearchString", () => {
  it("omits defaults", () => {
    expect(toSearchString(DEFAULT_LIST_STATE)).toBe("");
  });

  it("round-trips through parseListState", () => {
    const state = { q: "a&b c", status: "ALL" as const, category: "Lawn & Garden", compliance: "NON_COMPLIANT" as const, sort: "nextExpiration,asc" as const, page: 2 };
    const query = toSearchString(state);
    expect(parseListState(Object.fromEntries(new URLSearchParams(query)))).toEqual(state);
  });
});

describe("state transitions", () => {
  it("resets the page when a filter changes but keeps it for plain page moves", () => {
    const state = { ...DEFAULT_LIST_STATE, page: 4 };
    expect(withFilterChange(state, { q: "x" })).toMatchObject({ q: "x", page: 1 });
    expect(withFilterChange(state, { status: "ALL" }).page).toBe(1);
    expect(withPage(state, 5).page).toBe(5);
    expect(withPage(state, 0).page).toBe(1);
  });

  it("toggles sort direction on the active column and starts sensibly on a new one", () => {
    expect(nextSort(DEFAULT_LIST_STATE, "companyName")).toBe("companyName,desc");
    expect(nextSort({ ...DEFAULT_LIST_STATE, sort: "companyName,desc" }, "companyName")).toBe("companyName,asc");
    expect(nextSort(DEFAULT_LIST_STATE, "updatedAt")).toBe("updatedAt,desc");
    expect(nextSort(DEFAULT_LIST_STATE, "compliance")).toBe("compliance,asc");
    expect(nextSort(DEFAULT_LIST_STATE, "nextExpiration")).toBe("nextExpiration,asc");
  });

  it("reports aria-sort per column", () => {
    expect(ariaSortFor(DEFAULT_LIST_STATE, "companyName")).toBe("ascending");
    expect(ariaSortFor(DEFAULT_LIST_STATE, "updatedAt")).toBe("none");
    expect(ariaSortFor({ ...DEFAULT_LIST_STATE, sort: "updatedAt,desc" }, "updatedAt")).toBe("descending");
  });

  it("knows when no filter is applied (sort and page do not count)", () => {
    expect(hasDefaultFilters({ ...DEFAULT_LIST_STATE, page: 3, sort: "updatedAt,asc" })).toBe(true);
    expect(hasDefaultFilters({ ...DEFAULT_LIST_STATE, q: "x" })).toBe(false);
    expect(hasDefaultFilters({ ...DEFAULT_LIST_STATE, status: "ALL" })).toBe(false);
    expect(hasDefaultFilters({ ...DEFAULT_LIST_STATE, category: "c" })).toBe(false);
    expect(hasDefaultFilters({ ...DEFAULT_LIST_STATE, compliance: "COMPLIANT" })).toBe(false);
  });
});

describe("toApiQuery", () => {
  it("is 0-based for page and always explicit about status, sort and size", () => {
    expect(toApiQuery({ ...DEFAULT_LIST_STATE, q: "a b", page: 3 })).toBe("?q=a+b&status=ACTIVE&sort=companyName%2Casc&page=2&size=25");
    expect(toApiQuery({ ...DEFAULT_LIST_STATE, compliance: "ATTENTION" })).toBe("?status=ACTIVE&compliance=ATTENTION&sort=companyName%2Casc&page=0&size=25");
  });
});
