import { describe, expect, it } from "vitest";
import { makeBrewer, type Brewer } from "@/domain/core/brewer";
import type { Recipe } from "@/domain/core/recipe";
import { brewersIn, filterRecipes, headerEyebrow, NO_FILTER, ownedFamilies } from "./recipeFilters";

const v60 = makeBrewer({ id: "hario-v60-02", label: "Hario V60", method: "pourover" });
const v60one = makeBrewer({ id: "hario-v60-01", label: "Hario V60-01", method: "pourover" });
const kalita = makeBrewer({ id: "kalita-wave-185", label: "Kalita Wave 185", method: "pourover", category: "flat-bottom" });
const aero = makeBrewer({ id: "aeropress", label: "AeroPress", method: "aeropress" });

const r = (id: string, author: string, brewer: Brewer, isBuiltIn = true): Recipe => ({
  id, name: `${author} ${brewer.label}`, author, brewer, doseG: 15, waterG: 250, grind: "", tempC: 94, blurb: id === "rao" ? "Spin the bed" : "", sourceNote: "",
  prep: [], tips: [], steps: [], accent: 0, isBuiltIn, fidelity: isBuiltIn ? "published" : "custom", sources: [], basedOn: null, level: "", needs: [], prepNoScale: [],
});

const ALL = [r("hoff", "James Hoffmann", v60), r("rao", "Scott Rao", v60), r("onyx", "Onyx", kalita), r("mine", "Me", aero, false)];
const byId = (id: string) => [v60, v60one, kalita, aero].find((b) => b.id === id) ?? null;

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
