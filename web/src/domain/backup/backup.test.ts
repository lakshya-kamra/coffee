// Backup v2 domain (web-spec §15.5): sanitiser, codec, 1.x reader against the shared fixtures,
// merge planner, row mapping round trip and the catalog linker. Pure: no IndexedDB here.
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newBean, newBrew, newGrinder, type AnalysisRow, type PhotoRow } from "@/data/schema";
import { DEFAULT_AI_PREFS, DEFAULT_SETTINGS } from "@/data/settings";
import { decodeV2, detect, detectValue, encode, parseBackupText, parseInstant } from "./codec";
import { legacyUid, readLegacy } from "./legacy";
import { linkBean, linkGrinder, matchGrinder, matchOrigin, matchRoaster, norm, scaleDiffers } from "./linker";
import { planBackup, planTable, type Existing } from "./merge";
import { KEYS, type BackupDoc } from "./model";
import { readBackupText } from "./read";
import { analysisFromDto, analysisToDto, beanFromDto, brewFromDto, grinderFromDto, photoFromDto, snapshotToDoc } from "./rows";
import { sanitizeNonStandardNumbers } from "./sanitize";

const fixture = (name: string) => fileURLToPath(new URL(`../../../../shared/fixtures/${name}`, import.meta.url));
const readFixture = (name: string): string | null => (existsSync(fixture(name)) ? readFileSync(fixture(name), "utf8") : null);

beforeAll(async () => {
  await catalog.load(["brewers", "recipes", "grinders", "roasters", "origins"]);
});

const builtIns = () => catalog.recipes.map((r) => ({ id: r.id, name: r.name, author: r.author }));

describe("sanitizeNonStandardNumbers", () => {
  it("turns bare NaN / Infinity / -Infinity into null", () => {
    const out = sanitizeNonStandardNumbers('{"a": NaN, "b": Infinity, "c": -Infinity, "d": [NaN,1]}');
    expect(JSON.parse(out)).toEqual({ a: null, b: null, c: null, d: [null, 1] });
  });
  it("leaves NaN inside strings alone, escapes included", () => {
    const text = '{"note": "NaN \\" Infinity", "x": NaN, "y": "-Infinity"}';
    expect(JSON.parse(sanitizeNonStandardNumbers(text))).toEqual({ note: 'NaN " Infinity', x: null, y: "-Infinity" });
  });
  it("returns the same string when there is nothing to fix", () => {
    const text = '{"a": 1}';
    expect(sanitizeNonStandardNumbers(text)).toBe(text);
  });
});

describe("codec", () => {
  it("detects every kind", () => {
    expect(detect('{"format":"coffee-backup","version":2}')).toBe("v2");
    expect(detect('{"format":"coffee-backup","version":3}')).toBe("tooNew");
    expect(detect('{"schema":"coffee-brew-log/1","exportedAt":"x"}')).toBe("legacy1");
    expect(detect('{"schema":"coffee-brew-log/2"}')).toBe("tooNew");
    expect(detect('{"hello":1}')).toBe("unknown");
    expect(detect("not json")).toBe("unknown");
    expect(detectValue([])).toBe("unknown");
    expect(parseBackupText("{")).toEqual({ ok: false, error: "notJson" });
  });

  it("reads instants with or without milliseconds, microseconds and offsets", () => {
    expect(parseInstant("2026-11-17T09:41:07.123456Z")).toBe(Date.UTC(2026, 10, 17, 9, 41, 7, 123));
    expect(parseInstant("2026-11-12T08:01:00Z")).toBe(Date.UTC(2026, 10, 12, 8, 1, 0));
    expect(parseInstant("2026-11-12T09:01:00+01:00")).toBe(Date.UTC(2026, 10, 12, 8, 1, 0));
    expect(parseInstant("yesterday")).toBeNull();
  });

  it("decodes leniently: defaults, skipped records, Kotlin enum names and 1.x labels", () => {
    const { doc, problems } = decodeV2({
      format: "coffee-backup",
      version: 2,
      brews: [
        { uid: "a", startedAt: "2026-01-01T00:00:00Z", flow: "OnTarget", bed: { shape: "Flat and even", surface: "light" }, extra: true },
        { startedAt: "2026-01-01T00:00:00Z" },
        { uid: "b" },
      ],
      beans: [{ uid: "x", name: "" }],
    });
    expect(doc.brews).toHaveLength(1);
    expect(doc.brews[0]!.flow).toBe("on-target");
    expect(doc.brews[0]!.bed.shape).toBe("flat");
    expect(doc.brews[0]!.bed.surface).toBe("light");
    expect(doc.brews[0]!.taste.balance).toBe(5);
    expect(doc.brews[0]!.tempC).toBe(0);
    expect(problems).toEqual(["beans[0]: no name", "brews[1]: no uid", "brews[2]: no startedAt"]);
    expect(doc.app.platform).toBe("android");
  });

  const sample = readFixture("backup-v2-sample.json");
  it.skipIf(sample == null)("shared/fixtures/backup-v2-sample.json parses with nothing skipped", async () => {
    const r = await readBackupText(sample!, builtIns());
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.preview.problems).toEqual([]);
    expect(r.preview.counts).toEqual({ brews: 2, beans: 1, grinders: 1, recipes: 1, analyses: 3, photos: 1 });
    expect(r.preview.platform).toBe("android");
    expect(r.doc.grinders[0]!.setting).toMatchObject({ clicksPerRotation: 100, micronsPerStep: 20, dialFormat: "number-tick:10" });
    expect(r.doc.settings?.defaultBeanUid).toBe("0b9a3c1e-5d2f-4a8b-9c7d-1e2f3a4b5c6d");
    expect(r.doc.ai?.providers.map((p) => p.id)).toEqual(["anthropic", "custom"]);
    // Photo bytes arrive as a Blob; the base64 is gone from the document.
    expect(r.doc.photos[0]!.data).toBeNull();
    expect(r.photoBytes[0]?.full.size).toBeGreaterThan(100);
    expect(r.doc.brews[1]!.startedAt).toBe("2026-11-12T08:01:00.000Z");
  });
  if (sample == null) console.warn("shared/fixtures/backup-v2-sample.json is missing: fixture test skipped");
});

describe("1.x reader", () => {
  it("derives uids from SHA-256 of '<exportedAt>|<type>|<id>'", async () => {
    // Fixed vector: the first 16 hex of SHA-256("2026-11-17T09:41:07.123456Z|bean|1").
    expect(await legacyUid("2026-11-17T09:41:07.123456Z", "bean", 1)).toBe("legacy-bcf423451d822f64");
    expect(await legacyUid("2026-11-17T09:41:07.123456Z", "bean", 1)).toBe(await legacyUid("2026-11-17T09:41:07.123456Z", "bean", "1"));
    expect(await legacyUid("x", "brew", 1)).not.toBe(await legacyUid("x", "bean", 1));
  });

  const sample = readFixture("coffee-brew-log-1-sample.json");
  const expected = readFixture("coffee-brew-log-1-expected.json");
  it.skipIf(sample == null || expected == null)("imports the shared 1.x fixture exactly as coffee-brew-log-1-expected.json says", async () => {
    const r = await readBackupText(sample!, builtIns());
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.preview.kind).toBe("legacy1");
    expect(r.preview.problems).toEqual([]);
    const want = JSON.parse(expected!) as {
      beans: Record<string, unknown>[];
      grinders: Record<string, unknown>[];
      brews: Record<string, unknown>[];
      afterLinking: { grinders: Record<string, string | null>; beans: Record<string, { roasterId: string | null; originId: string | null }>; pendingNotices: string[] };
    };
    expect(r.doc.beans).toHaveLength(want.beans.length);
    want.beans.forEach((b, i) => expect(r.doc.beans[i]).toMatchObject(b));
    want.grinders.forEach((g, i) => expect(r.doc.grinders[i]).toMatchObject(g));
    want.brews.forEach((l, i) => expect(r.doc.brews[i]).toMatchObject(l));

    // The linker, applied to the rows the import would write, gives the expected ids and notices.
    const notices: string[] = [];
    for (const g of r.doc.grinders) {
      const row = { ...grinderFromDto(g, 0), id: 1 };
      const link = linkGrinder(row, catalog.grinders);
      expect(link?.catalogId ?? null).toBe(want.afterLinking.grinders[g.uid]);
      if (link?.notice) notices.push(`grinder-scale:${g.uid}`);
    }
    expect(notices).toEqual(want.afterLinking.pendingNotices);
    for (const b of r.doc.beans) {
      const row = beanFromDto(b, 0);
      const linked = linkBean(row, catalog.roasters, catalog.origins) ?? row;
      expect({ roasterId: linked.roasterId, originId: linked.originId }).toEqual(want.afterLinking.beans[b.uid]);
    }
  });
  if (sample == null || expected == null) console.warn("shared/fixtures/coffee-brew-log-1-*.json missing: legacy fixture test skipped");

  it("keeps a 1.x K-Ultra at 10 clicks per turn and its logged clicks (lead decision 2)", async () => {
    const r = await readLegacy(
      {
        exportedAt: "2026-11-17T09:41:07.123456Z",
        schema: "coffee-brew-log/1",
        beans: [],
        grinders: [{ id: 2, brand: "1Zpresso", model: "K-Ultra", burrType: "Conical", settingStyle: "Clicks", clicksPerRotation: 10, micronsPerClick: null, lowerIsFiner: true }],
        brews: [{ id: 1, startedAt: "2026-11-17T08:30:00.000Z", recipe: "x", recipeAuthor: "y", brewer: "Hario V60", grinder: "1Zpresso K-Ultra", grindClicks: 82 }],
      },
      builtIns(),
    );
    expect(r.doc.grinders[0]!.setting.clicksPerRotation).toBe(10);
    expect(r.doc.brews[0]!.grindClicks).toBe(82);
    expect(r.doc.brews[0]!.grinderUid).toBe(r.doc.grinders[0]!.uid);
    expect(r.doc.exportedAt).toBe("2026-11-17T09:41:07.123Z");
  });
});

describe("merge planner", () => {
  const ex = (pairs: [string, number, number | null][]) => new Map<string, Existing<number>>(pairs.map(([k, id, u]) => [k, { id, updatedAt: u }]));
  type R = { uid: string; u: number | null };
  const plan = (incoming: R[], existing: Map<string, Existing<number>>, mode: "add" | "replace" = "add") =>
    planTable(incoming, (r) => r.uid, (r) => r.u, existing, mode);

  it("adds unknown uids", () => {
    const p = plan([{ uid: "new", u: 5 }], ex([["old", 1, 5]]));
    expect(p.add.map((r) => r.uid)).toEqual(["new"]);
    expect(p.replace).toEqual([]);
  });
  it("replaces only when the file's copy is strictly newer", () => {
    const p = plan([{ uid: "a", u: 10 }, { uid: "b", u: 5 }, { uid: "c", u: 1 }], ex([["a", 1, 5], ["b", 2, 5], ["c", 3, 5]]));
    expect(p.replace).toEqual([{ item: { uid: "a", u: 10 }, id: 1 }]);
    expect(p.keep.map((k) => k.item.uid)).toEqual(["b", "c"]);
  });
  it("never replaces with a record that has no updatedAt", () => {
    const p = plan([{ uid: "a", u: null }], ex([["a", 1, null]]));
    expect(p.keep).toHaveLength(1);
    expect(p.replace).toHaveLength(0);
  });
  it("replaces a row that has no updatedAt with one that has", () => {
    expect(plan([{ uid: "a", u: 3 }], ex([["a", 1, null]])).replace).toHaveLength(1);
  });
  it("replace mode adds everything, whatever is here", () => {
    const p = plan([{ uid: "a", u: 1 }, { uid: "b", u: null }], ex([["a", 1, 99]]), "replace");
    expect(p.add.map((r) => r.uid)).toEqual(["a", "b"]);
    expect(p.keep).toEqual([]);
  });
  it("plans a uid that appears twice once, from its newest copy", () => {
    const p = plan([{ uid: "a", u: 1 }, { uid: "a", u: 7 }, { uid: "a", u: 3 }], ex([]));
    expect(p.add).toEqual([{ uid: "a", u: 7 }]);
  });
  it("plans recipes by id and never replaces analyses or photos", () => {
    const doc = {
      beans: [], grinders: [], brews: [],
      recipes: [{ id: "custom-1", updatedAt: "2026-01-02T00:00:00Z" }],
      analyses: [{ uid: "an" } as BackupDoc["analyses"][number]],
      photos: [{ uid: "ph" } as BackupDoc["photos"][number]],
    };
    const empty = new Map<string, Existing<number>>();
    const p = planBackup(doc, {
      beans: empty, grinders: empty, brews: empty, recipes: new Map([["custom-1", { id: "custom-1", updatedAt: Date.UTC(2026, 0, 1) }]]),
      analyses: ex([["an", 4, 1]]), photos: ex([["ph", 5, 1]]),
    }, "add");
    expect(p.recipes.replace).toHaveLength(1);
    expect(p.analyses.keep).toHaveLength(1);
    expect(p.photos.keep).toHaveLength(1);
  });
});

describe("rows <-> DTO", () => {
  function sampleSnapshot() {
    const bean = { ...newBean({ name: "Red Brick", roaster: "Square Mile", roastedOnEpochDay: 20755, varietalIds: ["gesha"], bagSizeG: 250 }, 1000), id: 7 };
    const grinder = { ...newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra", dialFormat: "number-tick:10" }, 2000), id: 3 };
    const unknownCpr = { ...newGrinder({ brand: "Hand", model: "Mill", clicksPerRotation: 0 }, 2500), id: 4 };
    const brew = {
      ...newBrew({ beanId: 7, grinderId: 3, brewer: "Hario V60", brewerId: "hario-v60-02", grindClicks: 75, flowFeel: "slow", bedShape: "crater", sludge: null, channeling: "obvious", sharedAt: 5000, overall: 7, doseG: 15.5 }, 3000),
      id: 11,
    };
    const orphanBrew = { ...newBrew({ beanId: 99, grinderId: null }, 4000), id: 12 };
    const analysis: AnalysisRow = {
      id: 2, uid: "an-1", kind: "brew", brewLogId: 11, createdAt: 3500, promptVersion: 2, inputHash: "h", brewCount: 1, newestBrewAt: 3000, model: "m", host: "api.x",
      rawText: "{}", result: { a: 1 }, flags: [{ code: "X" }], promptTokens: 10, completionTokens: 2, costMicroUsd: null, latencyMs: 40, providerId: "openai", photoCount: 1, origin: "web",
    };
    const photo: PhotoRow = { id: 9, uid: "ph-1", brewLogId: 11, beanId: null, kind: "bed", mediaType: "image/jpeg", widthPx: 10, heightPx: 20, bytes: 3, takenAt: 3100, createdAt: 3200, caption: "c", sendToAi: false };
    return {
      beans: [bean], grinders: [grinder, unknownCpr], brews: [brew, orphanBrew], savedRecipes: [], analyses: [analysis], photos: [photo],
      settings: { ...DEFAULT_SETTINGS, defaultBeanId: 7, defaultGrinderId: 3, tempUnit: "f" as const },
      aiPrefs: { ...DEFAULT_AI_PREFS, activeProvider: "custom", providers: { custom: { model: "m", baseUrl: "https://x.example/v1", vision: true, testOkAt: 1 }, openai: { model: "gpt" } } },
    };
  }

  it("writes every key, nulls included, in the documented order", () => {
    const doc = snapshotToDoc(sampleSnapshot(), { exportedAt: 9000, app: { platform: "web", appVersion: "2.0.0", packageName: "h", dbVersion: 1 }, includesPhotos: true, includesAnalyses: true });
    const json = JSON.parse(encode(doc)) as Record<string, unknown> & BackupDoc;
    expect(Object.keys(json)).toEqual([...KEYS.top]);
    expect(Object.keys(json.settings!)).toEqual([...KEYS.settings]);
    expect(Object.keys(json.beans[0]!)).toEqual([...KEYS.bean]);
    expect(Object.keys(json.grinders[0]!)).toEqual([...KEYS.grinder]);
    expect(Object.keys(json.grinders[0]!.setting)).toEqual([...KEYS.grinderSetting]);
    expect(Object.keys(json.brews[0]!)).toEqual([...KEYS.brew]);
    expect(Object.keys(json.brews[0]!.bed)).toEqual([...KEYS.bed]);
    expect(Object.keys(json.brews[0]!.taste)).toEqual([...KEYS.taste]);
    expect(Object.keys(json.analyses[0]!)).toEqual([...KEYS.analysis]);
    expect(Object.keys(json.photos[0]!)).toEqual([...KEYS.photo]);
    expect(Object.keys(json.ai!.providers[0]!)).toEqual([...KEYS.aiProvider]);
    // Nulls are written, not dropped.
    expect(json.brews[0]!.bed.surface).toBeNull();
    expect("beanUid" in json.brews[1]!).toBe(true);
    expect(json.brews[1]!.beanUid).toBeNull();
    expect(json.grinders[1]!.setting.clicksPerRotation).toBeNull();
  });

  it("a trend's brew numbers travel with the uids behind them", () => {
    const trend = { trajectory: "steady", headline: "h", patterns: [{ title: "a", brewIds: [11, 12] }, { title: "b", brewIds: [11, 99] }] };
    const row: AnalysisRow = { ...sampleSnapshot().analyses[0]!, kind: "trend", brewLogId: null, result: { trend, localWarnings: [] } };
    const brewUid = (id: number) => ({ 11: "brew-a", 12: "brew-b" })[id] ?? null;

    // Stored before results kept uids: they are added on the way out, while the numbers are
    // still this browser's row ids.
    const legacy = analysisToDto(row, brewUid);
    expect(legacy.result).toEqual({ trend, localWarnings: [], brewUids: { "11": "brew-a", "12": "brew-b" } });
    expect(row.result).toEqual({ trend, localWarnings: [] });

    // One that Android made and an older build brought in holds Android's row ids: nothing
    // is filled in from this browser's rows, and the file says so with an empty table.
    expect(analysisToDto({ ...row, origin: "android" }, brewUid).result).toEqual({ trend, localWarnings: [], brewUids: {} });

    // A result that already has them is written as it is, and read as it is.
    const current = { trend, localWarnings: [], brewUids: { "11": "brew-x" } };
    expect(analysisToDto({ ...row, result: current }, brewUid).result).toBe(current);
    expect(analysisFromDto({ ...legacy, result: current }, null).result).toBe(current);

    // A file with numbers only (a 2.0.0 backup): they are another install's row ids, so the
    // empty table says "follow none of them".
    expect(analysisFromDto({ ...legacy, result: { trend, localWarnings: [] } }, null).result).toEqual({ trend, localWarnings: [], brewUids: {} });

    // Anything that is not a trend passes through untouched, both ways.
    const brewResult = { brew: { verdict: "close" }, localWarnings: [] };
    expect(analysisToDto({ ...row, result: brewResult }, brewUid).result).toBe(brewResult);
    expect(analysisFromDto({ ...legacy, result: brewResult }, 4).result).toBe(brewResult);
    expect(analysisFromDto({ ...legacy, result: null }, null).result).toBeNull();
  });

  it("round-trips rows -> DTO -> JSON -> DTO -> rows with ids remapped", () => {
    const snap = sampleSnapshot();
    const doc = snapshotToDoc(snap, { exportedAt: 9000, app: { platform: "web", appVersion: "2.0.0", packageName: "h", dbVersion: 1 }, includesPhotos: false, includesAnalyses: true });
    const back = decodeV2(JSON.parse(encode(doc)));
    expect(back.problems).toEqual([]);
    const strip = <T extends { id?: number }>(r: T) => {
      const { id: _id, ...rest } = r;
      return rest;
    };
    expect(beanFromDto(back.doc.beans[0]!, 0)).toEqual(strip(snap.beans[0]!));
    expect(grinderFromDto(back.doc.grinders[0]!, 0)).toEqual(strip(snap.grinders[0]!));
    expect(grinderFromDto(back.doc.grinders[1]!, 0)).toEqual(strip(snap.grinders[1]!));
    // New local ids for the bean (70) and grinder (30): the brew follows them.
    const brew = back.doc.brews.find((b) => b.uid === snap.brews[0]!.uid)!;
    expect(brewFromDto(brew, 70, 30)).toEqual({ ...strip(snap.brews[0]!), beanId: 70, grinderId: 30 });
    expect(brew.beanUid).toBe(snap.beans[0]!.uid);
    expect(analysisFromDto(back.doc.analyses[0]!, 110)).toEqual({ ...strip(snap.analyses[0]!), brewLogId: 110 });
    expect(photoFromDto(back.doc.photos[0]!, 110, null, 3)).toEqual({ ...strip(snap.photos[0]!), brewLogId: 110 });
    expect(back.doc.settings).toMatchObject({ defaultBeanUid: snap.beans[0]!.uid, defaultGrinderUid: snap.grinders[0]!.uid, tempUnit: "f" });
    expect(back.doc.ai).toEqual({
      activeProvider: "custom",
      sendPhotos: true,
      providers: [
        { id: "custom", baseUrl: "https://x.example/v1", model: "m", vision: true },
        { id: "openai", baseUrl: null, model: "gpt", vision: null },
      ],
    });
  });
});

describe("catalog linker", () => {
  it("normalises like Kotlin", () => {
    expect(norm("Mahlkönig EK43")).toBe("mahlkonigek43");
    expect(norm("Ø Coffee")).toBe("ocoffee");
  });
  it("links the K-Ultra and asks about a 10-click scale", () => {
    const c = matchGrinder({ brand: "1Zpresso", model: "K-Ultra" }, catalog.grinders);
    expect(c?.id).toBe("1zpresso-k-ultra");
    expect(scaleDiffers({ settingStyle: "clicks", clicksPerRotation: 10, micronsPerClick: null, lowerIsFiner: true }, c!)).toBe(true);
    expect(scaleDiffers({ settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, lowerIsFiner: true }, c!)).toBe(false);
  });
  it("never links a grinder twice and never touches numbers", () => {
    const row = { ...newGrinder({ brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 10 }), id: 1 };
    expect(linkGrinder({ ...row, catalogId: "x" }, catalog.grinders)).toBeNull();
    expect(linkGrinder(row, catalog.grinders)).toEqual({ catalogId: "1zpresso-k-ultra", notice: true });
  });
  it("matches roasters with and without a trailing Coffee/Roasters word, and origins by name or ISO code", () => {
    expect(matchRoaster("Square Mile", catalog.roasters)?.id).toBe("square-mile");
    expect(matchRoaster("tim wendelboe", catalog.roasters)?.id).toBe("tim-wendelboe");
    expect(matchRoaster("", catalog.roasters)).toBeNull();
    expect(matchOrigin("et", catalog.origins)?.id).toBe("ethiopia");
    expect(matchOrigin("Kenya", catalog.origins)?.id).toBe("kenya");
    expect(matchOrigin("Blend", catalog.origins)).toBeNull();
  });
  it("leaves set ids alone and returns null when nothing changes", () => {
    const b = newBean({ name: "x", roaster: "Square Mile", roasterId: "custom-id", origin: "Narnia" });
    expect(linkBean(b, catalog.roasters, catalog.origins)).toBeNull();
  });
});
