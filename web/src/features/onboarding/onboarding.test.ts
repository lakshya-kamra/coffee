// Each onboarding step persists on its own (A§7.3), and "Run setup again" reuses a grinder you
// already have instead of adding a duplicate. Runs against fake-indexeddb (src/test/setup.ts).
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo } from "@/data/repo";
import { newGrinder } from "@/data/schema";
import { settings } from "@/data/settings";
import { store } from "@/data/store";
import { finish, saveBrewers, saveCoffee, saveGrinder, saveMeasure } from "./persist";

beforeAll(async () => {
  await catalog.load(["brewers", "grinders"]);
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
