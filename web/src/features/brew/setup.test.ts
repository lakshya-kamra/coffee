import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newBean, newBrew, newGrinder, type BrewRow, type GrinderRow } from "@/data/schema";
import { DEFAULT_SETTINGS } from "@/data/settings";
import { makeBrewer } from "@/domain/core/brewer";
import { epochDayOf } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { AS_BOUGHT, buildBrewRow, describeClicks, grindKindOf, initialSetup, lastTrustedClicks, minClicks, prefillGrind, setupRecipe, withBean, withDose, withSetupDefaults, type Setup } from "./setup";

beforeAll(async () => {
  await catalog.load(["grinders"]);
});

const V60 = makeBrewer({ id: "hario-v60-02", label: "Hario V60", method: "pourover" });
const AERO = makeBrewer({ id: "aeropress", label: "AeroPress", method: "aeropress" });
const ESPRESSO = makeBrewer({ id: "espresso-machine", label: "Espresso machine", method: "espresso" });

function recipe(p: Partial<Recipe> = {}): Recipe {
  return {
    id: "hoffmann-v60", name: "V60", author: "Hoffmann", brewer: V60, doseG: 15, waterG: 250, grind: "Medium-fine", tempC: 94, blurb: "", sourceNote: "",
    prep: [], tips: [], steps: [{ kind: "Pour", title: "Pour", detail: "", startSec: 0, endSec: 120, targetWaterG: 250 }], accent: 0xff000000,
    isBuiltIn: true, fidelity: "published", sources: [], basedOn: null, level: "", needs: [], prepNoScale: [], ...p,
  };
}

const legacyKUltra = (): GrinderRow => ({ ...newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 10, micronsPerClick: 20, maxSetting: 200 }), id: 1 });
const linkedKUltra = (): GrinderRow => ({ ...newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, maxSetting: 200, catalogId: "1zpresso-k-ultra" }), id: 2 });
const log = (p: Partial<BrewRow>, at: number): BrewRow => ({ ...newBrew({ startedAt: at, ...p }, at), id: at });
const blank = (p: Partial<Setup> = {}): Setup => withSetupDefaults({ doseG: 15, tempC: 94, ...p });

describe("describeClicks (GrinderEntity.describeClicks)", () => {
  it("1.x row reads in turns, the catalog-linked K-Ultra reads the dial (lead decision 2)", () => {
    expect(describeClicks(legacyKUltra(), 75)).toBe("75 clicks (7 turns + 5)");
    expect(describeClicks(linkedKUltra(), 75)).toBe("75 clicks (dial 7.5)");
    expect(describeClicks(linkedKUltra(), 175)).toBe("175 clicks (dial 1 turn + 7.5)");
  });

  it("no rotation known: just the clicks", () => {
    const g = { ...newGrinder({ brand: "X", model: "Y", settingStyle: "clicks", clicksPerRotation: 0 }), id: 3 };
    expect(describeClicks(g, 32)).toBe("32 clicks");
  });
});

describe("grind prefill (BrewViewModel)", () => {
  const brews = [
    log({ recipeId: "other", brewerId: "aeropress", brewer: "AeroPress", grinderId: 2, grindClicks: 55 }, 5),
    log({ recipeId: "hoffmann-v60", brewerId: "hario-v60-02", grinderId: 2, grindClicks: 0 }, 4),
    log({ recipeId: "rao-v60", brewerId: "hario-v60-02", grinderId: 2, grindClicks: 72 }, 3),
    log({ recipeId: "hoffmann-v60", brewerId: "hario-v60-02", grinderId: 2, grindClicks: 81 }, 2),
  ];

  it("this recipe first, never 0, never another brewer's grind", () => {
    expect(lastTrustedClicks(linkedKUltra(), recipe(), brews)).toBe(81);
    expect(lastTrustedClicks(linkedKUltra(), recipe({ id: "new-one" }), brews)).toBe(72);
    expect(lastTrustedClicks(linkedKUltra(), recipe({ id: "x", brewer: AERO }), brews)).toBe(55);
  });

  it("distrusts a dial reading typed as clicks on a filter brew", () => {
    // A 1.x 10-click row with no microns: under one turn is the legacy rule.
    const legacy = { ...legacyKUltra(), micronsPerClick: null };
    const typed = [log({ recipeId: "hoffmann-v60", brewerId: "hario-v60-02", grinderId: 1, grindClicks: 8 }, 9), log({ recipeId: "hoffmann-v60", brewerId: "hario-v60-02", grinderId: 1, grindClicks: 82 }, 8)];
    expect(lastTrustedClicks(legacy, recipe(), typed)).toBe(82);
    // The catalog K-Ultra: finer than its espresso range (30–45) on a V60 is a typed dial reading.
    const linked = [log({ recipeId: "hoffmann-v60", brewerId: "hario-v60-02", grinderId: 2, grindClicks: 8 }, 9), log({ recipeId: "hoffmann-v60", brewerId: "hario-v60-02", grinderId: 2, grindClicks: 80 }, 8)];
    expect(lastTrustedClicks(linkedKUltra(), recipe(), linked)).toBe(80);
  });

  it("falls back to the grinder's own go-to setting, espresso or filter", () => {
    const g = { ...linkedKUltra(), defaultFilterSetting: 70, defaultEspressoSetting: 25 };
    const base = blank({ grinderId: 2 });
    expect(prefillGrind(base, recipe(), [g], []).grindClicks).toBe(70);
    expect(prefillGrind(base, recipe({ brewer: ESPRESSO }), [g], []).grindClicks).toBe(25);
    expect(prefillGrind(base, recipe(), [linkedKUltra()], []).grindClicks).toBeNull();
  });

  it("stepless grinders prefill the typed setting", () => {
    const g = { ...newGrinder({ brand: "Baratza", model: "Vario", settingStyle: "numbers", defaultFilterSetting: 5.5 }), id: 9 };
    const s = prefillGrind(blank({ grinderId: 9 }), recipe(), [g], []);
    expect(s.grindSetting).toBe("5.5");
  });

  it("minimum is one click: 0 is burr contact", () => {
    expect(minClicks(linkedKUltra())).toBe(1);
    expect(minClicks({ ...linkedKUltra(), minSetting: 5 })).toBe(5);
  });
});

describe("initial setup and the finished row (A§2.5 finish())", () => {
  it("defaults, then a row with brewerId, waterId and grindValue", () => {
    const now = Date.UTC(2026, 10, 17, 8, 14);
    const bean = { ...newBean({ name: "Red Brick", roaster: "Square Mile", roastedOnEpochDay: epochDayOf(now) - 12 }), id: 4 };
    const vario = { ...newGrinder({ brand: "Baratza", model: "Vario", settingStyle: "numbers" }), id: 9, isDefault: true };
    const settings = { ...DEFAULT_SETTINGS, defaultWaterId: "third-wave-classic" };
    const setup = initialSetup(recipe(), [bean], [linkedKUltra(), vario], settings, []);
    expect(setup).toMatchObject({ doseG: 15, tempC: 94, beanId: 4, grinderId: 9 });
    const row = buildBrewRow({ recipe: recipe(), setup: { ...setup, grindSetting: "5,5" }, bean, grinder: vario, settings, result: { totalTimeSec: 190, drawdownSec: 40 }, now });
    expect(row).toMatchObject({
      recipeId: "hoffmann-v60",
      brewer: "Hario V60",
      brewerId: "hario-v60-02",
      beanLabel: "Square Mile — Red Brick",
      daysOffRoast: 12,
      grinderLabel: "Baratza Vario",
      grindSetting: "5,5",
      grindClicks: null,
      grindValue: 5.5,
      waterId: "third-wave-classic",
      totalTimeSec: 190,
      drawdownSec: 40,
      startedAt: now,
    });
    const clicks = buildBrewRow({ recipe: recipe(), setup: { ...setup, grinderId: 2, grindClicks: 75 }, bean: null, grinder: linkedKUltra(), settings: DEFAULT_SETTINGS, result: { totalTimeSec: 1, drawdownSec: null }, now });
    expect(clicks).toMatchObject({ grindClicks: 75, grindSetting: "75 clicks (dial 7.5)", grindValue: null, waterId: null, beanId: null });
  });
});

describe("v4 grind kinds (§4.2)", () => {
  const now = Date.UTC(2026, 10, 19, 8, 0);
  const blade = (): GrinderRow => ({ ...newGrinder({ brand: "Any", model: "Blade grinder", settingStyle: "stepless", settingUnit: "s", catalogId: "blade-grinder" }), id: 7 });
  const vario = (): GrinderRow => ({ ...newGrinder({ brand: "Baratza", model: "Vario", settingStyle: "numbers" }), id: 9, isDefault: true });
  const groundBag = { ...newBean({ name: "Supermarket", roaster: "Folgers", form: "ground" }), id: 5 };
  const wholeBag = { ...newBean({ name: "Red Brick", roaster: "Square Mile", form: "whole" }), id: 6 };

  it("pre-ground is chosen for a ground coffee, or when buying ground with no grinder", () => {
    expect(initialSetup(recipe(), [groundBag], [vario()], DEFAULT_SETTINGS, []).preground).toBe(true);
    expect(initialSetup(recipe(), [wholeBag], [vario()], DEFAULT_SETTINGS, []).preground).toBe(false);
    expect(initialSetup(recipe(), [], [], { ...DEFAULT_SETTINGS, buysGround: true }, []).preground).toBe(true);
    // Buying ground but owning a grinder: the grinder stays the default until the coffee says otherwise.
    expect(initialSetup(recipe(), [], [vario()], { ...DEFAULT_SETTINGS, buysGround: true }, []).preground).toBe(false);
  });

  it("switching coffee follows its form", () => {
    const s = initialSetup(recipe(), [wholeBag, groundBag], [vario()], DEFAULT_SETTINGS, []);
    const toGround = withBean(s, groundBag, DEFAULT_SETTINGS, [vario()]);
    expect(toGround).toMatchObject({ beanId: 5, preground: true });
    expect(withBean(toGround, wholeBag, DEFAULT_SETTINGS, [vario()])).toMatchObject({ beanId: 6, preground: false });
  });

  it("stores grindKind, grindSetting and grindValue on save", () => {
    const settings = DEFAULT_SETTINGS;
    const result = { totalTimeSec: 200, drawdownSec: null };
    const pre = buildBrewRow({ recipe: recipe(), setup: blank({ grinderId: 9, preground: true, grindSetting: "5" }), bean: groundBag, grinder: vario(), settings, result, now });
    expect(pre).toMatchObject({ grindKind: "preground", grindSetting: AS_BOUGHT, grindValue: null, grindClicks: null, grinderId: null, grinderLabel: "" });

    const bl = buildBrewRow({ recipe: recipe(), setup: blank({ grinderId: 7, bladeSec: 12 }), bean: wholeBag, grinder: blade(), settings, result, now });
    expect(bl).toMatchObject({ grindKind: "blade", grindSetting: "12 s", grindValue: 12, grindClicks: null, grinderId: 7 });

    const dial = buildBrewRow({ recipe: recipe(), setup: blank({ grinderId: 9, grindSetting: "5.5" }), bean: null, grinder: vario(), settings, result, now });
    expect(dial).toMatchObject({ grindKind: "dial", grindSetting: "5.5", grindValue: 5.5 });

    const clicks = buildBrewRow({ recipe: recipe(), setup: blank({ grinderId: 2, grindClicks: 75 }), bean: null, grinder: linkedKUltra(), settings, result, now });
    expect(clicks).toMatchObject({ grindKind: "dial", grindClicks: 75 });

    const typed = buildBrewRow({ recipe: recipe(), setup: blank({ grinderId: null, grindSetting: "medium" }), bean: null, grinder: null, settings, result, now });
    expect(typed).toMatchObject({ grindKind: "", grindSetting: "medium", grinderId: null });
    expect(grindKindOf({ preground: false }, blade())).toBe("blade");
  });

  it("a blade grinder prefills 12 s, else the last time for this recipe", () => {
    expect(prefillGrind(blank({ grinderId: 7 }), recipe(), [blade()], []).bladeSec).toBe(12);
    const brews = [log({ recipeId: "hoffmann-v60", grinderId: 7, grindKind: "blade", grindValue: 15 }, 3)];
    expect(prefillGrind(blank({ grinderId: 7 }), recipe(), [blade()], brews).bladeSec).toBe(15);
  });

  it("an explicit water scales every target; a new dose keeps that ratio", () => {
    const r = recipe({ steps: [{ kind: "Pour", title: "Pour", detail: "", startSec: 0, endSec: 120, targetWaterG: 250 }] });
    expect(setupRecipe(r, { doseG: 15, waterG: null }).waterG).toBe(250);
    const given = setupRecipe(r, { doseG: 15, waterG: 270 });
    expect(given.waterG).toBe(270);
    expect(given.steps[0]!.targetWaterG).toBe(270);
    expect(withDose(blank({ doseG: 15, waterG: 270 }), 20)).toMatchObject({ doseG: 20, waterG: 360 });
    expect(withDose(blank({ doseG: 15 }), 20).waterG).toBeNull();
  });
});
