// Log search, filter chips and the XL table (v4 §3.7, §4.11): the UI state, its copy and the sort.
import { describe, expect, it } from "vitest";
import { newBrew, type BrewRow } from "@/data/schema";
import { EMPTY_LOG_FILTER, filterLog, isFilterActive } from "@/domain/core/logQuery";
import { brewAgainPath, filterOptions, logUiReducer, menuChipLabel, nextSort, resultLine, selectedUidOf, sortTable, type LogUiAction } from "./logState";

const DAY = 86_400_000;
const T0 = new Date(2026, 10, 19, 8, 0).getTime();

function brew(p: Partial<BrewRow>): BrewRow {
  return newBrew({ recipeId: "hoffmann-v60", recipeName: "The Ultimate V60 Technique", recipeAuthor: "James Hoffmann", brewer: "V60 02", brewerId: "hario-v60-02", doseG: 15, waterG: 250, ...p }, p.startedAt ?? T0);
}

const LOGS: BrewRow[] = [
  brew({ id: 1, uid: "a", startedAt: T0, beanLabel: "Onyx Coffee Lab — Geometry", overall: 8, isFavourite: true, notes: "Sweet and clean" }),
  brew({ id: 2, uid: "b", startedAt: T0 - DAY, beanLabel: "Square Mile — Red Brick", overall: 6, recipeId: "kasuya-46", recipeName: "The 4:6 Method", recipeAuthor: "Tetsu Kasuya" }),
  brew({ id: 3, uid: "c", startedAt: T0 - 2 * DAY, beanLabel: "Onyx Coffee Lab — Geometry", overall: 9, brewer: "AeroPress", brewerId: "aeropress", recipeId: "hoffmann-aeropress", recipeName: "The Ultimate AeroPress" }),
  brew({ id: 4, uid: "d", startedAt: T0 - 3 * DAY, beanLabel: "", overall: 0, descriptors: ["Café", "Floral"] }),
];

const run = (actions: LogUiAction[]) => actions.reduce(logUiReducer, EMPTY_LOG_FILTER);

describe("log filter UI state", () => {
  it("typing a query narrows with CORE's matcher (accents ignored)", () => {
    const f = run([{ type: "query", query: "onyx" }]);
    expect(filterLog(LOGS, f).map((b) => b.uid)).toEqual(["a", "c"]);
    expect(filterLog(LOGS, run([{ type: "query", query: "cafe" }])).map((b) => b.uid)).toEqual(["d"]);
  });

  it("toggle chips switch on and off; menu chips pick and unpick", () => {
    let f = run([{ type: "toggle", chip: "favourites" }]);
    expect(f.favourites).toBe(true);
    expect(filterLog(LOGS, f).map((b) => b.uid)).toEqual(["a"]);
    f = logUiReducer(f, { type: "toggle", chip: "favourites" });
    expect(f.favourites).toBe(false);
    f = run([{ type: "pick", chip: "coffee", value: "Onyx Coffee Lab — Geometry" }, { type: "toggle", chip: "rated8" }]);
    expect(filterLog(LOGS, f).map((b) => b.uid)).toEqual(["a", "c"]);
    f = logUiReducer(f, { type: "pick", chip: "brewer", value: "aeropress" });
    expect(filterLog(LOGS, f).map((b) => b.uid)).toEqual(["c"]);
  });

  it("With photos uses the ids that have photos", () => {
    const f = run([{ type: "toggle", chip: "withPhotos" }]);
    expect(filterLog(LOGS, f, new Set([2, 4])).map((b) => b.uid)).toEqual(["b", "d"]);
  });

  it("Best rated sorts by score, then newest", () => {
    const f = run([{ type: "sort", sort: "best" }]);
    expect(filterLog(LOGS, f).map((b) => b.uid)).toEqual(["c", "a", "b", "d"]);
  });

  it("Clear filters empties every filter but keeps the sort; a no-op returns the same object", () => {
    const f = run([{ type: "sort", sort: "best" }, { type: "query", query: "x" }, { type: "toggle", chip: "rated8" }, { type: "pick", chip: "recipe", value: "kasuya-46" }]);
    expect(isFilterActive(f)).toBe(true);
    const cleared = logUiReducer(f, { type: "clear" });
    expect(isFilterActive(cleared)).toBe(false);
    expect(cleared.sort).toBe("best");
    expect(logUiReducer(cleared, { type: "clear" })).toBe(cleared);
    expect(logUiReducer(cleared, { type: "query", query: "" })).toBe(cleared);
  });

  it("menu options: most brewed first, blanks left out; chip labels say the pick", () => {
    const coffees = filterOptions(LOGS, "coffee");
    expect(coffees).toEqual([
      { key: "Onyx Coffee Lab — Geometry", label: "Geometry", count: 2 },
      { key: "Square Mile — Red Brick", label: "Red Brick", count: 1 },
    ]);
    expect(menuChipLabel("coffee", EMPTY_LOG_FILTER, coffees)).toBe("Coffee ▾");
    expect(menuChipLabel("coffee", { ...EMPTY_LOG_FILTER, coffee: "Square Mile — Red Brick" }, coffees)).toBe("Red Brick ▾");
    expect(filterOptions(LOGS, "recipe")[0]).toEqual({ key: "hoffmann-v60", label: "The Ultimate V60 Technique", count: 2 });
    expect(filterOptions(LOGS, "brewer").map((o) => o.key)).toEqual(["hario-v60-02", "aeropress"]);
  });

  it("the result line", () => {
    expect(resultLine(12, 12, false)).toBe("12 brews");
    expect(resultLine(1, 1, false)).toBe("1 brew");
    expect(resultLine(3, 25, true)).toBe("3 of 25 brews");
    expect(resultLine(25, 25, true)).toBe("25 brews");
  });
});

describe("log table sort", () => {
  it("sorts each column, missing values last both ways, ties newest first", () => {
    expect(sortTable(LOGS, "score", "desc").map((b) => b.uid)).toEqual(["c", "a", "b", "d"]);
    expect(sortTable(LOGS, "score", "asc").map((b) => b.uid)).toEqual(["b", "a", "c", "d"]);
    expect(sortTable(LOGS, "coffee", "asc").map((b) => b.uid)).toEqual(["a", "c", "b", "d"]);
    expect(sortTable(LOGS, "date", "asc").map((b) => b.uid)).toEqual(["d", "c", "b", "a"]);
  });

  it("time vs plan sorts by the size of the miss", () => {
    const rows = [brew({ uid: "x", totalTimeSec: 200 }), brew({ uid: "y", totalTimeSec: 175, startedAt: T0 - DAY }), brew({ uid: "z", totalTimeSec: 0, startedAt: T0 - 2 * DAY })];
    expect(sortTable(rows, "time", "asc", () => 180).map((b) => b.uid)).toEqual(["y", "x", "z"]);
  });

  it("clicking a header: the same column flips, another starts at its natural order", () => {
    expect(nextSort({ key: "date", dir: "desc" }, "date")).toEqual({ key: "date", dir: "asc" });
    expect(nextSort({ key: "date", dir: "desc" }, "recipe")).toEqual({ key: "recipe", dir: "asc" });
    expect(nextSort({ key: "recipe", dir: "asc" }, "score")).toEqual({ key: "score", dir: "desc" });
  });
});

describe("paths", () => {
  it("brew again and the selected uid", () => {
    expect(brewAgainPath({ recipeId: "hoffmann-v60", uid: "u1" })).toBe("/brew/hoffmann-v60/again/u1");
    expect(brewAgainPath({ recipeId: " ", uid: "u1" })).toBe("/brew/deleted/again/u1");
    expect(selectedUidOf("/log/u1")).toBe("u1");
    expect(selectedUidOf("/log/u1/compare/u2")).toBe("u1");
    expect(selectedUidOf(null)).toBeNull();
  });
});
