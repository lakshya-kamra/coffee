import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newBean, newBrew, newGrinder, type BrewRow, type GrinderRow } from "@/data/schema";
import { DEFAULT_SETTINGS } from "@/data/settings";
import { makeBrewer } from "@/domain/core/brewer";
import type { Recipe } from "@/domain/core/recipe";
import { AGAIN_TITLE, againSetup, oneOffRecipe } from "./again";
import { setupRecipe } from "./setup";

beforeAll(async () => {
  await catalog.load(["grinders"]);
});

const V60 = makeBrewer({ id: "hario-v60-02", label: "Hario V60", method: "pourover", category: "cone" });
const PRESS = makeBrewer({ id: "french-press", label: "French press", method: "frenchPress" });

const recipe = (p: Partial<Recipe> = {}): Recipe => ({
  id: "hoffmann-v60", name: "The Ultimate V60", author: "James Hoffmann", brewer: V60, doseG: 15, waterG: 250, grind: "Medium-fine", tempC: 94, blurb: "", sourceNote: "",
  prep: [], tips: [], steps: [{ kind: "Bloom", title: "Bloom", detail: "", startSec: 0, endSec: 45, targetWaterG: 50 }, { kind: "Pour", title: "Pour", detail: "", startSec: 45, endSec: 120, targetWaterG: 250 }],
  accent: 0xff000000, isBuiltIn: true, fidelity: "published", sources: [], basedOn: null, level: "", needs: [], prepNoScale: [], ...p,
});

const kUltra = (): GrinderRow => ({ ...newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, catalogId: "1zpresso-k-ultra" }), id: 2 });
const vario = (): GrinderRow => ({ ...newGrinder({ brand: "Baratza", model: "Vario", settingStyle: "numbers" }), id: 9, isDefault: true });
const blade = (): GrinderRow => ({ ...newGrinder({ brand: "Any", model: "Blade grinder", settingStyle: "stepless", settingUnit: "s" }), id: 7 });
const beans = [{ ...newBean({ name: "Red Brick", roaster: "Square Mile" }), id: 4 }, { ...newBean({ name: "Daterra", roaster: "Onyx" }), id: 5 }];
const brew = (p: Partial<BrewRow>): BrewRow => ({ ...newBrew({ startedAt: 1, recipeId: "hoffmann-v60", recipeName: "The Ultimate V60", brewerId: "hario-v60-02", brewer: "Hario V60", ...p }, 1), id: 1, uid: "u1" });

describe("Brew again prefill (§4.10)", () => {
  it("takes the brew's coffee, grinder, clicks, dose and temperature", () => {
    const b = brew({ beanId: 5, grinderId: 2, grindClicks: 78, grindKind: "dial", doseG: 18, waterG: 300, tempC: 92 });
    const s = againSetup(recipe(), b, beans, [vario(), kUltra()], DEFAULT_SETTINGS, []);
    expect(s).toMatchObject({ beanId: 5, grinderId: 2, grindClicks: 78, doseG: 18, tempC: 92, preground: false });
    // 18 g at the recipe's 1:16.7 is 300 g: the recipe's ratio already gives the brew's water.
    expect(s.waterG).toBeNull();
    expect(setupRecipe(recipe(), s).waterG).toBe(300);
  });

  it("keeps a water that differs from the recipe's ratio, and scales the pours to it", () => {
    const b = brew({ grinderId: 9, grindValue: 5.5, grindSetting: "5.5", grindKind: "dial", doseG: 15, waterG: 270 });
    const s = againSetup(recipe(), b, beans, [vario()], DEFAULT_SETTINGS, []);
    expect(s).toMatchObject({ waterG: 270, grinderId: 9, grindSetting: "5.5" });
    const r = setupRecipe(recipe(), s);
    expect(r.waterG).toBe(270);
    expect(r.steps.map((x) => x.targetWaterG)).toEqual([54, 270]);
  });

  it("pre-ground and blade brews come back as they were", () => {
    const pre = againSetup(recipe(), brew({ grindKind: "preground", grindSetting: "As bought", grinderId: null }), beans, [vario()], DEFAULT_SETTINGS, []);
    expect(pre.preground).toBe(true);
    const bl = againSetup(recipe(), brew({ grindKind: "blade", grinderId: 7, grindValue: 14, grindSetting: "14 s" }), beans, [vario(), blade()], DEFAULT_SETTINGS, []);
    expect(bl).toMatchObject({ preground: false, grinderId: 7, bladeSec: 14 });
  });

  it("an archived coffee or grinder falls back to the usual defaults", () => {
    const s = againSetup(recipe(), brew({ beanId: 99, grinderId: 42, grindClicks: 60 }), beans, [vario()], { ...DEFAULT_SETTINGS, defaultBeanId: 4 }, []);
    expect(s).toMatchObject({ beanId: 4, grinderId: 9, grindClicks: null });
  });

  it("a deleted recipe becomes a one-off with the brew's numbers and one step", () => {
    const b = brew({ recipeId: "gone", recipeName: "My old recipe", recipeAuthor: "Me", doseG: 20, waterG: 320, tempC: 93, totalTimeSec: 215, grindSetting: "Medium" });
    const r = oneOffRecipe(b, V60);
    expect(r).toMatchObject({ id: "gone", name: "My old recipe", doseG: 20, waterG: 320, tempC: 93, isBuiltIn: false, fidelity: "custom" });
    expect(r.steps).toEqual([{ kind: "Pour", title: "Brew", detail: "The same time as last time.", startSec: 0, endSec: 215, targetWaterG: 320 }]);
    expect(oneOffRecipe({ ...b, recipeName: " ", totalTimeSec: 0 }, PRESS)).toMatchObject({ name: AGAIN_TITLE, steps: [{ kind: "Steep", endSec: 240 }] });
    // Prefilled from itself: the same dose and water.
    const s = againSetup(r, b, beans, [], DEFAULT_SETTINGS, []);
    expect(setupRecipe(r, s).waterG).toBe(320);
  });
});
