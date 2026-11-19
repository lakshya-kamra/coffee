// A§8.8 cases that apply to the web: providers + header allow-list, base URLs, prompt blocks
// (K-Ultra currentOnDial "7.5", no grindSetting for clicks, moveSizes, principles by method, °F
// line), the parser (grindValueFrom/To), GrindMove / GrindValueMove, data quality and staleness.
import { beforeAll, describe, expect, it } from "vitest";
import { catalog, Brewers } from "@/catalog/catalog";
import type { Principle } from "@/catalog/types";
import { DEFAULT_TASTE } from "@/domain/core/evaluation";
import type { Recipe } from "@/domain/core/recipe";
import { newBean, newBrew, newGrinder, type BeanRow, type BrewRow, type GrinderRow } from "@/data/schema";
import { redactSecrets } from "./aiError";
import { buildBrewPrompt, buildTrendPrompt, isCurrentHash, principlesFor, redactedKeys, VERSION, type BrewPromptInput, type TrendPromptInput } from "./analysisPrompt";
import { AnalysisParser, brewRowIds, brewUidsFor, emptyChange, followBrews, GrindMoves, GrindValueMoves, readStoredResult, resolveGrindMove, resolveGrindValueMove, storedBrewUids, valueMove } from "./analysisResult";
import { checkBaseUrl } from "./baseUrl";
import { checkBrew, distrustedFields, Fields } from "./dataQuality";
import { costMicroUsd, formatUsd, usd } from "./modelCatalog";
import { aiProviders, headerAllowed, priceOf, providerById } from "./providers";
import { sha256Hex, sha256Hex16 } from "./sha256";
import { staleReason } from "./staleness";

beforeAll(async () => {
  await catalog.load(["providers", "grinders", "brewers", "recipes", "beans", "water", "roasters", "origins"]);
});

const kUltra = (p: Partial<GrinderRow> = {}): GrinderRow =>
  newGrinder({ id: 7, brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, minSetting: 0, maxSetting: 200, catalogId: "1zpresso-k-ultra", ...p });

const v60 = (): Recipe => catalog.recipes.find((r) => r.brewer.method === "pourover" && r.steps.some((s) => s.targetWaterG != null))!;

function brew(p: Partial<BrewRow> = {}): BrewRow {
  const r = v60();
  return newBrew({
    // A fixed uid, as a saved brew's is: the hash reads it.
    id: 12, uid: "brew-12", startedAt: Date.UTC(2026, 10, 17, 8, 14), recipeId: r.id, recipeName: r.name, brewer: r.brewer.label, brewerId: r.brewer.id, grinderId: 7, grinderLabel: "1Zpresso K-Ultra",
    grindClicks: 75, doseG: r.doseG, waterG: r.waterG, tempC: 94, totalTimeSec: 200, overall: 7, taste: { ...DEFAULT_TASTE, acidity: 7 }, ...p,
  });
}

function input(p: Partial<BrewPromptInput> = {}): BrewPromptInput {
  const r = v60();
  const g = kUltra();
  return {
    log: brew(), recipe: r, bean: null, grinder: g, history: [], photos: [], recipes: new Map([[r.id, r]]), beans: new Map(), grinders: new Map([[7, g]]), units: "c", principles: [], ...p,
  };
}

/** The JSON object on the line after a block title. */
function block(user: string, title: string): Record<string, unknown> {
  const start = user.indexOf(`${title}\n`);
  const line = user.slice(start + title.length + 1).split("\n")[0]!;
  return JSON.parse(line) as Record<string, unknown>;
}

describe("providers (web-spec §14.1)", () => {
  it("keeps every usable provider in catalog order, including ollama, lm-studio and custom", () => {
    const list = aiProviders(catalog.providers);
    const ids = list.map((p) => p.id);
    expect(ids).toContain("ollama");
    expect(ids).toContain("lm-studio");
    expect(ids).toContain("custom");
    expect(ids[0]).toBe(catalog.providers[0]!.id);
    expect(providerById(list, "ollama")!.baseUrl).toBe("http://localhost:11434/v1");
  });

  it("carries the Anthropic browser header and marks public model lists", () => {
    const list = aiProviders(catalog.providers);
    const a = providerById(list, "anthropic")!;
    expect(a.api).toBe("anthropic-messages");
    expect(a.auth).toBe("x-api-key");
    expect(a.browserHeaders["anthropic-dangerous-direct-browser-access"]).toBe("true");
    expect(a.extraHeaders["anthropic-version"]).toBeTruthy();
    expect(providerById(list, "openrouter")!.modelsPublic).toBe(true);
    expect(providerById(list, "commandcode")!.modelsPublic).toBe(true);
    expect(providerById(list, "openai")!.modelsPublic).toBe(false);
  });

  it("header allow-list (A§8.1)", () => {
    expect(headerAllowed("anthropic-version", "2026-10-21")).toBe(true);
    expect(headerAllowed("Authorization", "x")).toBe(false);
    expect(headerAllowed("x-api-key", "x")).toBe(false);
    expect(headerAllowed("cookie", "x")).toBe(false);
    expect(headerAllowed("Content-Type", "x")).toBe(false);
    expect(headerAllowed("bad header", "x")).toBe(false);
    expect(headerAllowed("x-ok", "é")).toBe(false);
    expect(headerAllowed("x-ok", "a".repeat(257))).toBe(false);
  });

  it("prices come from the catalog, both sides or nothing", () => {
    const list = aiProviders(catalog.providers);
    const a = providerById(list, "anthropic")!;
    const m = a.models.find((x) => x.isDefault)!;
    expect(priceOf(a, m.id)).toEqual(m.price);
    expect(priceOf(providerById(list, "ollama"), "ornith-1.5:9b")).toBeNull();
    expect(costMicroUsd({ inputPerM: 0.15, outputPerM: 0.5 }, 3000, 1500)).toBe(1200);
    expect(formatUsd(1200)).toBe("0.12¢");
    expect(formatUsd(25_000)).toBe("2.5¢");
    expect(formatUsd(2_500_000)).toBe("$2.50");
    expect(usd(0.15)).toBe("$0.15");
    expect(usd(0.5)).toBe("$0.50");
    expect(usd(0.435)).toBe("$0.435");
  });
});

describe("base URL (web rules)", () => {
  it("normalises https and strips pasted endpoints", () => {
    expect(checkBaseUrl("api.openai.com/v1/")).toMatchObject({ ok: true, normalized: "https://api.openai.com/v1", host: "api.openai.com" });
    expect(checkBaseUrl("https://openrouter.ai/api/v1/chat/completions")).toMatchObject({ ok: true, normalized: "https://openrouter.ai/api/v1" });
    expect(checkBaseUrl("https://api.anthropic.com/v1/messages")).toMatchObject({ ok: true, normalized: "https://api.anthropic.com/v1" });
  });

  it("allows http only for this computer", () => {
    expect(checkBaseUrl("http://localhost:11434/v1")).toMatchObject({ ok: true, host: "localhost" });
    expect(checkBaseUrl("http://127.0.0.1:1234/v1")).toMatchObject({ ok: true, host: "127.0.0.1" });
    const bad = checkBaseUrl("http://example.com/v1");
    expect(bad.ok).toBe(false);
    if (!bad.ok) expect(bad.error.message).toBe("Browsers block plain http:// from this site. Use https://, or run the model on this computer (localhost).");
  });

  it("refuses keys, credentials and queries", () => {
    expect(checkBaseUrl("sk-proj-abcdefghijklmnop1234").ok).toBe(false);
    expect(checkBaseUrl("https://user:pass@api.example.com/v1").ok).toBe(false);
    expect(checkBaseUrl("https://api.example.com/v1?key=1").ok).toBe(false);
    expect(checkBaseUrl("https://dotless/v1").ok).toBe(false);
  });

  it("redacts the exact key and key-shaped tokens from provider text", () => {
    const key = "sk-live-0123456789abcdef";
    expect(redactSecrets(`bad key ${key} sent`, key)).toBe("bad key ••• sent");
    expect(redactSecrets("Authorization: Bearer abc.def", null)).toBe("Authorization: Bearer •••");
  });
});

describe("sha256", () => {
  it("matches the FIPS vectors", () => {
    expect(sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    expect(sha256Hex("")).toBe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    expect(sha256Hex16("abc")).toBe("ba7816bf8f01cfea");
    expect(sha256Hex("a".repeat(1000))).toBe("41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3");
  });
});

describe("prompt v2 (A§8.5)", () => {
  it("K-Ultra: currentOnDial 7.5, scale, 2/6/13 moves, starting range and a dial example", () => {
    const p = buildBrewPrompt(input());
    const g = block(p.user, "GRINDER");
    expect(g.currentClicks).toBe(75);
    expect(g.currentOnDial).toBe("7.5");
    expect(g.catalogId).toBe("1zpresso-k-ultra");
    expect(g.scale).toMatchObject({ adjustsBy: "clicks", unit: "clicks", finerDirection: "FEWER clicks is finer", clicksPerTurn: 100, micronsPerStep: 20, dialFormat: "number-tick:10" });
    expect(g.moveSizes).toMatchObject({ nudge: 2, clearMiss: 6, maxAtOnce: 13, basis: "20 µm per click (app rule of thumb)" });
    expect(g.startingRange).toMatchObject({ method: "pourover", from: 75, to: 90 });
    expect(g.example).toBe("6 clicks finer from 75 (dial 7.5) is 69 (dial 6.9)");
    expect(p.grindAnchor).toBe(75);
    expect(p.inputHash).toMatch(/^s2:[0-9a-f]{16}$/);
  });

  it("sends no grindSetting text for a click grinder, but does for a numbered dial", () => {
    const clicks = block(buildBrewPrompt(input({ log: brew({ grindSetting: "7.5 on the dial" }) })).user, "THIS BREW (what happened)");
    expect("grindSetting" in clicks).toBe(false);
    expect(clicks.grindClicks).toBe(75);
    const numbered = newGrinder({ id: 8, brand: "Baratza", model: "Encore", settingStyle: "numbers", minSetting: 0, maxSetting: 40 });
    const b = brew({ grinderId: 8, grindClicks: null, grindValue: 14, grindSetting: "14" });
    const user = buildBrewPrompt(input({ log: b, grinder: numbered, grinders: new Map([[8, numbered]]) })).user;
    expect(block(user, "THIS BREW (what happened)")).toMatchObject({ grindSetting: "14", grindValue: 14 });
    expect(block(user, "GRINDER")).toMatchObject({ currentSetting: 14, scale: { adjustsBy: "numbered dial" } });
  });

  it("adds the °F line, brewer, water and photos blocks", () => {
    const water = catalog.water[0]!;
    const p = buildBrewPrompt(input({ units: "f", log: brew({ waterId: water.id }), photos: [{ uid: "abcdef1234", kind: "bed", takenAt: Date.UTC(2026, 10, 17, 8, 14), bytes: 180_000 }] }));
    expect(p.user).toContain("Write temperatures in °F for this person; the data is in °C.");
    expect(block(p.user, "BREWER")).toMatchObject({ method: "pourover" });
    expect(block(p.user, "WATER")).toMatchObject({ label: water.label });
    expect(p.user).toContain("PHOTOS\n1 attached, in this order: #1 bed (2026-11-17 08:14Z, abcdef12, 176 KB)");
    expect(p.photoCount).toBe(1);
    expect(buildBrewPrompt(input()).user).toContain("PHOTOS\nnone");
    expect(buildBrewPrompt(input()).user).not.toContain("°F");
  });

  it("filters principles to the method, at most 10", () => {
    const pr = (id: string, appliesTo: string[]): Principle => ({ id, topic: "grind", appliesTo, text: `text ${id}`, who: "Someone", url: "https://example.com", note: "" });
    const all = [pr("a", ["pourover"]), pr("b", ["espresso"]), pr("c", []), ...Array.from({ length: 12 }, (_, i) => pr(`x${i}`, []))];
    const chosen = principlesFor(all, new Set(["pourover"]));
    expect(chosen.map((p) => p.id).slice(0, 2)).toEqual(["a", "c"]);
    expect(chosen.some((p) => p.id === "b")).toBe(false);
    expect(chosen.length).toBe(10);
    const user = buildBrewPrompt(input({ principles: [pr("a", ["pourover"]), pr("b", ["espresso"])] })).user;
    expect(user).toContain("text a");
    expect(user).not.toContain("text b");
  });

  it("is deterministic and changes with the data", () => {
    const a = buildBrewPrompt(input());
    expect(buildBrewPrompt(input()).inputHash).toBe(a.inputHash);
    expect(buildBrewPrompt(input({ log: brew({ notes: "sour" }) })).inputHash).not.toBe(a.inputHash);
  });

  it("sends a distrusted grind as null with everything worked out from it", () => {
    const p = buildBrewPrompt(input({ log: brew({ grindClicks: 0 }) }));
    const g = block(p.user, "GRINDER");
    expect(g.currentClicks).toBeNull();
    expect(g.currentOnDial).toBeNull();
    expect(block(p.user, "THIS BREW (what happened)").grindClicks).toBeNull();
    expect([...redactedKeys(new Set([Fields.GRIND]))]).toContain("currentOnDial");
  });

  it("trend prompt lists each grinder's moveSizes", () => {
    const logs = [brew({ id: 3 }), brew({ id: 2, startedAt: Date.UTC(2026, 10, 16) }), brew({ id: 1, startedAt: Date.UTC(2026, 10, 15) })];
    const p = buildTrendPrompt({ logs, totalLogged: 3, recipes: input().recipes, beans: new Map(), grinders: input().grinders, units: "c", principles: [] });
    expect(p.user).toContain('"moveSizes":{"nudge":2,"clearMiss":6,"maxAtOnce":13');
    expect(p.brewCount).toBe(3);
  });
});

describe("data quality", () => {
  it("K-Ultra 7 clicks on a pour-over is finer than espresso: distrusted", () => {
    const flags = checkBrew(brew({ grindClicks: 7 }), v60(), kUltra(), null);
    const f = flags.find((x) => x.code === "GRIND_TOO_FINE_FOR_METHOD")!;
    expect(f.severity).toBe("Distrust");
    expect(f.message).toContain("On this grinder the dial reading 7.5 is stored as 75.");
    expect(distrustedFields(flags, 12).has(Fields.GRIND)).toBe(true);
  });

  it("untouched sliders and a 1.x ten-click under-one-turn grind", () => {
    const legacy = newGrinder({ id: 9, brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 10 });
    const flags = checkBrew(brew({ grindClicks: 7, taste: { ...DEFAULT_TASTE } }), v60(), legacy, null);
    expect(flags.map((f) => f.code)).toEqual(expect.arrayContaining(["GRIND_UNDER_ONE_TURN", "TASTE_UNTOUCHED"]));
  });

  it("espresso is never flagged for a fine grind", () => {
    const espresso = Brewers.all.find((b) => b.method === "espresso")!;
    const flags = checkBrew(brew({ grindClicks: 35, recipeId: "", brewerId: espresso.id, brewer: espresso.label, doseG: 18, waterG: 36 }), null, kUltra(), null);
    expect(flags.some((f) => f.field === Fields.GRIND)).toBe(false);
  });
});

describe("parser (A§8.5)", () => {
  it("reads a fenced reply with thinking and grindValueFrom/To", () => {
    const raw = '<think>hmm {not json}</think>```json\n{"verdict":"Needs change","headline":"Thin","diagnosis":"Fast.","confidence":"HIGH","evidence":"one","change":{"variable":"Grind","instruction":"14 -> 13, finer","grindValueFrom":"14","grindValueTo":13.0},}\n```';
    const p = AnalysisParser.parseBrew(raw);
    expect(p.ok).toBe(true);
    if (!p.ok) return;
    expect(p.value.verdict).toBe("needs_change");
    expect(p.value.confidence).toBe("high");
    expect(p.value.evidence).toEqual(["one"]);
    expect(p.value.change).toMatchObject({ variable: "grind", grindValueFrom: 14, grindValueTo: 13, grindClicksTo: null });
  });

  it("keeps a reply it cannot read as raw", () => {
    expect(AnalysisParser.parseBrew("no json here").ok).toBe(false);
    expect(AnalysisParser.parseTrend("{}").ok).toBe(false);
  });

  it("reads a trend reply with perCoffee as an object and brew ids as text", () => {
    const p = AnalysisParser.parseTrend('{"trajectory":"stuck","headline":"Same miss","patterns":[{"title":"Fast","brewIds":"#3, 6","tone":"attention","strength":"strong"}],"perCoffee":{"Onyx — Tropical":"Bright."},"experiment":{"brews":[{"settings":"66 clicks","grindClicks":"66 clicks"}]}}');
    expect(p.ok).toBe(true);
    if (!p.ok) return;
    expect(p.value.patterns[0]!.brewIds).toEqual([3, 6]);
    expect(p.value.perCoffee).toEqual([{ bean: "Onyx — Tropical", note: "Bright." }]);
    expect(p.value.experiment.brews[0]!.grindClicks).toBe(66);
  });
});

describe("grind moves", () => {
  it("K-Ultra numbered dial: 75 → 69 is 6 clicks finer, dial 7.5 → 6.9", () => {
    const r = resolveGrindMove({ ...emptyChange(), variable: "grind", instruction: "75 -> 69 clicks, finer", grindClicksTo: 69 }, kUltra(), 75, "pourover");
    expect(r.warnings).toEqual([]);
    expect(GrindMoves.numbers(r.move!)).toBe("75 → 69");
    expect(GrindMoves.caption(r.move!)).toBe("6 clicks finer · dial 7.5 → 6.9");
  });

  it("1.x ten-click row keeps its words: one full turn", () => {
    const legacy = newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 10 });
    const r = resolveGrindMove({ ...emptyChange(), variable: "grind", grindClicksTo: 67 }, legacy, 77);
    expect(GrindMoves.caption(r.move!)).toBe("10 clicks finer · one full turn · dial 7.7 → 6.7");
  });

  it("warns when words and numbers disagree, or the move is too big", () => {
    const r = resolveGrindMove({ ...emptyChange(), variable: "grind", instruction: "go finer", grindClicksFrom: 80, grindClicksTo: 95 }, kUltra(), 75, "pourover");
    expect(r.warnings.join(" ")).toContain("started from 80 clicks, but your log says 75");
    expect(r.warnings.join(" ")).toContain("words and numbers disagree");
    expect(r.warnings.join(" ")).toContain("more than 13 clicks in one go");
  });

  it("numbered dial value move: 14 → 13 · finer", () => {
    const g = newGrinder({ brand: "Baratza", model: "Encore", settingStyle: "numbers", minSetting: 0, maxSetting: 40 });
    const r = resolveGrindValueMove({ ...emptyChange(), variable: "grind", instruction: "one finer", grindValueTo: 13 }, g, 14);
    expect(GrindValueMoves.numbers(r.move!)).toBe("14 → 13");
    expect(GrindValueMoves.caption(r.move!)).toBe("finer by 1");
    expect(r.warnings).toEqual([]);
  });

  it("value hero for a temperature change", () => {
    expect(valueMove({ ...emptyChange(), variable: "temperature", from: "94 °C", to: "98 °C" })).toEqual({ from: "94", to: "98", unit: "°C" });
    expect(valueMove({ ...emptyChange(), variable: "grind", from: "1", to: "2" })).toBeNull();
  });
});

describe("staleness (A§8.6, web wording)", () => {
  it("other device, older prompt, edited, fresh", () => {
    expect(staleReason({ origin: "android", promptVersion: VERSION, inputHash: "s2:a" }, "s2:a")).toBe("other-device");
    expect(staleReason({ origin: "web", promptVersion: 1, inputHash: "s2:a" }, "s2:a")).toBe("older-prompt");
    expect(staleReason({ origin: "web", promptVersion: VERSION, inputHash: "s2:a" }, "s2:b")).toBe("edited");
    expect(staleReason({ origin: "web", promptVersion: VERSION, inputHash: "s2:a" }, "s2:a")).toBeNull();
    expect(staleReason({ origin: "web", promptVersion: VERSION, inputHash: "s2:a" }, null)).toBeNull();
  });

  it("a hash made from row ids reads as an earlier version, never as an edit", () => {
    // What 2.0.0 stored: the right prompt version, but a hash that changes on every restore.
    const old = { origin: "web", promptVersion: VERSION, inputHash: "9f2c1a0b3d4e5f60" };
    expect(isCurrentHash(old.inputHash)).toBe(false);
    expect(isCurrentHash("s2:9f2c1a0b3d4e5f60")).toBe(true);
    expect(staleReason(old, "s2:abc")).toBe("older-prompt");
    expect(staleReason(old, null)).toBe("older-prompt");
    expect(staleReason(old, old.inputHash)).toBe("older-prompt");
  });
});

describe("the hash and row ids", () => {
  const encore = (p: Partial<GrinderRow> = {}): GrinderRow => newGrinder({ id: 8, uid: "grinder-encore", brand: "Baratza", model: "Encore", settingStyle: "numbers", minSetting: 0, maxSetting: 40, ...p });
  const bean = (p: Partial<BeanRow> = {}): BeanRow => newBean({ id: 5, uid: "bean-5", name: "Halo Beriti", ...p });

  function trendInput(shift: (n: number) => number, grinderIds: { k: number; e: number }, beanId: number): TrendPromptInput {
    const r = v60();
    const k = kUltra({ id: grinderIds.k, uid: "grinder-k-ultra" });
    const e = encore({ id: grinderIds.e });
    // Every brew carries the coffee's name from brew day, as the app saves it.
    const of = { beanId, beanLabel: "Halo Beriti" };
    const logs = [
      brew({ ...of, id: shift(3), uid: "brew-3", grinderId: k.id!, startedAt: Date.UTC(2026, 10, 17, 8, 14) }),
      brew({ ...of, id: shift(2), uid: "brew-2", grinderId: e.id!, grinderLabel: "Baratza Encore", grindClicks: null, grindValue: 15, grindSetting: "15", startedAt: Date.UTC(2026, 10, 16, 8, 14) }),
      brew({ ...of, id: shift(1), uid: "brew-1", grinderId: k.id!, grindClicks: 0, startedAt: Date.UTC(2026, 10, 15, 8, 14) }),
    ];
    return { logs, totalLogged: 3, recipes: new Map([[r.id, r]]), beans: new Map([[beanId, bean({ id: beanId })]]), grinders: new Map([[k.id!, k], [e.id!, e]]), units: "c", principles: [] };
  }

  it("a brew restored under new row ids hashes the same", () => {
    // The target's grind is distrusted, so the prompt names an earlier brew ("brew #9") too.
    const g = kUltra({ uid: "grinder-k-ultra" });
    const earlier = brew({ id: 9, uid: "brew-9", grindClicks: 78, startedAt: Date.UTC(2026, 10, 16, 8, 14) });
    const here = buildBrewPrompt(input({ log: brew({ grindClicks: 0 }), history: [earlier], grinder: g, grinders: new Map([[7, g]]) }));
    expect(here.user).toContain("TASK: Diagnose brew #12 ");
    expect(here.user).toContain('"lastTrustedFrom":"brew #9, ');
    expect(here.user).toContain("- #12 ");

    const moved = kUltra({ id: 907, uid: "grinder-k-ultra" });
    const there = buildBrewPrompt(
      input({ log: brew({ id: 412, grinderId: 907, grindClicks: 0 }), history: [{ ...earlier, id: 409, grinderId: 907 }], grinder: moved, grinders: new Map([[907, moved]]) }),
    );
    expect(there.user).toContain("TASK: Diagnose brew #412 ");
    expect(there.user).toContain('"lastTrustedFrom":"brew #409, ');
    // Only the row ids differ, and the hash reads none of them.
    expect(there.inputHash).toBe(here.inputHash);

    // The data still counts: one click of difference is an edit on either install.
    const edited = buildBrewPrompt(input({ log: brew({ grindClicks: 0 }), history: [{ ...earlier, grindClicks: 79 }], grinder: g, grinders: new Map([[7, g]]) }));
    expect(edited.inputHash).not.toBe(here.inputHash);
    // And a different brew with the same numbers is not the same brew.
    const other = buildBrewPrompt(input({ log: brew({ uid: "another", grindClicks: 0 }), history: [earlier], grinder: g, grinders: new Map([[7, g]]) }));
    expect(other.inputHash).not.toBe(here.inputHash);
  });

  it("a log restored under new row ids hashes the same", () => {
    const here = buildTrendPrompt(trendInput((n) => n, { k: 7, e: 8 }, 5));
    // New ids, and the two grinders swap places so their id order is the other way round.
    const there = buildTrendPrompt(trendInput((n) => n + 400, { k: 902, e: 901 }, 77));

    // What the model reads does change: its ids are the rows', and the grinder list follows them.
    expect(here.user.indexOf('"name":"1Zpresso K-Ultra"')).toBeLessThan(here.user.indexOf('"name":"Baratza Encore"'));
    expect(there.user.indexOf('"name":"1Zpresso K-Ultra"')).toBeGreaterThan(there.user.indexOf('"name":"Baratza Encore"'));
    expect(there.user).toContain('{"id":403,');
    expect(there.user).toContain('"coffeeId":77,');
    expect(there.user).toContain("- #401 ");
    expect(there.inputHash).toBe(here.inputHash);

    const edited = trendInput((n) => n, { k: 7, e: 8 }, 5);
    const changed = buildTrendPrompt({ ...edited, logs: edited.logs.map((l) => (l.uid === "brew-2" ? { ...l, overall: 2 } : l)) });
    expect(changed.inputHash).not.toBe(here.inputHash);
  });

  it("a coffee deleted before the backup does not change the hash on restore", () => {
    // A brew keeps the row id of a coffee that was deleted; a backup has no uid to write for it,
    // so the restored brew has no coffee id at all.
    const base = trendInput((n) => n, { k: 7, e: 8 }, 5);
    const dangling = { ...base, logs: base.logs.map((l) => (l.uid === "brew-1" ? { ...l, beanId: 55 } : l)) };
    const restored = { ...base, logs: base.logs.map((l) => (l.uid === "brew-1" ? { ...l, beanId: null } : l)) };
    expect(buildTrendPrompt(dangling).inputHash).toBe(buildTrendPrompt(restored).inputHash);
  });

  it("brews of a deleted coffee are grouped as a restore leaves them", () => {
    // Three brews of a coffee since deleted (they keep its row id, 55) and one logged with no
    // coffee, far from them. A restore brings all four back with no coffee id.
    const old = (id: number, day: number) => brew({ id, uid: `brew-${id}`, beanId: 55, beanLabel: "Gone", grindClicks: 60, startedAt: Date.UTC(2026, 10, day, 8, 14) });
    const history = [old(11, 16), old(10, 15), old(9, 14)];
    const target = brew({ grindClicks: 190 });
    const before = buildBrewPrompt(input({ log: target, history }));
    const after = buildBrewPrompt(input({ log: target, history: history.map((h) => ({ ...h, beanId: null })) }));
    expect(after.flags.some((f) => f.code === "GRIND_JUMP" && f.brewId === 12)).toBe(true);
    expect(before.flags).toEqual(after.flags);
    expect(before.inputHash).toBe(after.inputHash);

    // The same for the brew a distrusted grind is anchored on: the newest, on either side.
    const anchors = [brew({ id: 11, uid: "brew-11", beanId: 55, grindClicks: 70, startedAt: Date.UTC(2026, 10, 16, 8, 14) }), brew({ id: 10, uid: "brew-10", grindClicks: 80, startedAt: Date.UTC(2026, 10, 15, 8, 14) })];
    const a = buildBrewPrompt(input({ log: brew({ grindClicks: 0 }), history: anchors }));
    const b = buildBrewPrompt(input({ log: brew({ grindClicks: 0 }), history: anchors.map((h) => ({ ...h, beanId: null })) }));
    expect(a.user).toContain('"lastTrustedFrom":"brew #11, ');
    expect(a.inputHash).toBe(b.inputHash);

    // And across a log: the trend checks every brew against the ones before it.
    const logs = [target, ...history];
    // One grinder for both sides: a new one would get a new uid.
    const { recipes, grinders } = input();
    const trend = (rows: BrewRow[]): TrendPromptInput => ({ logs: rows, totalLogged: rows.length, recipes, beans: new Map(), grinders, units: "c", principles: [] });
    const dangling = buildTrendPrompt(trend(logs));
    expect(dangling.flags).toEqual(buildTrendPrompt(trend(logs.map((l) => ({ ...l, beanId: null })))).flags);
    expect(dangling.inputHash).toBe(buildTrendPrompt(trend(logs.map((l) => ({ ...l, beanId: null })))).inputHash);
    // The model is still shown which brews shared the coffee.
    expect(dangling.user).toContain('"coffeeId":55,');
  });

  it("whether photos are sent is a setting, and the hash does not read it", () => {
    const bed = { uid: "photo-bed-0001", kind: "bed", takenAt: Date.UTC(2026, 10, 17, 8, 20), bytes: 120_000 } as const;
    const cup = { ...bed, uid: "photo-cup-0002", kind: "cup" } as const;
    // Made with a vision model; read again where the settings attach nothing.
    const sent = buildBrewPrompt(input({ photos: [bed], brewPhotos: [bed] }));
    const unsent = buildBrewPrompt(input({ photos: [], brewPhotos: [bed] }));
    expect(sent.user).toContain("PHOTOS\n1 attached");
    expect(unsent.user).toContain("PHOTOS\nnone");
    expect([sent.photoCount, unsent.photoCount]).toEqual([1, 0]);
    expect(unsent.inputHash).toBe(sent.inputHash);
    // A photo added to the brew is still an edit.
    expect(buildBrewPrompt(input({ photos: [], brewPhotos: [bed, cup] })).inputHash).not.toBe(sent.inputHash);
    expect(buildBrewPrompt(input({ photos: [], brewPhotos: [] })).inputHash).not.toBe(sent.inputHash);
  });

  it("settings that a restore may not bring leave the hash alone", () => {
    // °F is a setting and principles are reference text: neither is the brew's data.
    const a = buildBrewPrompt(input());
    const pr: Principle = { id: "a", topic: "grind", appliesTo: [], text: "text a", who: "Someone", url: "https://example.com", note: "" };
    const b = buildBrewPrompt(input({ units: "f", principles: [pr] }));
    expect(b.user).not.toBe(a.user);
    expect(b.inputHash).toBe(a.inputHash);
  });
});

describe("the brews a stored trend points at", () => {
  const trend = AnalysisParser.parseTrend('{"trajectory":"stuck","headline":"Same miss","patterns":[{"title":"Fast","brewIds":[11,15]},{"title":"Slow","brewIds":[12,13,11]}]}');
  if (!trend.ok) throw new Error("the sample trend did not parse");
  // Row 13 was deleted before the read was made: nothing stands behind that number.
  const uidOf = (n: number) => ({ 11: "brew-a", 12: "brew-b", 15: "brew-d" })[n];

  it("keeps the uid behind every number the patterns name", () => {
    expect(brewUidsFor(trend.value, uidOf)).toEqual({ "11": "brew-a", "15": "brew-d", "12": "brew-b" });
  });

  it("follows the numbers through the uids, to whatever row ids the brews have now", () => {
    const here = new Map([["brew-a", 301], ["brew-b", 302], ["brew-d", 304], ["someone-else", 11]]);
    const rowIds = brewRowIds(brewUidsFor(trend.value, uidOf), (uid) => here.get(uid));
    expect(followBrews(trend.value.patterns[0]!.brewIds, rowIds)).toEqual([301, 304]);
    // Number 11 never opens the unrelated brew that has row id 11 here.
    expect(followBrews(trend.value.patterns[1]!.brewIds, rowIds)).toEqual([302, 301]);
    // A brew that was deleted since has no chip.
    here.delete("brew-d");
    expect(followBrews([11, 15], brewRowIds(brewUidsFor(trend.value, uidOf), (uid) => here.get(uid)))).toEqual([301]);
  });

  it("reads a result stored before uids by its row ids, as it always was", () => {
    const stored = readStoredResult({ trend: trend.value, localWarnings: [] });
    expect(stored?.brewUids).toBeUndefined();
    expect(followBrews([11, 15, 11], brewRowIds(stored?.brewUids, () => 1))).toEqual([11, 15]);
  });

  it("never reads another app's numbers as row ids of its own", () => {
    // Brought in before imports marked such rows: no table, and the numbers are Android's.
    expect(storedBrewUids(undefined, true)).toBeNull();
    expect(followBrews([11, 15], brewRowIds(storedBrewUids(undefined, false), () => 1))).toEqual([]);
    expect(storedBrewUids({ "11": "brew-a" }, false)).toEqual({ "11": "brew-a" });
  });

  it("reads the table back from a stored result, whichever platform wrote it", () => {
    const stored = readStoredResult({ trend: trend.value, localWarnings: [], brewUids: { "11": "brew-a", "15": 7 } });
    expect(stored?.brewUids).toEqual({ "11": "brew-a" });
    // An empty table is a table: none of the numbers is followed.
    expect(followBrews([11, 15], brewRowIds(readStoredResult({ trend: trend.value, brewUids: {} })?.brewUids, () => 1))).toEqual([]);
  });
});
