// Repository cascades and stamps, settings defaults and the hot store's selective updates,
// against an in-memory IndexedDB (fake-indexeddb, see src/test/setup.ts).
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { makeBrewer } from "@/domain/core/brewer";
import type { Recipe } from "@/domain/core/recipe";
import { db } from "./db";
import { mergeRecipes, repo } from "./repo";
import { newBean, newBrew, newGrinder, type AnalysisRow, type PhotoRow } from "./schema";
import { aiPrefs, DEFAULT_SETTINGS, settings } from "./settings";
import { store } from "./store";

function photo(p: Partial<PhotoRow>): PhotoRow {
  return { uid: crypto.randomUUID(), brewLogId: null, beanId: null, kind: "bed", mediaType: "image/jpeg", widthPx: 10, heightPx: 10, bytes: 3, takenAt: 1, createdAt: 1, caption: "", sendToAi: true, ...p };
}

function analysis(p: Partial<AnalysisRow>): AnalysisRow {
  return {
    uid: crypto.randomUUID(), kind: "brew", brewLogId: null, createdAt: 1, promptVersion: 2, inputHash: "", brewCount: 1, newestBrewAt: 1, model: "m", host: "h",
    rawText: "", result: null, flags: [], promptTokens: null, completionTokens: null, costMicroUsd: null, latencyMs: 0, providerId: "custom", photoCount: 0, origin: "web", ...p,
  };
}

const blob = () => new Blob([new Uint8Array([1, 2, 3])], { type: "image/jpeg" });

beforeAll(async () => {
  await catalog.load(["brewers"]);
});

beforeEach(async () => {
  store.resetForTests();
  await db.delete();
  await db.open();
});

afterEach(() => {
  store.stop();
});

describe("repo", () => {
  it("stamps updatedAt on writes", async () => {
    const before = Date.now();
    const id = await repo.insertBrew(newBrew({ recipeName: "A" }, 1000));
    const b = await db.brew_logs.get(id);
    expect(b!.updatedAt).toBeGreaterThanOrEqual(before);
    expect(b!.startedAt).toBe(1000);
    const beanId = await repo.upsertBean(newBean({ name: "Red Brick" }, 5));
    expect((await db.beans.get(beanId))!.updatedAt).toBeGreaterThanOrEqual(before);
  });

  it("deleteBrew removes its analyses, photos and image bytes in one go", async () => {
    const id = await repo.insertBrew(newBrew());
    const other = await repo.insertBrew(newBrew());
    const p = photo({ brewLogId: id });
    await repo.addPhoto(p, blob(), blob());
    await repo.addPhoto(photo({ brewLogId: other }), blob(), blob());
    await repo.insertAnalysis(analysis({ brewLogId: id }));
    await repo.insertAnalysis(analysis({ brewLogId: other }));
    await repo.deleteBrew(id);
    expect(await db.brew_logs.count()).toBe(1);
    expect(await db.analyses.count()).toBe(1);
    expect(await db.photos.count()).toBe(1);
    expect(await db.photo_blobs.get(p.uid)).toBeUndefined();
    expect(await db.photo_blobs.count()).toBe(1);
  });

  it("deleteBean removes its photos; brews keep beanId and their label snapshot", async () => {
    const beanId = await repo.upsertBean(newBean({ name: "Kayon" }));
    const brewId = await repo.insertBrew(newBrew({ beanId, beanLabel: "Kayon" }));
    await repo.addPhoto(photo({ beanId, kind: "bag" }), blob(), blob());
    await repo.deleteBean(beanId);
    expect(await db.photos.count()).toBe(0);
    expect(await db.photo_blobs.count()).toBe(0);
    const brew = await db.brew_logs.get(brewId);
    expect(brew!.beanId).toBe(beanId);
    expect(brew!.beanLabel).toBe("Kayon");
  });

  it("one default grinder at a time; archiving clears default", async () => {
    const a = await repo.upsertGrinder(newGrinder({ brand: "1Zpresso", model: "K-Ultra", isDefault: true }));
    const b = await repo.upsertGrinder(newGrinder({ brand: "Comandante", model: "C40" }));
    await repo.setDefaultGrinder(b);
    expect((await db.grinders.get(a))!.isDefault).toBe(false);
    expect((await db.grinders.get(b))!.isDefault).toBe(true);
    await repo.archiveGrinder(b);
    expect((await db.grinders.get(b))!.isDefault).toBe(false);
  });

  it("isEmpty, latestTrend and wipeAll", async () => {
    expect(await repo.isEmpty()).toBe(true);
    await repo.insertBrew(newBrew());
    expect(await repo.isEmpty()).toBe(false);
    await repo.insertAnalysis(analysis({ kind: "trend", createdAt: 5 }));
    await repo.insertAnalysis(analysis({ kind: "trend", createdAt: 9 }));
    await repo.insertAnalysis(analysis({ kind: "brew", createdAt: 20 }));
    expect((await repo.latestTrend())!.createdAt).toBe(9);
    await settings.set("name", "Lakshya");
    await repo.wipeAll();
    expect(await repo.isEmpty()).toBe(true);
    expect((await settings.get()).name).toBe("");
  });
});

describe("settings", () => {
  it("defaults as Android, sound cues off on the web, no AI provider on a fresh install", async () => {
    const s = await settings.get();
    expect(s).toEqual(DEFAULT_SETTINGS);
    expect(s.soundCues).toBe(false);
    expect(s.haptics && s.keepScreenOn && s.autoAdvanceSteps).toBe(true);
    const ai = await aiPrefs.get();
    expect(ai.activeProvider).toBe("");
    expect(ai.sendPhotos).toBe(true);
  });

  it("set, notices", async () => {
    await settings.set("tempUnit", "f");
    await settings.addNotice("grinder-scale:abc");
    await settings.addNotice("grinder-scale:abc");
    expect((await settings.get()).pendingNotices).toEqual(["grinder-scale:abc"]);
    await settings.removeNotice("grinder-scale:abc");
    const s = await settings.get();
    expect(s.pendingNotices).toEqual([]);
    expect(s.tempUnit).toBe("f");
  });
});

describe("hot store", () => {
  async function until(pred: () => boolean, ms = 2000) {
    const start = Date.now();
    while (!pred()) {
      if (Date.now() - start > ms) throw new Error("timed out waiting for the store");
      await new Promise((r) => setTimeout(r, 10));
    }
  }

  it("loads before first render and follows writes", async () => {
    await repo.upsertBean(newBean({ name: "Red Brick" }));
    await store.init();
    expect(store.get().ready).toBe(true);
    expect(store.get().beans.map((b) => b.name)).toEqual(["Red Brick"]);
    await repo.insertBrew(newBrew({ recipeName: "Later", startedAt: 2000 }));
    await repo.insertBrew(newBrew({ recipeName: "Earlier", startedAt: 1000 }));
    await until(() => store.get().brews.length === 2);
    expect(store.get().brews.map((b) => b.recipeName)).toEqual(["Later", "Earlier"]);
  });

  it("does not re-emit unrelated slices", async () => {
    await repo.upsertBean(newBean({ name: "Red Brick" }));
    await store.init();
    await until(() => store.get().beans.length === 1);
    const beans = store.get().beans;
    const s = store.get().settings;
    const ai = store.get().aiPrefs;
    await repo.insertBrew(newBrew());
    await until(() => store.get().brews.length === 1);
    expect(store.get().beans).toBe(beans);
    expect(store.get().settings).toBe(s);
    // A kv write that changes only the active brew keeps settings and AI prefs identical.
    await repo.setActiveBrew({ recipeId: "x", startedAtWall: 1, accumulatedMs: 0, running: true, setup: { doseG: 15, beanId: null, grinderId: null, grindClicks: null, grindSetting: "", tempC: 94 }, stepIndex: 0, lastPourEndSec: null, bedExposedSec: null });
    await until(() => store.get().activeBrew != null);
    expect(store.get().settings).toBe(s);
    expect(store.get().aiPrefs).toBe(ai);
    await settings.set("name", "L");
    await until(() => store.get().settings.name === "L");
    expect(store.get().beans).toBe(beans);
  });
});

describe("recipe merge (CoffeeRepository.recipes)", () => {
  const r = (id: string, name = id): Recipe =>
    ({ id, name, author: "", brewer: makeBrewer({ id: "chemex", label: "Chemex" }), doseG: 1, waterG: 2, grind: "", tempC: 90, blurb: "", sourceNote: "", prep: [], tips: [], steps: [], accent: 0xff000000, isBuiltIn: true, fidelity: "", sources: [], basedOn: null });
  it("built-ins in order, a saved edit replaces its original, then the user's own", () => {
    const merged = mergeRecipes([r("a"), r("b"), r("c")], [r("custom-1"), r("b", "My b")]);
    expect(merged.map((x) => `${x.id}:${x.name}`)).toEqual(["a:a", "b:My b", "c:c", "custom-1:custom-1"]);
  });
});
