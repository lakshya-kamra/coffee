import { beforeAll, describe, expect, it } from "vitest";
import { Brewers, catalog } from "@/catalog/catalog";
import { LegacyIds } from "./legacyIds";
import {
  accentHex,
  decodeRecipe,
  encodeRecipe,
  parseAccent,
  recipeRatioLabel,
  scaledToDose,
  scaledToWater,
  stepBoundaries,
  totalSec,
  waterAt,
  waterAtF,
  type Recipe,
} from "./recipe";

beforeAll(async () => {
  await catalog.load(["brewers", "recipes"]);
});

/** A 1.x saved-recipe JSON string: enum brewer and a numeric accent (A§2.8 RecipeSerializationTest). */
const LEGACY_SAVED = JSON.stringify({
  id: "custom-1",
  name: "My V60",
  author: "Me",
  brewer: "V60",
  doseG: 15,
  waterG: 250,
  grind: "Medium-fine",
  tempC: 94,
  blurb: "",
  sourceNote: "",
  prep: [],
  tips: [],
  steps: [
    { kind: "Bloom", title: "Bloom to 45 g", detail: "", startSec: 0, endSec: 40, targetWaterG: 45 },
    { kind: "Pour", title: "Pour to 150 g", detail: "", startSec: 40, endSec: 80, targetWaterG: 150 },
    { kind: "Pour", title: "Pour to 250 g", detail: "", startSec: 80, endSec: 120, targetWaterG: 250 },
    { kind: "Drawdown", title: "Drawdown", detail: "", startSec: 120, endSec: 180, targetWaterG: null },
  ],
  accent: 4293503061,
  isBuiltIn: false,
});

function legacy(): Recipe {
  const r = decodeRecipe(JSON.parse(LEGACY_SAVED), Brewers.resolve);
  if (!r.ok) throw new Error(r.error);
  return r.value;
}

describe("recipe serialization", () => {
  it("decodes a 1.x saved recipe: enum brewer and numeric accent", () => {
    const r = legacy();
    expect(r.brewer.id).toBe("hario-v60-02");
    expect(r.accent).toBe(0xffe9a855);
    expect(r.isBuiltIn).toBe(false);
  });

  it("re-encodes brewer as its id and accent as hex", () => {
    const json = encodeRecipe(legacy());
    expect(json.brewer).toBe("hario-v60-02");
    expect(json.accent).toBe("#E9A855");
    const again = decodeRecipe(JSON.parse(JSON.stringify(json)), Brewers.resolve);
    expect(again.ok && again.value.accent).toBe(0xffe9a855);
  });

  it("reads #RRGGBB, #AARRGGBB and numbers", () => {
    expect(parseAccent("#E9A855")).toBe(0xffe9a855);
    expect(parseAccent("#80E9A855")).toBe(0x80e9a855);
    expect(parseAccent(-1)).toBe(0xffffffff); // a signed Int-style value maps to the same bits
    expect(parseAccent("nope")).toBeNull();
    expect(accentHex(0x80e9a855)).toBe("#80E9A855");
  });

  it("refuses an entry without steps instead of crashing", () => {
    const r = decodeRecipe({ id: "x", name: "X", doseG: 1, waterG: 2, tempC: 90, accent: "#000000" }, Brewers.resolve);
    expect(r.ok).toBe(false);
  });
});

describe("recipe maths", () => {
  it("ratio, total time and boundaries", () => {
    const r = legacy();
    expect(recipeRatioLabel(r)).toBe("1:16.7");
    expect(totalSec(r)).toBe(180);
    expect(stepBoundaries(r)).toEqual([40 / 180, 80 / 180, 120 / 180]);
  });

  it("scales to a new dose, keeping timings", () => {
    const r = scaledToDose(legacy(), 20);
    expect(r.doseG).toBe(20);
    expect(r.waterG).toBe(333);
    expect(r.steps.map((s) => s.targetWaterG)).toEqual([60, 200, 333, null]);
    expect(r.steps.map((s) => s.endSec)).toEqual([40, 80, 120, 180]);
    expect(scaledToWater(legacy(), 500).doseG).toBe(30);
  });

  it("interpolates water inside a pour", () => {
    const r = legacy();
    expect(waterAtF(r, 0)).toBe(0);
    expect(waterAtF(r, 20_000)).toBeCloseTo(22.5);
    expect(waterAt(r, 40_000)).toBe(45);
    expect(waterAt(r, 60_000)).toBe(98);
    expect(waterAt(r, 150_000)).toBe(250);
  });
});

describe("shared/recipes.json (skips until Android WP-1 writes it)", () => {
  it("legacy ids lead, golden numbers hold, steps are contiguous", () => {
    const all = catalog.recipes;
    if (all.length === 0) {
      console.warn("shared/recipes.json not present yet: recipe golden tests skipped");
      return;
    }
    expect(catalog.problems.filter((p) => p.startsWith("recipes.json"))).toEqual([]);
    expect(all.slice(0, LegacyIds.LEGACY_RECIPE_IDS.length).map((r) => r.id)).toEqual([...LegacyIds.LEGACY_RECIPE_IDS]);
    const ids = all.map((r) => r.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const r of all) {
      expect(r.id.startsWith("custom-"), r.id).toBe(false);
      expect(r.id, r.id).not.toBe("new");
      r.steps.forEach((s, i) => {
        if (i > 0) expect(s.startSec, `${r.id} step ${i}`).toBe(r.steps[i - 1]!.endSec);
      });
      const targets = r.steps.map((s) => s.targetWaterG).filter((t): t is number => t != null);
      for (let i = 1; i < targets.length; i++) expect(targets[i]!, `${r.id} targets`).toBeGreaterThanOrEqual(targets[i - 1]!);
      if (targets.length) expect(Math.max(...targets), `${r.id} max target`).toBe(r.waterG);
    }
    const h = catalog.recipe("hoffmann-v60")!;
    expect([h.doseG, h.waterG, h.tempC, totalSec(h), h.steps.length]).toEqual([30, 500, 100, 210, 6]);
    const k = catalog.recipe("kasuya-46")!;
    expect([k.doseG, k.waterG, k.tempC, totalSec(k)]).toEqual([20, 300, 92, 210]);
  });
});
