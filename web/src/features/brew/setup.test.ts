import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newBean, newBrew, newGrinder, type BrewRow, type GrinderRow } from "@/data/schema";
import { DEFAULT_SETTINGS } from "@/data/settings";
import { makeBrewer } from "@/domain/core/brewer";
import { epochDayOf } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { buildBrewRow, describeClicks, initialSetup, lastTrustedClicks, minClicks, prefillGrind } from "./setup";

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
    const base = { doseG: 15, tempC: 94, beanId: null, grinderId: 2, grindClicks: null, grindSetting: "" };
    expect(prefillGrind(base, recipe(), [g], []).grindClicks).toBe(70);
    expect(prefillGrind(base, recipe({ brewer: ESPRESSO }), [g], []).grindClicks).toBe(25);
    expect(prefillGrind(base, recipe(), [linkedKUltra()], []).grindClicks).toBeNull();
  });

  it("stepless grinders prefill the typed setting", () => {
    const g = { ...newGrinder({ brand: "Baratza", model: "Vario", settingStyle: "numbers", defaultFilterSetting: 5.5 }), id: 9 };
    const s = prefillGrind({ doseG: 15, tempC: 94, beanId: null, grinderId: 9, grindClicks: null, grindSetting: "" }, recipe(), [g], []);
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
