// Backup import/export against an in-memory IndexedDB (web-spec §15.5): round trip, no key in a
// file, re-import is a no-op, replace mode, newer-wins, settings and AI only when ticked, the 1.x
// fixture and (when COFFEE_OWNER_EXPORT names one) a real 1.x export, read in place.
import { existsSync, readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo } from "@/data/repo";
import { newBean, newBrew, newGrinder, type AnalysisRow, type BrewRow, type PhotoRow } from "@/data/schema";
import { aiPrefs, settings } from "@/data/settings";
import { brewRowIds, brewUidsFor, buildBrewPrompt, buildTrendPrompt, followBrews, readStoredResult, staleReason, VERSION, type TrendAnalysis } from "@/domain/ai";
import { MAX_IMPORT_BYTES } from "@/domain/backup/model";
import { bytesToBase64, readBackupText, type ReadResult } from "@/domain/backup/read";
import { applyBackup, type ImportOptions } from "./apply";
import { previewLine, readErrorText, resultLine, savedMessage } from "./copy";
import { aboutSize, backupFileName, buildBackup, MAX_BACKUP_BYTES, photosThatFit } from "./exporter";
import { runStartupLinking } from "./linking";
import { readBackupFile } from "./workerClient";

const fixture = (name: string) => new URL(`../../../../shared/fixtures/${name}`, import.meta.url);
const readFixture = (name: string): string | null => (existsSync(fixture(name)) ? readFileSync(fixture(name), "utf8") : null);
// A real 1.x export to test against: read in place, never copied into the repo. Its path comes
// from the environment (COFFEE_OWNER_EXPORT), so no machine's folders are written down here.
const OWNER_EXPORT = process.env.COFFEE_OWNER_EXPORT ?? "";
const HAS_OWNER_EXPORT = OWNER_EXPORT !== "" && existsSync(OWNER_EXPORT);

const ADD: ImportOptions = { mode: "add", bringSettings: false, bringAi: false };

async function read(text: string): Promise<Extract<ReadResult, { ok: true }>> {
  const r = await readBackupFile(new Blob([text], { type: "application/json" }));
  if (!r.ok) throw new Error(`read failed: ${r.error}`);
  return r;
}

/** Every table's rows minus local ids and with references by uid, so two browsers compare equal. */
async function snapshot() {
  const [beans, grinders, brews, saved, analyses, photos, blobs] = await Promise.all([
    db.beans.toArray(), db.grinders.toArray(), db.brew_logs.toArray(), db.saved_recipes.toArray(), db.analyses.toArray(), db.photos.toArray(), db.photo_blobs.toArray(),
  ]);
  const beanUid = new Map(beans.map((b) => [b.id, b.uid]));
  const grinderUid = new Map(grinders.map((g) => [g.id, g.uid]));
  const brewUid = new Map(brews.map((l) => [l.id, l.uid]));
  const byUid = <T extends { uid: string }>(a: T[]) => [...a].sort((x, y) => x.uid.localeCompare(y.uid));
  return {
    beans: byUid(beans.map(({ id: _id, ...b }) => b)),
    grinders: byUid(grinders.map(({ id: _id, ...g }) => g)),
    brews: byUid(brews.map(({ id: _id, beanId, grinderId, ...l }) => ({ ...l, bean: beanId != null ? (beanUid.get(beanId) ?? null) : null, grinder: grinderId != null ? (grinderUid.get(grinderId) ?? null) : null }))),
    saved: [...saved].sort((a, b) => a.id.localeCompare(b.id)),
    analyses: byUid(analyses.map(({ id: _id, brewLogId, ...a }) => ({ ...a, brew: brewLogId != null ? (brewUid.get(brewLogId) ?? null) : null }))),
    photos: byUid(photos.map(({ id: _id, brewLogId, beanId, ...p }) => ({ ...p, brew: brewLogId != null ? brewUid.get(brewLogId) : null, bean: beanId != null ? beanUid.get(beanId) : null }))),
    blobs: await Promise.all(byUid(blobs).map(async (b) => ({ uid: b.uid, full: [...new Uint8Array(await b.full.arrayBuffer())] }))),
  };
}

/** The smallest thing the importer accepts as a stored photo: SOI, a frame header (4 × 3 px), some bytes, EOI. */
const jpegBytes = (body: number[] = [1, 2, 3, 250], app1 = false, w = 4, h = 3) =>
  new Uint8Array([0xff, 0xd8, ...(app1 ? [0xff, 0xe1, 0, 4, 0x45, 0x78] : []), 0xff, 0xc0, 0, 11, 8, h >> 8, h & 255, w >> 8, w & 255, 1, 1, 0x11, 0, ...body, 0xff, 0xd9]);
const jpeg = () => new Blob([jpegBytes()], { type: "image/jpeg" });

async function seed() {
  const beanId = await repo.upsertBean(newBean({ name: "Red Brick", roaster: "Square Mile", roastedOnEpochDay: 20755, varietalIds: ["gesha"], processId: "washed", priceAmount: 12.5, currency: "GBP" }));
  const kultra = await repo.upsertGrinder(newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra", dialFormat: "number-tick:10", isDefault: true, maxSetting: 200 }));
  await repo.upsertGrinder(newGrinder({ brand: "Old", model: "Mill", settingStyle: "numbers", isArchived: true }));
  const brewId = await repo.insertBrew(newBrew({ beanId, grinderId: kultra, beanLabel: "Square Mile — Red Brick", grinderLabel: "1Zpresso K-Ultra", grindClicks: 75, recipeId: "hoffmann-v60", brewer: "Hario V60", brewerId: "hario-v60-02", flowFeel: "fast", bedShape: "domed", sludge: "light", channeling: null, overall: 7, descriptors: ["Floral"], isFavourite: true, tdsPercent: 1.4 }, 1_700_000_000_000));
  await repo.insertBrew(newBrew({ recipeName: "No bean", doseG: 18, waterG: 36 }, 1_700_000_100_000));
  const hoffmann = catalog.recipe("hoffmann-v60")!;
  await repo.saveRecipe({ ...hoffmann, doseG: 15, waterG: 250 });
  await repo.saveRecipe({ ...hoffmann, id: "custom-1", name: "My V60", isBuiltIn: false });
  await repo.insertAnalysis({
    uid: crypto.randomUUID(), kind: "brew", brewLogId: brewId, createdAt: 1_700_000_050_000, promptVersion: 2, inputHash: "abc", brewCount: 1, newestBrewAt: 1_700_000_000_000,
    model: "claude-haiku-5-5", host: "api.anthropic.com", rawText: '{"v":1}', result: { v: 1 }, flags: [], promptTokens: 100, completionTokens: 20, costMicroUsd: 30, latencyMs: 900, providerId: "anthropic", photoCount: 1, origin: "web",
  });
  await repo.insertAnalysis({
    uid: crypto.randomUUID(), kind: "trend", brewLogId: null, createdAt: 1_700_000_060_000, promptVersion: 2, inputHash: "def", brewCount: 2, newestBrewAt: 1_700_000_100_000,
    model: "m", host: "h", rawText: "x", result: null, flags: [{ code: "GRIND_TOO_FINE_FOR_METHOD" }], promptTokens: null, completionTokens: null, costMicroUsd: null, latencyMs: 1, providerId: "custom", photoCount: 0, origin: "android",
  });
  const photo: PhotoRow = { uid: crypto.randomUUID(), brewLogId: brewId, beanId: null, kind: "bed", mediaType: "image/jpeg", widthPx: 4, heightPx: 3, bytes: jpegBytes().length, takenAt: 1_700_000_001_000, createdAt: 1_700_000_002_000, caption: "Bed", sendToAi: true };
  await repo.addPhoto(photo, jpeg(), jpeg());
  await repo.addPhoto({ ...photo, uid: crypto.randomUUID(), brewLogId: null, beanId, kind: "bag", caption: "" }, jpeg(), jpeg());
  await settings.update({ defaultBeanId: beanId, defaultGrinderId: kultra, tempUnit: "f", name: "Sam", ownedBrewerIds: ["hario-v60-02"], onboardingDone: true });
  await aiPrefs.update((a) => ({ ...a, activeProvider: "anthropic", providers: { anthropic: { model: "claude-haiku-5-5", vision: null, testOkAt: 5 } } }));
  return { beanId, kultra, brewId };
}

beforeAll(async () => {
  await catalog.load();
});

beforeEach(async () => {
  await db.delete();
  await db.open();
});

describe("export then import", () => {
  it("round-trips every table through a file, photos included, into an empty browser", async () => {
    await seed();
    const before = await snapshot();
    const built = await buildBackup({ includePhotos: true, includeAnalyses: true, now: Date.UTC(2026, 10, 17, 9, 0) });
    expect(built.fileName).toMatch(/^coffee-backup-\d{4}-\d{2}-\d{2}\.json$/);
    expect(built.counts).toEqual({ brews: 2, beans: 1, grinders: 2, recipes: 2, analyses: 2, photos: 2 });
    const text = await built.blob.text();
    expect(() => JSON.parse(text) as unknown).not.toThrow();

    await repo.wipeAll();
    const r = await read(text);
    expect(r.preview.platform).toBe("web");
    expect(r.preview.photosWithData).toBe(2);
    const result = await applyBackup(r, { mode: "add", bringSettings: true, bringAi: true });
    expect(result.added).toEqual(built.counts);
    expect(result.skipped).toEqual([]);
    expect(await snapshot()).toEqual(before);

    // Settings came across with ids remapped to this browser's rows.
    const s = await settings.get();
    const bean = await db.beans.toCollection().first();
    const grinder = (await db.grinders.toArray()).find((g) => g.model === "K-Ultra");
    expect(s).toMatchObject({ tempUnit: "f", name: "Sam", defaultBeanId: bean!.id, defaultGrinderId: grinder!.id, onboardingDone: true });
    const ai = await aiPrefs.get();
    expect(ai.activeProvider).toBe("anthropic");
    expect(ai.providers.anthropic).toEqual({ model: "claude-haiku-5-5", vision: null });
  });

  it("writes photo data null when saved without photos, and the import says so", async () => {
    await seed();
    const built = await buildBackup({ includePhotos: false, includeAnalyses: true });
    const doc = JSON.parse(await built.blob.text()) as { photos: { data: unknown }[]; options: { includesPhotos: boolean } };
    expect(doc.options.includesPhotos).toBe(false);
    expect(doc.photos.every((p) => p.data === null)).toBe(true);
    await repo.wipeAll();
    const result = await applyBackup(await read(JSON.stringify(doc)), ADD);
    expect(result.added.photos).toBe(0);
    expect(result.skipped).toEqual(["2 photos aren't in the file"]);
  });

  it("never writes a file too big to restore: the newest photos that fit keep their image", async () => {
    const { brewId } = await seed();
    // A third, newer and much bigger photo than the two 10-byte ones from seed().
    const big = new Blob([jpegBytes(new Array<number>(3000).fill(7))], { type: "image/jpeg" });
    const bigUid = crypto.randomUUID();
    await repo.addPhoto(
      { uid: bigUid, brewLogId: brewId, beanId: null, kind: "cup", mediaType: "image/jpeg", widthPx: 4, heightPx: 3, bytes: big.size, takenAt: 1_800_000_000_000, createdAt: 1_800_000_000_000, caption: "", sendToAi: true },
      big,
      jpeg(),
    );
    const without = await buildBackup({ includePhotos: false, includeAnalyses: true });
    const whole = await buildBackup({ includePhotos: true, includeAnalyses: true });
    expect(whole.photosLeftOut).toBe(0);

    // Room for the two small photos but not the big one.
    const maxBytes = without.blob.size + 1000;
    const built = await buildBackup({ includePhotos: true, includeAnalyses: true, maxBytes });
    expect(built.blob.size).toBeLessThanOrEqual(maxBytes);
    expect(built.photosLeftOut).toBe(1);
    const doc = JSON.parse(await built.blob.text()) as { photos: { uid: string; data: string | null }[] };
    expect(doc.photos).toHaveLength(3);
    expect(doc.photos.find((p) => p.uid === bigUid)!.data).toBeNull();
    expect(doc.photos.filter((p) => p.data != null)).toHaveLength(2);

    // The file still restores, and says which photos stayed behind.
    await repo.wipeAll();
    const r = await read(await built.blob.text());
    expect(r.preview.photosWithData).toBe(2);
    const result = await applyBackup(r, ADD);
    expect(result.added.photos).toBe(2);
    expect(result.skipped).toEqual(["1 photo isn't in the file"]);
  });

  it("keeps the newest photos when not all fit, passing over one that is too big", () => {
    const photos = [
      { uid: "old", createdAt: "2026-01-01T00:00:00.000Z", bytes: 40 },
      { uid: "mid", createdAt: "2026-06-01T00:00:00.000Z", bytes: 90 },
      { uid: "new", createdAt: "2026-11-01T00:00:00.000Z", bytes: 50 },
    ];
    expect([...photosThatFit(photos, 1000)].sort()).toEqual(["mid", "new", "old"]);
    expect([...photosThatFit(photos, 100)].sort()).toEqual(["new", "old"]);
    expect([...photosThatFit(photos, 10)]).toEqual([]);
  });

  it("says what a save left out, and never calls an unrestorable file a backup", () => {
    const counts = { brews: 52, beans: 4, grinders: 1, recipes: 0, analyses: 0, photos: 300 };
    expect(savedMessage("coffee-backup-2026-11-18.json", counts, 0, 5_000_000)).toEqual({ status: "good", lead: "Saved", rest: "coffee-backup-2026-11-18.json · 52 brews, 4 coffees, 1 grinder, 300 photos" });
    expect(savedMessage("coffee-backup-2026-11-18.json", counts, 60, 60_000_000)).toEqual({
      status: "attention",
      lead: "Saved",
      rest: "coffee-backup-2026-11-18.json · 52 brews, 4 coffees, 1 grinder, 240 photos · 60 older photos left out: a backup over 64 MB can't be restored",
    });
    expect(savedMessage("x.json", counts, 0, 70 * 1024 * 1024).status).toBe("fault");
    expect(MAX_BACKUP_BYTES).toBeLessThan(MAX_IMPORT_BYTES);
  });

  it("stores a photo from a file only when it is a clean stored-size JPEG, or was drawn again", async () => {
    const { brewId } = await seed();
    const brew = (await db.brew_logs.get(brewId))!;
    const b64 = (bytes: Uint8Array) => bytesToBase64(bytes);
    const doc = JSON.parse(await (await buildBackup({ includePhotos: false, includeAnalyses: false })).blob.text()) as { photos: Record<string, unknown>[] };
    const row = (uid: string, data: string) => ({ ...doc.photos[0], uid, brewUid: brew.uid, beanUid: null, data });
    doc.photos = [
      row("clean", b64(jpegBytes())),
      row("exif", b64(jpegBytes([9], true))), // an APP1 segment: where a camera puts the location
      row("huge", b64(jpegBytes([9], false, 4032, 3024))), // a full-size camera frame
      row("junk", b64(new TextEncoder().encode("<html>not a picture</html>"))),
    ];
    const text = JSON.stringify(doc);

    // No canvas here (as on the main-thread fallback): only the clean one is kept, the rest are named.
    const plain = await readBackupText(text, []);
    if (!plain.ok) throw new Error("read failed");
    expect(plain.photoBytes.map((p) => p != null)).toEqual([true, false, false, false]);
    expect([...new Uint8Array(await plain.photoBytes[0]!.full.arrayBuffer())]).toEqual([...jpegBytes()]);
    expect(plain.preview.problems).toEqual([1, 2, 3].map((i) => `photos[${i}]: not a photo this app can store`));
    expect(plain.preview.photosWithData).toBe(1);

    // With the worker's re-encoder: what it can draw is stored as its clean copy, never the original.
    const redrawn = jpegBytes([42]);
    const seen: number[] = [];
    const withCanvas = await readBackupText(text, [], {
      reencode: async (original) => {
        const bytes = new Uint8Array(await original.arrayBuffer());
        seen.push(bytes.length);
        if (bytes[0] !== 0xff) return null; // the junk does not decode
        return { full: new Blob([redrawn], { type: "image/jpeg" }), thumb: null, size: { widthPx: 1024, heightPx: 768 } };
      },
    });
    if (!withCanvas.ok) throw new Error("read failed");
    expect(seen).toHaveLength(3); // the clean photo never goes through it
    expect(withCanvas.photoBytes.map((p) => p != null)).toEqual([true, true, true, false]);
    expect(withCanvas.preview.problems).toEqual(["photos[3]: not a photo this app can store"]);
    await db.photos.clear();
    await db.photo_blobs.clear();
    await applyBackup(withCanvas, ADD);
    const stored = await db.photo_blobs.get("exif");
    expect([...new Uint8Array(await stored!.full.arrayBuffer())]).toEqual([...redrawn]);
    expect(await db.photos.where("uid").equals("huge").first()).toMatchObject({ widthPx: 1024, heightPx: 768, bytes: redrawn.length });
    expect(await db.photo_blobs.get("junk")).toBeUndefined();
  });

  it("never puts a saved API key in the file", async () => {
    await seed();
    const secret = "sk-ant-api03-THIS-IS-A-TEST-KEY-1234567890";
    // What WEB-3's keyStore writes (web-spec §14.2), in both storages.
    const store = new Map<string, string>();
    const shim = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k) };
    const saved = { local: Object.getOwnPropertyDescriptor(globalThis, "localStorage"), session: Object.getOwnPropertyDescriptor(globalThis, "sessionStorage") };
    Object.defineProperty(globalThis, "localStorage", { value: shim, configurable: true, writable: true });
    Object.defineProperty(globalThis, "sessionStorage", { value: shim, configurable: true, writable: true });
    try {
      const entry = JSON.stringify({ v: 1, host: "api.anthropic.com", key: secret, hint: "…7890", savedAt: 1 });
      localStorage.setItem("coffee.ai.key.anthropic", entry);
      sessionStorage.setItem("coffee.ai.key.custom", entry);
      const text = await (await buildBackup({ includePhotos: true, includeAnalyses: true })).blob.text();
      expect(text).not.toContain(secret);
      expect(text).not.toContain("7890");
      expect(text).not.toMatch(/"(apiKey|key)"\s*:/);
    } finally {
      for (const [name, d] of [["localStorage", saved.local], ["sessionStorage", saved.session]] as const) {
        if (d) Object.defineProperty(globalThis, name, d);
        else delete (globalThis as Record<string, unknown>)[name];
      }
    }
  });
});

describe("applying the shared v2 fixture", () => {
  const sample = readFixture("backup-v2-sample.json");

  it.skipIf(sample == null)("imports it; a second import changes nothing", async () => {
    const first = await applyBackup(await read(sample!), ADD);
    expect(first.added).toEqual({ brews: 2, beans: 1, grinders: 1, recipes: 1, analyses: 3, photos: 1 });
    const after1 = await snapshot();
    const settingsAfter1 = await settings.get();
    const second = await applyBackup(await read(sample!), ADD);
    expect(second.added).toEqual({ brews: 0, beans: 0, grinders: 0, recipes: 0, analyses: 0, photos: 0 });
    expect(second.replaced).toEqual({ brews: 0, beans: 0, grinders: 0, recipes: 0, analyses: 0, photos: 0 });
    expect(second.kept).toEqual(first.added);
    expect(await snapshot()).toEqual(after1);
    expect(await settings.get()).toEqual(settingsAfter1);
    expect(resultLine(second)).toBe("Nothing new · 9 already here");
    // Analyses made on Android keep their origin (lead decision 12).
    expect((await db.analyses.toArray()).every((a) => a.origin === "android")).toBe(true);
    // The custom recipe is a saved recipe that opens.
    expect((await db.saved_recipes.get("custom-1731398400000"))?.recipe.name).toBe("Sam's Sunday V60");
  });

  it.skipIf(sample == null)("settings and AI choices come only when ticked", async () => {
    await applyBackup(await read(sample!), ADD);
    expect((await settings.get()).name).toBe("");
    expect((await aiPrefs.get()).activeProvider).toBe("");
    expect((await settings.get()).onboardingDone).toBe(true); // data arrived
    await applyBackup(await read(sample!), { mode: "add", bringSettings: true, bringAi: true });
    const s = await settings.get();
    const bean = await db.beans.where("uid").equals("0b9a3c1e-5d2f-4a8b-9c7d-1e2f3a4b5c6d").first();
    expect(s).toMatchObject({ name: "Sam", homeArea: "Europe", defaultBeanId: bean!.id, favouriteRoasterIds: ["square-mile"] });
    const ai = await aiPrefs.get();
    expect(ai.activeProvider).toBe("anthropic");
    expect(ai.providers.custom).toEqual({ model: "my-model", baseUrl: "https://llm.example.com/v1", vision: false });
  });

  it.skipIf(sample == null)("newer copies replace, older and equal ones are kept", async () => {
    await applyBackup(await read(sample!), ADD);
    const doc = JSON.parse(sample!) as { brews: { uid: string; updatedAt: string; notes: string }[] };
    doc.brews[0]!.updatedAt = "2026-11-15T00:00:00Z";
    doc.brews[0]!.notes = "Edited on the phone";
    doc.brews[1]!.updatedAt = "2026-11-01T00:00:00Z";
    doc.brews[1]!.notes = "Older copy";
    const r = await applyBackup(await read(JSON.stringify(doc)), ADD);
    expect(r.replaced.brews).toBe(1);
    expect(r.kept.brews).toBe(1);
    expect((await repo.brewByUid(doc.brews[0]!.uid))?.notes).toBe("Edited on the phone");
    expect((await repo.brewByUid(doc.brews[1]!.uid))?.notes).toBe("");
    // The replaced brew still points at the same local bean and grinder.
    const brew = await repo.brewByUid(doc.brews[0]!.uid);
    expect(brew?.beanId).toBe((await db.beans.toCollection().first())?.id);
  });

  it.skipIf(sample == null)("replace mode deletes what was here first", async () => {
    const { beanId } = await seed();
    await settings.update({ defaultBeanId: beanId });
    const r = await applyBackup(await read(sample!), { mode: "replace", bringSettings: false, bringAi: false });
    expect(r.added.brews).toBe(2);
    expect(await db.brew_logs.count()).toBe(2);
    expect(await db.beans.count()).toBe(1);
    expect(await db.photo_blobs.count()).toBe(1);
    expect(await db.saved_recipes.count()).toBe(1);
    // The old default pointed at a deleted coffee.
    expect((await settings.get()).defaultBeanId).toBe(0);
  });

  it.skipIf(sample == null)("an add-mode import keeps this browser's default grinder", async () => {
    const { kultra } = await seed();
    await applyBackup(await read(sample!), ADD);
    const defaults = (await db.grinders.toArray()).filter((g) => g.isDefault);
    expect(defaults.map((g) => g.id)).toEqual([kultra]);
  });

  it.skipIf(sample == null)("aborts everything when one write fails (storage full)", async () => {
    await seed();
    const before = await snapshot();
    const r = await read(sample!);
    // The last write of the transaction (photo bytes) fails as a full disk would.
    const original = db.photo_blobs.bulkPut.bind(db.photo_blobs);
    db.photo_blobs.bulkPut = (() => Promise.reject(new DOMException("full", "QuotaExceededError"))) as unknown as typeof db.photo_blobs.bulkPut;
    try {
      await expect(applyBackup(r, ADD)).rejects.toMatchObject({ kind: "storageFull" });
    } finally {
      db.photo_blobs.bulkPut = original;
    }
    expect(await snapshot()).toEqual(before);
  });
});

describe("1.x exports", () => {
  const sample = readFixture("coffee-brew-log-1-sample.json");

  it.skipIf(sample == null)("imports the shared 1.x fixture, links the catalog and asks about the K-Ultra scale", async () => {
    const r = await read(sample!);
    expect(previewLine(r.preview)).toBe("Found 4 brews, 3 coffees, 3 grinders · a 1.x export from Android on 17 Nov 2026");
    const result = await applyBackup(r, ADD);
    expect(result.added).toMatchObject({ brews: 4, beans: 3, grinders: 3 });
    const kultra = (await db.grinders.toArray()).find((g) => g.model === "K-Ultra")!;
    expect(kultra.clicksPerRotation).toBe(10);
    expect(kultra.catalogId).toBe("1zpresso-k-ultra");
    const s = await settings.get();
    expect(s.pendingNotices).toEqual([`grinder-scale:${kultra.uid}`]);
    expect(s.onboardingDone).toBe(true);
    const brews = await db.brew_logs.orderBy("startedAt").toArray();
    expect(brews[0]!.grinderId).toBe(kultra.id);
    expect(brews[0]!.grindClicks).toBe(77);
    expect(brews[1]!.tdsPercent).toBeNull(); // NaN in the file

    const again = await applyBackup(await read(sample!), ADD);
    expect(again.added).toMatchObject({ brews: 0, beans: 0, grinders: 0 });
    expect((await settings.get()).pendingNotices).toHaveLength(1);
  });

  it.skipIf(!HAS_OWNER_EXPORT)("imports a real 1.x export (COFFEE_OWNER_EXPORT, read in place, never copied)", async () => {
    const text = readFileSync(OWNER_EXPORT, "utf8");
    const r = await read(text);
    expect(r.preview.kind).toBe("legacy1");
    expect(r.preview.problems).toEqual([]);
    const brewsInFile = (JSON.parse(text) as { brews: unknown[] }).brews.length;
    const result = await applyBackup(r, ADD);
    expect(result.added.brews).toBe(brewsInFile);
    expect(result.skipped).toEqual([]);
    const grinders = await db.grinders.toArray();
    const kultra = grinders.find((g) => g.brand === "1Zpresso" && g.model === "K-Ultra");
    expect(kultra).toBeDefined();
    // Lead decision 2: logged values stay; the 1.x row keeps 10 clicks per turn and gets the scale notice.
    expect(kultra!.clicksPerRotation).toBe(10);
    expect(kultra!.catalogId).toBe("1zpresso-k-ultra");
    expect((await settings.get()).pendingNotices).toContain(`grinder-scale:${kultra!.uid}`);
    const brews = await db.brew_logs.toArray();
    const fileClicks = (JSON.parse(text) as { brews: { grindClicks: number | null }[] }).brews.map((b) => b.grindClicks).sort();
    expect(brews.map((b) => b.grindClicks).sort()).toEqual(fileClicks);
    expect(brews.every((b) => b.brewerId === "hario-v60-02")).toBe(true);
    expect(brews.filter((b) => b.recipeAuthor === "James Hoffmann").every((b) => b.recipeId === "hoffmann-v60")).toBe(true);

    const before = await snapshot();
    const again = await applyBackup(await read(text), ADD);
    expect(again.added.brews).toBe(0);
    expect(await snapshot()).toEqual(before);
  });
  if (!HAS_OWNER_EXPORT) console.warn("COFFEE_OWNER_EXPORT is not set to a file: real-export import test skipped");
});

describe("startup linking", () => {
  it("links typed rows once (startupTasksVersion 1) and never again", async () => {
    await db.grinders.add(newGrinder({ brand: "1zpresso", model: "k ultra", clicksPerRotation: 10 }));
    await db.beans.add(newBean({ name: "x", roaster: "Tim Wendelboe", origin: "Kenya" }));
    await runStartupLinking();
    const g = await db.grinders.toCollection().first();
    const b = await db.beans.toCollection().first();
    expect(g?.catalogId).toBe("1zpresso-k-ultra");
    expect(b).toMatchObject({ roasterId: "tim-wendelboe", originId: "kenya" });
    expect((await settings.get()).startupTasksVersion).toBe(1);
    expect((await settings.get()).pendingNotices).toEqual([`grinder-scale:${g!.uid}`]);
    // Linking is not an edit: updatedAt is untouched.
    expect(g!.updatedAt).toBeLessThanOrEqual(Date.now());
    await db.beans.update(b!.id, { roasterId: null });
    await runStartupLinking();
    expect((await db.beans.get(b!.id))?.roasterId).toBeNull();
  });
});

describe("analyses after a restore", () => {
  const DAY = 86_400_000;
  const T0 = Date.UTC(2026, 10, 1, 8, 0);

  /** The hashes this browser works out now, from its own rows. */
  async function hashes() {
    const [brews, beans, grinders] = await Promise.all([db.brew_logs.toArray(), db.beans.toArray(), db.grinders.toArray()]);
    const logs = [...brews].sort((a, b) => b.startedAt - a.startedAt);
    const ctx = {
      recipes: new Map(catalog.recipes.map((r) => [r.id, r])),
      beans: new Map(beans.map((b) => [b.id!, b])),
      grinders: new Map(grinders.map((g) => [g.id!, g])),
      units: "c",
      principles: [],
    };
    const brew = (uid: string) => {
      const log = logs.find((l) => l.uid === uid)!;
      return buildBrewPrompt({
        ...ctx, log, recipe: ctx.recipes.get(log.recipeId) ?? null, bean: log.beanId != null ? (ctx.beans.get(log.beanId) ?? null) : null,
        grinder: log.grinderId != null ? (ctx.grinders.get(log.grinderId) ?? null) : null, history: logs.filter((l) => l.startedAt < log.startedAt), photos: [],
      }).inputHash;
    };
    return { brew, trend: () => buildTrendPrompt({ ...ctx, logs, totalLogged: logs.length }).inputHash, logs };
  }

  const trend: TrendAnalysis = {
    trajectory: "improving", headline: "A zero grind keeps turning up", summary: "", confidence: "low",
    patterns: [
      { title: "No grind logged", detail: "", brewIds: [], strength: "weak", tone: "attention" },
      { title: "Finer scored higher", detail: "", brewIds: [], strength: "weak", tone: "good" },
    ],
    changedOften: [], neverChanged: [], experiment: { goal: "", brews: [], decide: "" }, perCoffee: [], dataWarnings: [],
  };

  function analysisRow(p: Partial<AnalysisRow>): AnalysisRow {
    return {
      uid: crypto.randomUUID(), kind: "trend", brewLogId: null, createdAt: T0 + 9 * DAY, promptVersion: VERSION, inputHash: "", brewCount: 4, newestBrewAt: T0 + 4 * DAY,
      model: "m", host: "api.example.com", rawText: "{}", result: null, flags: [], promptTokens: 100, completionTokens: 50, costMicroUsd: null, latencyMs: 900, providerId: "custom", photoCount: 0, origin: "web",
      ...p,
    };
  }

  /** Four brews made here, with a gap in the row ids, a brew analysis and a trend. */
  async function seedLog(opts: { legacyTrend?: boolean; oldHashes?: boolean } = {}) {
    const beanId = await repo.upsertBean(newBean({ name: "Chelbesa", roaster: "Tim Wendelboe" }));
    const grinderId = await repo.upsertGrinder(newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra", dialFormat: "number-tick:10", isDefault: true, maxSetting: 200 }));
    const v60 = catalog.recipe("hoffmann-v60")!;
    const make = (uid: string, dayNo: number, grindClicks: number, overall: number): BrewRow =>
      newBrew({ uid, beanId, grinderId, beanLabel: "Tim Wendelboe — Chelbesa", grinderLabel: "1Zpresso K-Ultra", recipeId: v60.id, recipeName: v60.name, recipeAuthor: v60.author, brewer: v60.brewer.label, brewerId: v60.brewer.id, grindClicks, doseG: v60.doseG, waterG: v60.waterG, tempC: 95, totalTimeSec: 200 + grindClicks, drawdownSec: 45, daysOffRoast: 10 + dayNo, overall }, T0 + dayNo * DAY);
    const a = await repo.insertBrew(make("brew-a", 1, 0, 4));
    const b = await repo.insertBrew(make("brew-b", 2, 78, 6));
    const gone = await repo.insertBrew(make("brew-gone", 2.5, 77, 5));
    const c = await repo.insertBrew(make("brew-c", 3, 76, 7));
    const d = await repo.insertBrew(make("brew-d", 4, 0, 8));
    await db.brew_logs.delete(gone);

    const now = await hashes();
    const uidOf = new Map(now.logs.map((l) => [l.id!, l.uid]));
    const made: TrendAnalysis = { ...trend, patterns: [{ ...trend.patterns[0]!, brewIds: [a, d] }, { ...trend.patterns[1]!, brewIds: [b, c, gone] }] };
    await repo.insertAnalysis(analysisRow({ uid: "analysis-brew", kind: "brew", brewLogId: d, inputHash: opts.oldHashes ? "0f1e2d3c4b5a6978" : now.brew("brew-d"), result: { brew: { verdict: "close", headline: "Nearly there" }, localWarnings: [] } }));
    await repo.insertAnalysis(analysisRow({
      uid: "analysis-trend", inputHash: opts.oldHashes ? "9f2c1a0b3d4e5f60" : now.trend(),
      result: opts.legacyTrend ? { trend: made, localWarnings: [] } : { trend: made, localWarnings: [], brewUids: brewUidsFor(made, (n) => uidOf.get(n)) },
    }));
    return { ids: { a, b, c, d, gone } };
  }

  /** Saves a backup, empties the browser, puts another brew in first, then restores. */
  async function backUpAndRestore(change: (doc: Record<string, unknown>) => void = () => {}) {
    const built = await buildBackup({ includePhotos: false, includeAnalyses: true });
    const doc = JSON.parse(await built.blob.text()) as Record<string, unknown>;
    change(doc);
    await repo.wipeAll();
    const result = await applyBackup(await read(JSON.stringify(doc)), { mode: "replace", bringSettings: false, bringAi: false });
    expect(result.skipped).toEqual([]);
    return doc;
  }

  const byUid = async (uid: string) => (await db.brew_logs.where("uid").equals(uid).first())!;
  const analysis = async (uid: string) => (await db.analyses.where("uid").equals(uid).first())!;

  /** The uids of the brews a pattern's chips open in this browser. */
  async function chips(pattern: number): Promise<string[]> {
    const row = await analysis("analysis-trend");
    const stored = readStoredResult(row.result)!;
    const brews = await db.brew_logs.toArray();
    const idByUid = new Map(brews.map((b) => [b.uid, b.id!]));
    const ids = followBrews(stored.trend!.patterns[pattern]!.brewIds, brewRowIds(stored.brewUids, (uid) => idByUid.get(uid)));
    return ids.map((id) => brews.find((b) => b.id === id)?.uid ?? `no brew has row id ${id}`);
  }

  it("stay current when every row comes back under a new id", async () => {
    const { ids } = await seedLog();
    await backUpAndRestore();
    // The restore really did renumber.
    const now = await hashes();
    expect(now.logs.map((l) => l.id).some((id) => Object.values(ids).includes(id!))).toBe(false);

    const brewRow = await analysis("analysis-brew");
    expect(brewRow.brewLogId).toBe((await byUid("brew-d")).id);
    expect(brewRow.inputHash).toBe(now.brew("brew-d"));
    expect(staleReason(brewRow, now.brew("brew-d"))).toBeNull();

    const trendRow = await analysis("analysis-trend");
    expect(trendRow.inputHash).toBe(now.trend());
    expect(staleReason(trendRow, now.trend())).toBeNull();

    // An edit after the restore still shows as an edit.
    await db.brew_logs.update(await byUid("brew-b"), { overall: 2 });
    expect(staleReason(trendRow, (await hashes()).trend())).toBe("edited");
  });

  it("made before uid hashes read as an earlier version, not as edited", async () => {
    await seedLog({ oldHashes: true });
    const here = await hashes();
    expect(staleReason(await analysis("analysis-trend"), here.trend())).toBe("older-prompt");
    expect(staleReason(await analysis("analysis-brew"), here.brew("brew-d"))).toBe("older-prompt");
    await backUpAndRestore();
    const now = await hashes();
    expect((await analysis("analysis-trend")).inputHash).toBe("9f2c1a0b3d4e5f60");
    expect(staleReason(await analysis("analysis-trend"), now.trend())).toBe("older-prompt");
    expect(staleReason(await analysis("analysis-brew"), now.brew("brew-d"))).toBe("older-prompt");
  });

  it("trend chips open the same brews after the restore", async () => {
    await seedLog();
    expect(await chips(0)).toEqual(["brew-a", "brew-d"]);
    expect(await chips(1)).toEqual(["brew-b", "brew-c"]);
    await backUpAndRestore();
    expect(await chips(0)).toEqual(["brew-a", "brew-d"]);
    // The number of the brew that was deleted before the backup opens nothing.
    expect(await chips(1)).toEqual(["brew-b", "brew-c"]);
  });

  it("a trend stored before uids gets them in the backup, so its chips survive too", async () => {
    const { ids } = await seedLog({ legacyTrend: true });
    // In place, its numbers are this browser's row ids and are followed as they always were.
    expect(await chips(0)).toEqual(["brew-a", "brew-d"]);
    const doc = await backUpAndRestore();
    const written = (doc.analyses as { uid: string; result: { brewUids?: Record<string, string> } }[]).find((a) => a.uid === "analysis-trend")!;
    expect(written.result.brewUids).toEqual({ [ids.a]: "brew-a", [ids.d]: "brew-d", [ids.b]: "brew-b", [ids.c]: "brew-c" });
    expect(await chips(0)).toEqual(["brew-a", "brew-d"]);
  });

  it("a file with numbers only shows no chips rather than the wrong brew", async () => {
    await seedLog();
    // What a 2.0.0 backup holds: a trend whose numbers are another install's row ids.
    await backUpAndRestore((doc) => {
      for (const a of doc.analyses as { result: Record<string, unknown> | null }[]) if (a.result) delete a.result.brewUids;
    });
    expect(readStoredResult((await analysis("analysis-trend")).result)?.brewUids).toEqual({});
    expect(await chips(0)).toEqual([]);
    expect(await chips(1)).toEqual([]);
  });
});

describe("the shared fixture's trend", () => {
  const sample = readFixture("backup-v2-sample.json");

  it.skipIf(sample == null)("points at the fixture's two brews by uid, whatever row ids they get here", async () => {
    // Another brew is here first, so the fixture's brews cannot land on the ids the trend names.
    await repo.insertBrew(newBrew({ uid: "here-first" }, 1_700_000_000_000));
    await applyBackup(await read(sample!), ADD);
    const row = (await db.analyses.where("uid").equals("a7d5e6f8-0112-43c4-9e5f-6a7b8c9d0e1f").first())!;
    const stored = readStoredResult(row.result)!;
    const brews = await db.brew_logs.toArray();
    const idByUid = new Map(brews.map((b) => [b.uid, b.id!]));
    const open = (pattern: number) =>
      followBrews(stored.trend!.patterns[pattern]!.brewIds, brewRowIds(stored.brewUids, (uid) => idByUid.get(uid))).map((id) => brews.find((b) => b.id === id)?.uid);
    expect(stored.trend!.patterns[0]!.brewIds).toEqual([3, 7]);
    expect(open(0)).toEqual(["c3f1a2b4-6d7e-4f80-9a1b-2c3d4e5f6a7b", "d4a2b3c5-7e8f-4091-8b2c-3d4e5f6a7b8c"]);
    expect(open(1)).toEqual(["c3f1a2b4-6d7e-4f80-9a1b-2c3d4e5f6a7b"]);
    // Made on Android: its hash is carried as written and never compared here.
    expect(row.inputHash).toBe("s2:5d0c2f7a91b3e486");
    expect(staleReason(row, "s2:anything")).toBe("other-device");
  });
});

describe("words", () => {
  it("names errors plainly", () => {
    expect(readErrorText("tooNew")).toBe("This backup was made by a newer version of Coffee. Update Coffee and try again.");
    expect(readErrorText("tooBig")).toBe("Too big to import here. Save the backup without photos and try again.");
  });
  it("previews the way the spec reads", async () => {
    const r = await readBackupText(readFixture("backup-v2-sample.json") ?? "{}", []);
    if (!r.ok) return;
    expect(previewLine(r.preview)).toBe("Found 2 brews, 1 coffee, 1 grinder, 1 recipe, 3 analyses, 1 photo · made on Android (com.laksh.coffee.debug) on 17 Nov 2026");
  });
  it("sizes and file names", () => {
    expect(aboutSize(14_200_000)).toBe("about 14 MB");
    expect(aboutSize(1_250_000)).toBe("about 1.3 MB");
    expect(aboutSize(120_400)).toBe("about 120 KB");
    expect(backupFileName(new Date(2026, 10, 7, 12).getTime())).toBe("coffee-backup-2026-11-07.json");
  });
  it("refuses files over 64 MB before reading them", async () => {
    const big = { size: 65 * 1024 * 1024 } as Blob;
    expect(await readBackupFile(big)).toEqual({ ok: false, error: "tooBig" });
  });
});

afterAll(async () => {
  await db.delete();
});
