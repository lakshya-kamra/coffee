// A§3.2 test vectors (verbatim), A§3.3 move sizes and the lead's K-Ultra decision 2.
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import type { CatalogGrinder } from "@/catalog/types";
import { newGrinder, type GrinderRow } from "@/data/schema";
import { decodeDialFormat, encodeDialFormat, DialFormats } from "./dialFormat";
import { grindLooksTyped } from "./grindLooksTyped";
import { describe as describeGrind, finerDirection, format, fromReading, grindScaleOf, moves, parseReading, reading, resolveDialFormat } from "./grindScale";
import { NUMBERED_DIAL_IDS } from "./numberedDials";

beforeAll(async () => {
  await catalog.load(["grinders"]);
});

function row(p: Partial<GrinderRow>): GrinderRow {
  return newGrinder({ brand: "Test", model: "Grinder", ...p });
}

const kUltra = (): CatalogGrinder => catalog.grinder("1zpresso-k-ultra")!;
const kMax = (): CatalogGrinder => catalog.grinder("1zpresso-k-max")!;

describe("A§3.2 vectors", () => {
  it("legacy cpr 10, no catalog: 75 → 7.5 / '7 turns + 5 · dial 7.5'", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 10 }));
    expect(s.dial).toEqual(DialFormats.turnsDotClicks(10));
    expect(reading(s, 75)).toBe("7.5");
    expect(describeGrind(s, 75)).toBe("7 turns + 5 · dial 7.5");
    const withMicrons = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 10, micronsPerClick: 20 }));
    expect(describeGrind(withMicrons, 75)).toBe("7 turns + 5 · dial 7.5 · ≈ 1,500 µm");
  });

  it("NumberTick(100,10), 20 µm: 75 → 7.5 / 'dial 7.5 · ≈ 1,500 µm'", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra", maxSetting: 200 }), kUltra());
    expect(s.dial).toEqual(DialFormats.numberTick(100, 10));
    expect(reading(s, 75)).toBe("7.5");
    expect(describeGrind(s, 75)).toBe("dial 7.5 · ≈ 1,500 µm");
  });

  it("NumberTick(100,10): 125 → '1 turn + 2.5' / 'dial 1 turn + 2.5'", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 100, catalogId: "1zpresso-k-ultra" }), kUltra());
    expect(reading(s, 125)).toBe("1 turn + 2.5");
    expect(describeGrind(s, 125)).toBe("dial 1 turn + 2.5");
  });

  it("NumberTick(90,10): 75 → 7.5 / 'dial 7.5'", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 90, catalogId: "1zpresso-k-max" }), kMax());
    expect(s.dial).toEqual(DialFormats.numberTick(90, 10));
    expect(reading(s, 75)).toBe("7.5");
    expect(describeGrind(s, 75)).toBe("dial 7.5");
  });

  it("TurnsAndClicks(12): 25 → '2 turns + 1'", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 12 }));
    expect(reading(s, 25)).toBe("2 turns + 1");
    expect(describeGrind(s, 25)).toBe("2 turns + 1");
    expect(reading(s, 13)).toBe("1 turn + 1");
  });

  it("Plain numbers: 14.5 → null / ''", () => {
    const s = grindScaleOf(row({ settingStyle: "numbers", clicksPerRotation: 0, minSetting: 0, maxSetting: 50, step: 0.5 }));
    expect(reading(s, 14.5)).toBeNull();
    expect(describeGrind(s, 14.5)).toBe("");
    expect(format(s, 14.5)).toBe("14.5");
  });

  it("no value yet is '' (the brew screen says 'Set your grind'); clicks without a rotation say 'clicks from zero'", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 0 }));
    expect(describeGrind(s, null)).toBe("");
    expect(describeGrind(s, 18)).toBe("clicks from zero");
  });
});

describe("dial format resolution (A§3.2 rules, lead decisions 1 and 2)", () => {
  it("encodes and decodes", () => {
    for (const f of [DialFormats.plain(), DialFormats.turnsAndClicks(12), DialFormats.turnsDotClicks(10), DialFormats.numberTick(100, 10)]) {
      const cpr = f.kind === "number-tick" ? f.clicksPerRotation : f.kind === "plain" ? 0 : f.clicksPerTurn;
      expect(decodeDialFormat(encodeDialFormat(f), cpr)).toEqual(f);
    }
    expect(decodeDialFormat("", 100)).toBeNull();
    // As Kotlin: a numbered dial with an unknown wrap still decodes (cpr 0 = never wraps).
    expect(decodeDialFormat("number-tick:10", 0)).toEqual(DialFormats.numberTick(0, 10));
    expect(decodeDialFormat("number-tick:11", 100)).toBeNull();
    expect(decodeDialFormat("turns-dot", 100)).toBeNull(); // 1.x behaviour only for cpr ≤ 10
    expect(decodeDialFormat("garbage", 100)).toBeNull();
  });

  it("the user's own choice wins", () => {
    const g = row({ settingStyle: "clicks", clicksPerRotation: 100, catalogId: "1zpresso-k-ultra", dialFormat: "turns" });
    expect(resolveDialFormat(g, kUltra())).toEqual(DialFormats.turnsAndClicks(100));
  });

  it("the catalog's dialFormat applies when the row carries the catalog's rotation", () => {
    const withFormat: CatalogGrinder = { ...kUltra(), id: "x-test", setting: { ...kUltra().setting, dialFormat: "number-tick:10" } };
    expect(resolveDialFormat(row({ settingStyle: "clicks", clicksPerRotation: 100, catalogId: "x-test" }), withFormat)).toEqual(DialFormats.numberTick(100, 10));
  });

  it("the owner's 1.x row (cpr 10) keeps today's words even once linked, until they accept the catalog numbers", () => {
    const owner = row({ settingStyle: "clicks", clicksPerRotation: 10, micronsPerClick: null, catalogId: "1zpresso-k-ultra" });
    const withFormat: CatalogGrinder = { ...kUltra(), setting: { ...kUltra().setting, dialFormat: "number-tick:10" } };
    expect(resolveDialFormat(owner, kUltra())).toEqual(DialFormats.turnsDotClicks(10));
    expect(resolveDialFormat(owner, withFormat)).toEqual(DialFormats.turnsDotClicks(10));
    // Stored values are never rescaled: 75 is still 75 clicks.
    expect(describeGrind(grindScaleOf(owner, kUltra()), 75)).toBe("7 turns + 5 · dial 7.5");
  });

  it("every numbered-dial id exists and is a 10-clicks-per-number dial in the catalog text", () => {
    for (const id of NUMBERED_DIAL_IDS) {
      const g = catalog.grinder(id);
      expect(g, id).not.toBeNull();
      expect(g!.setting.dialReading, id).toMatch(/10 clicks (between|per) number/);
    }
  });
});

describe("readings back to stored values", () => {
  const nt = () => grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 100, catalogId: "1zpresso-k-ultra" }), kUltra());
  it("NumberTick", () => {
    const s = nt();
    expect(parseReading(s, "7.5")).toBe(75);
    expect(parseReading(s, "8")).toBe(80);
    expect(parseReading(s, "1+2.5")).toBe(125);
    expect(parseReading(s, " 1 + 2,5 ")).toBe(125);
    expect(parseReading(s, "12.3")).toBeNull(); // number × 10 ≥ clicks per rotation: write it as 1 + 2.3
    expect(fromReading(s, { turns: 0, number: 7, tick: 10 })).toBeNull();
  });
  it("TurnsAndClicks and Plain", () => {
    const t = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 12 }));
    expect(parseReading(t, "2+1")).toBe(25);
    expect(parseReading(t, "2.13")).toBeNull();
    const p = grindScaleOf(row({ settingStyle: "numbers" }));
    expect(parseReading(p, "14,5")).toBe(14.5);
    expect(parseReading(p, "abc")).toBeNull();
  });
});

describe("A§3.3 move sizes (the app's own rule of thumb)", () => {
  it("K-Ultra at 20 µm → 2 / 6 / 13 / 25 clicks", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra" }), kUltra());
    expect(moves(s, "pourover")).toEqual({ nudge: 2, clearMiss: 6, maxAtOnce: 13, jumpLimit: 25, basis: "microns" });
  });
  it("legacy cpr 10 without microns is exactly 1.x", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 10 }));
    expect(moves(s, "pourover")).toEqual({ nudge: 2, clearMiss: 10, maxAtOnce: 20, jumpLimit: 20, basis: "legacy-turn" });
  });
  it("a method range sets the sizes when there are no microns", () => {
    const encore = catalog.grinder("baratza-encore")!;
    const s = grindScaleOf(row({ settingStyle: "numbers", minSetting: 1, maxSetting: 40, step: 1, catalogId: encore.id }), encore);
    const m = moves(s, "pourover");
    expect(m.basis).toBe(encore.ranges.pourover ? "range" : "generic");
    expect(m.nudge).toBeGreaterThanOrEqual(1);
  });
  it("generic fallback in steps", () => {
    const s = grindScaleOf(row({ settingStyle: "stepless", step: 0.1 }));
    expect(moves(s, null)).toEqual({ nudge: 0.2, clearMiss: 0.5, maxAtOnce: 1, jumpLimit: 2, basis: "generic" });
  });
});

describe("grindLooksTyped (A§3.4, replaces 'under one turn is finer than espresso'; true = distrust, as Kotlin)", () => {
  it("K-Ultra at 100 per turn: per-method ranges, not one turn", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra" }), kUltra());
    const floor = s.ranges.espresso!.from;
    expect(grindLooksTyped(75, s, "pourover")).toBe(false); // the owner's filter brews stay trusted
    expect(grindLooksTyped(60, s, "pourover")).toBe(false);
    expect(grindLooksTyped(7, s, "pourover")).toBe(floor > 7); // finer than espresso: a dial reading
    expect(grindLooksTyped(7, s, "espresso")).toBe(false); // espresso is never flagged
    expect(grindLooksTyped(0, s, "pourover")).toBe(false);
  });
  it("legacy 10-click rows keep the 1.x rule", () => {
    const s = grindScaleOf(row({ settingStyle: "clicks", clicksPerRotation: 10 }));
    expect(grindLooksTyped(7, s, "pourover")).toBe(true);
    expect(grindLooksTyped(7, null, "pourover")).toBe(false);
    expect(grindLooksTyped(7, s, "espresso")).toBe(false);
    expect(grindLooksTyped(7, s, null)).toBe(true); // an unknown brewer counts as filter
    expect(grindLooksTyped(75, s, null)).toBe(false);
  });
  it("direction words", () => {
    expect(finerDirection(grindScaleOf(row({ settingStyle: "clicks" })))).toBe("FEWER clicks is finer");
    expect(finerDirection(grindScaleOf(row({ settingStyle: "numbers", lowerIsFiner: false })))).toBe("HIGHER settings are finer");
  });
});
