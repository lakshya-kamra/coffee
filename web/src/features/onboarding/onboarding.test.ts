// Each onboarding step persists on its own (A§7.3), and "Run setup again" reuses a grinder you
// already have instead of adding a duplicate. Runs against fake-indexeddb (src/test/setup.ts).
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo } from "@/data/repo";
import { newGrinder } from "@/data/schema";
import { settings } from "@/data/settings";
import { store } from "@/data/store";
import { firstBrew } from "@/domain/core/firstBrew";
import { Brewers } from "@/catalog/catalog";
import { BLADE_GRINDER_ID, COMMON_BREWER_IDS, EXPERIENCE_CHOICES, finish, PLAIN_WATERS, presetsFor, saveBrewers, saveCoffee, saveGrinder, saveMeasure, saveYou, STEPS } from "./persist";

beforeAll(async () => {
  await catalog.load(["brewers", "grinders", "water", "recipes"]);
});

beforeEach(async () => {
  store.resetForTests();
  await db.delete();
  await db.open();
});

afterEach(() => {
  store.stop();
});

describe("onboarding persistence", () => {
  it("saves step 1 alone, leaving everything else at defaults", async () => {
    await saveMeasure({ tempUnit: "f", weightUnit: "oz", homeArea: "Europe" });
    const s = await settings.get();
    expect(s).toMatchObject({ tempUnit: "f", weightUnit: "oz", homeArea: "Europe", onboardingDone: false, ownedBrewerIds: [] });
  });

  it("saves the catalog grinder as the default, with the catalog's scale", async () => {
    if (!catalog.grinder("1zpresso-k-ultra")) return;
    const id = await saveGrinder({ kind: "catalog", id: "1zpresso-k-ultra" }, []);
    expect(id).not.toBeNull();
    const rows = await db.grinders.toArray();
    expect(rows).toHaveLength(1);
    // Lead decision 2: 100 clicks a turn, 20 µm a click, dial format left on auto.
    expect(rows[0]).toMatchObject({ brand: "1Zpresso", model: "K-Ultra", catalogId: "1zpresso-k-ultra", clicksPerRotation: 100, micronsPerClick: 20, isDefault: true, dialFormat: "" });
    expect((await settings.get()).defaultGrinderId).toBe(id);
  });

  it("makes an existing grinder the default instead of inserting a duplicate", async () => {
    if (!catalog.grinder("1zpresso-k-ultra")) return;
    const other = await repo.upsertGrinder(newGrinder({ brand: "Comandante", model: "C40", isDefault: true }));
    const mine = await repo.upsertGrinder(newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 10, catalogId: "1zpresso-k-ultra" }));
    const id = await saveGrinder({ kind: "catalog", id: "1zpresso-k-ultra" }, await db.grinders.toArray());
    expect(id).toBe(mine);
    const rows = await db.grinders.toArray();
    expect(rows).toHaveLength(2);
    expect(rows.find((g) => g.id === mine)).toMatchObject({ isDefault: true, clicksPerRotation: 10 }); // numbers untouched: that is the scale notice's job
    expect(rows.find((g) => g.id === other)?.isDefault).toBe(false);
  });

  it("saves a custom grinder typed by hand", async () => {
    const id = await saveGrinder({ kind: "custom", brand: "Acme", model: "Mill 2", settingStyle: "clicks", clicksPerRotation: 30 }, []);
    expect(await db.grinders.get(id!)).toMatchObject({ brand: "Acme", model: "Mill 2", clicksPerRotation: 30, catalogId: null, isDefault: true });
  });

  it("the new first step sets experience and its presets, one at a time", async () => {
    expect(STEPS).toEqual(["you", "measure", "grinder", "brewers", "coffee", "done"]);
    expect(EXPERIENCE_CHOICES.map((c) => c.title)).toEqual(["I'm new to this", "I follow recipes", "I tinker"]);
    expect(presetsFor("new")).toEqual({ reviewMode: "quick", soundCues: true, hasScale: false });
    expect(presetsFor("recipes")).toEqual({ reviewMode: "quick" });
    expect(presetsFor("tinker")).toEqual({ reviewMode: "detailed" });

    await saveYou("tinker");
    expect(await settings.get()).toMatchObject({ experience: "tinker", reviewMode: "detailed", soundCues: false, hasScale: true, onboardingDone: false });
    // The scale is the measure step's question: "new" does not answer it here.
    await saveYou("new");
    expect(await settings.get()).toMatchObject({ experience: "new", reviewMode: "quick", soundCues: true, hasScale: true });
  });

  it("the measure step saves the scale answer with the units", async () => {
    await saveMeasure({ tempUnit: "c", weightUnit: "g", homeArea: "", hasScale: false });
    expect((await settings.get()).hasScale).toBe(false);
  });

  it("I buy ground coffee saves no grinder; a grinder turns it off again", async () => {
    expect(await saveGrinder({ kind: "ground" }, [])).toBeNull();
    expect(await db.grinders.count()).toBe(0);
    expect((await settings.get()).buysGround).toBe(true);
    if (!catalog.grinder(BLADE_GRINDER_ID)) return;
    const id = await saveGrinder({ kind: "catalog", id: BLADE_GRINDER_ID }, []);
    expect(await db.grinders.get(id!)).toMatchObject({ model: "Blade grinder", settingUnit: "s", settingStyle: "stepless", isDefault: true });
    expect((await settings.get()).buysGround).toBe(false);
  });

  it("lists the most common brewers in the v4 order, all in the catalog", () => {
    expect(COMMON_BREWER_IDS).toEqual(["drip-machine", "french-press", "hario-v60-02", "aeropress", "moka-pot", "espresso-machine", "drip-bag", "south-indian-filter", "phin", "cold-brew-jar"]);
    for (const id of COMMON_BREWER_IDS) expect(catalog.brewer(id), id).toBeTruthy();
  });

  it("maps the four plain waters to catalog ids, and Not sure to none", () => {
    expect(PLAIN_WATERS.map((w) => w.label)).toEqual(["Tap water", "Filtered tap water", "Bottled spring water", "Not sure"]);
    for (const w of PLAIN_WATERS) if (w.waterId) expect(catalog.waterById(w.waterId), w.waterId).toBeTruthy();
    expect(PLAIN_WATERS[3]!.waterId).toBe("");
  });

  it("a beginner run (new, no scale, ground coffee, drip machine + French press) ends on a first brew that is not a V60", async () => {
    await saveYou("new");
    await saveMeasure({ tempUnit: "c", weightUnit: "g", homeArea: "", hasScale: false });
    await saveGrinder({ kind: "ground" }, []);
    await saveBrewers(["drip-machine", "french-press"]);
    await saveCoffee({ favouriteRoasterIds: [], defaultWaterId: "tap-unfiltered" });
    await finish();
    const st = await settings.get();
    expect(st).toMatchObject({ experience: "new", hasScale: false, buysGround: true, reviewMode: "quick", onboardingDone: true });
    const pick = firstBrew(catalog.recipes, st, [], (id) => Brewers.byId(id));
    expect(pick.recipe).not.toBeNull();
    expect(pick.recipe!.brewer.id).not.toMatch(/v60/);
    expect(["drip-machine", "french-press"]).toContain(pick.recipe!.brewer.id);
  });

  it("saves brewers, then coffee, then finishes", async () => {
    await saveBrewers(["hario-v60-02", "aeropress"]);
    expect((await settings.get()).ownedBrewerIds).toEqual(["hario-v60-02", "aeropress"]);
    await saveCoffee({ favouriteRoasterIds: ["square-mile"], defaultWaterId: "tap-filtered" });
    let s = await settings.get();
    expect(s).toMatchObject({ favouriteRoasterIds: ["square-mile"], defaultWaterId: "tap-filtered", ownedBrewerIds: ["hario-v60-02", "aeropress"], onboardingDone: false });
    await finish();
    s = await settings.get();
    expect(s.onboardingDone).toBe(true);
  });
});
