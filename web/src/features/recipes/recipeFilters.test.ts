import { describe, expect, it } from "vitest";
import { makeBrewer, type Brewer } from "@/domain/core/brewer";
import type { Recipe } from "@/domain/core/recipe";
import { brewersIn, filterRecipes, headerEyebrow, myBrewersOn, NO_FILTER, ownedFamilies, recipeStats, sortRecipes } from "./recipeFilters";

const v60 = makeBrewer({ id: "hario-v60-02", label: "Hario V60", method: "pourover" });
const v60one = makeBrewer({ id: "hario-v60-01", label: "Hario V60-01", method: "pourover" });
const kalita = makeBrewer({ id: "kalita-wave-185", label: "Kalita Wave 185", method: "pourover", category: "flat-bottom" });
const aero = makeBrewer({ id: "aeropress", label: "AeroPress", method: "aeropress" });

const r = (id: string, author: string, brewer: Brewer, isBuiltIn = true): Recipe => ({
  id, name: `${author} ${brewer.label}`, author, brewer, doseG: 15, waterG: 250, grind: "", tempC: 94, blurb: id === "rao" ? "Spin the bed" : "", sourceNote: "",
  prep: [], tips: [], steps: [], accent: 0, isBuiltIn, fidelity: isBuiltIn ? "published" : "custom", sources: [], basedOn: null, level: "", needs: [], prepNoScale: [],
});

const ALL = [r("hoff", "James Hoffmann", v60), r("rao", "Scott Rao", v60), r("onyx", "Onyx", kalita), r("mine", "Me", aero, false)];
const melodrip = makeBrewer({ id: "melodrip", label: "Melodrip", method: "pourover", category: "other" });
const byId = (id: string) => [v60, v60one, kalita, aero, melodrip].find((b) => b.id === id) ?? null;

describe("recipe filters (RecipesViewModel + A§7.8)", () => {
  it("search covers name, author, blurb and brewer", () => {
    expect(filterRecipes(ALL, { ...NO_FILTER, query: "rao" }, new Set()).map((x) => x.id)).toEqual(["rao"]);
    expect(filterRecipes(ALL, { ...NO_FILTER, query: " SPIN " }, new Set()).map((x) => x.id)).toEqual(["rao"]);
    expect(filterRecipes(ALL, { ...NO_FILTER, query: "kalita" }, new Set()).map((x) => x.id)).toEqual(["onyx"]);
  });

  it("Mine, My brewers (by family) and one brewer, ANDed", () => {
    expect(filterRecipes(ALL, { ...NO_FILTER, mine: true }, new Set()).map((x) => x.id)).toEqual(["mine"]);
    const owned = ownedFamilies(["hario-v60-01"], byId);
    expect(filterRecipes(ALL, { ...NO_FILTER, myBrewers: true }, owned).map((x) => x.id)).toEqual(["hoff", "rao"]);
    expect(filterRecipes(ALL, { ...NO_FILTER, brewerId: "kalita-wave-185" }, new Set()).map((x) => x.id)).toEqual(["onyx"]);
    expect(filterRecipes(ALL, { ...NO_FILTER, myBrewers: true, query: "hoff" }, owned).map((x) => x.id)).toEqual(["hoff"]);
  });

  it("brewers present once, in list order; the header counts methods and people", () => {
    expect(brewersIn(ALL).map((b) => b.id)).toEqual(["hario-v60-02", "kalita-wave-185", "aeropress"]);
    expect(headerEyebrow(ALL)).toBe("4 METHODS · 4 PEOPLE");
    expect(headerEyebrow([])).toBe(" ");
  });
});

describe("My brewers is derived, never copied at mount (v4 §4.13)", () => {
  it("onboarding then Recipes: off with no brewers, on once they are saved, unless chosen off", () => {
    // Recipes mounted before onboarding: nothing owned, nothing chosen.
    let owned = ownedFamilies([], byId);
    expect(myBrewersOn(owned.size > 0, null)).toBe(false);
    // Onboarding saves a V60-01: the same mounted screen now filters by it, without a remount.
    owned = ownedFamilies(["hario-v60-01"], byId);
    expect(myBrewersOn(owned.size > 0, null)).toBe(true);
    expect(filterRecipes(ALL, { ...NO_FILTER, myBrewers: myBrewersOn(owned.size > 0, null) }, owned).map((x) => x.id)).toEqual(["hoff", "rao"]);
    // The person's own choice wins both ways, but can't turn on an empty filter.
    expect(myBrewersOn(true, false)).toBe(false);
    expect(myBrewersOn(true, true)).toBe(true);
    expect(myBrewersOn(false, true)).toBe(false);
  });

  it("a Melodrip alone matches nothing, so it never becomes a V60 filter", () => {
    const owned = ownedFamilies(["melodrip"], byId);
    expect(owned.size).toBe(0);
    expect(myBrewersOn(owned.size > 0, null)).toBe(false);
  });
});

describe("sort orders (v4 §3.2)", () => {
  const brews = [
    { recipeId: "onyx", overall: 6 },
    { recipeId: "onyx", overall: 7 },
    { recipeId: "onyx", overall: 0 },
    { recipeId: "rao", overall: 9 },
    { recipeId: "mine", overall: 0 },
  ];
  const stats = recipeStats(brews);
  const ids = (xs: readonly { id: string }[]) => xs.map((x) => x.id);

  it("counts brews and keeps the best score", () => {
    expect(stats.get("onyx")).toEqual({ count: 3, best: 7 });
    expect(stats.get("mine")).toEqual({ count: 1, best: 0 });
    expect(stats.get("hoff")).toBeUndefined();
  });

  it("suggested: next brew, then owned families, then the rest in library order", () => {
    const owned = ownedFamilies(["aeropress"], byId);
    expect(ids(sortRecipes(ALL, "suggested", stats, owned, "onyx"))).toEqual(["onyx", "mine", "hoff", "rao"]);
    expect(ids(sortRecipes(ALL, "suggested", stats, new Set(), null))).toEqual(["hoff", "rao", "onyx", "mine"]);
  });

  it("A-Z by name, most brewed, best rated (unrated last, ties in library order)", () => {
    expect(ids(sortRecipes(ALL, "az", stats, new Set(), null))).toEqual(["hoff", "mine", "onyx", "rao"]);
    expect(ids(sortRecipes(ALL, "brewed", stats, new Set(), null))).toEqual(["onyx", "rao", "mine", "hoff"]);
    expect(ids(sortRecipes(ALL, "rated", stats, new Set(), null))).toEqual(["rao", "onyx", "mine", "hoff"]);
  });

  it("search ignores accents and needs every word", () => {
    const cafe = { ...ALL[0]!, id: "cafe", name: "Café Bustelo stovetop" };
    expect(ids(filterRecipes([...ALL, cafe], { ...NO_FILTER, query: "cafe bustelo" }, new Set()))).toEqual(["cafe"]);
    expect(ids(filterRecipes(ALL, { ...NO_FILTER, query: "hoffmann kalita" }, new Set()))).toEqual([]);
  });
});
