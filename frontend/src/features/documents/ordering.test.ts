import { describe, expect, it } from "vitest";
import { computeMove, sortTypes } from "./ordering";

const t = (id: string, sortOrder: number, name = id) => ({ id, sortOrder, name });

describe("computeMove", () => {
  const types = [t("c", 30), t("a", 10), t("b", 20)];

  it("sorts by sortOrder then name", () => {
    expect(sortTypes(types).map((x) => x.id)).toEqual(["a", "b", "c"]);
    expect(sortTypes([t("z", 1, "Zed"), t("y", 1, "Alpha")]).map((x) => x.id)).toEqual(["y", "z"]);
  });

  it("swaps the sort orders of the two neighbours", () => {
    expect(computeMove(types, "b", "up")).toEqual([
      { id: "b", sortOrder: 10 },
      { id: "a", sortOrder: 20 },
    ]);
    expect(computeMove(types, "b", "down")).toEqual([
      { id: "b", sortOrder: 30 },
      { id: "c", sortOrder: 20 },
    ]);
  });

  it("does nothing at the ends", () => {
    expect(computeMove(types, "a", "up")).toEqual([]);
    expect(computeMove(types, "c", "down")).toEqual([]);
    expect(computeMove(types, "missing", "up")).toEqual([]);
  });

  it("nudges the mover when the neighbour ties", () => {
    expect(computeMove([t("a", 5), t("b", 5)], "b", "up")).toEqual([{ id: "b", sortOrder: 4 }]);
    expect(computeMove([t("a", 5), t("b", 5)], "a", "down")).toEqual([{ id: "a", sortOrder: 6 }]);
  });
});
