// A catalog file that fails to load (offline before the service worker has it, a deploy race)
// must not be passed off as an empty list for the rest of the session: it stays "not loaded",
// says it failed, and the next load() fetches it again.
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CatalogName } from "./types";

const real = await vi.importActual<typeof import("./sources")>("./sources");
const down = new Set<CatalogName>();
const fetched: CatalogName[] = [];

vi.mock("./sources", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./sources")>();
  return {
    ...actual,
    readShared: (name: CatalogName) => {
      fetched.push(name);
      if (down.has(name)) return Promise.reject(new TypeError("Failed to fetch dynamically imported module"));
      return real.readShared(name);
    },
  };
});

import { Brewers, catalog, resetCatalogForTests } from "./catalog";

beforeEach(() => {
  down.clear();
  fetched.length = 0;
  resetCatalogForTests();
});

describe("a catalog file that fails to load", () => {
  it("is not loaded, says so, and loads on the next try", async () => {
    down.add("grinders");
    const seen: number[] = [];
    const off = catalog.subscribe(() => seen.push(catalog.version()));
    await catalog.load(["grinders"]); // never rejects
    expect(catalog.isLoaded("grinders")).toBe(false);
    expect(catalog.failed("grinders")).toBe(true);
    expect(catalog.grinders).toEqual([]);
    expect(catalog.problems.join()).toContain("catalog/grinders.json: Failed to fetch");
    expect(seen.length).toBeGreaterThan(0); // subscribers hear about the failure

    // Still down: another call tries again instead of returning the empty list as loaded.
    await catalog.load(["grinders"]);
    expect(fetched.filter((n) => n === "grinders")).toHaveLength(2);
    expect(catalog.failed("grinders")).toBe(true);

    // The network is back.
    down.clear();
    await catalog.load(["grinders"]);
    expect(catalog.isLoaded("grinders")).toBe(true);
    expect(catalog.failed("grinders")).toBe(false);
    expect(catalog.grinder("1zpresso-k-ultra")?.setting.dialFormat).toBe("number-tick:10");
    expect(catalog.problems).toEqual([]);

    // Loaded files are not fetched again.
    await catalog.load(["grinders"]);
    expect(fetched.filter((n) => n === "grinders")).toHaveLength(3);
    off();
  });

  it("concurrent callers share one fetch", async () => {
    await Promise.all([catalog.load(["roasters"]), catalog.load(["roasters"]), catalog.load(["roasters", "water"])]);
    expect(fetched.filter((n) => n === "roasters")).toHaveLength(1);
    expect(catalog.isLoaded("roasters")).toBe(true);
  });

  it("recipes do not load without the brewer list, so none is saved as brewer 'Other'", async () => {
    down.add("brewers");
    await catalog.load(["brewers", "recipes"]);
    expect(catalog.failed("brewers")).toBe(true);
    expect(catalog.failed("recipes")).toBe(true);
    expect(catalog.isLoaded("recipes")).toBe(false);
    expect(catalog.recipes).toEqual([]);

    down.clear();
    await catalog.load(["recipes"]);
    expect(catalog.isLoaded("brewers")).toBe(true);
    expect(catalog.isLoaded("recipes")).toBe(true);
    expect(catalog.recipes.length).toBeGreaterThan(10);
    const v60 = catalog.recipes.find((r) => r.id === "hoffmann-v60");
    expect(v60?.brewer.id).toBe("hario-v60-02");
    expect(Brewers.byId("hario-v60-02")).not.toBeNull();
  });
});
