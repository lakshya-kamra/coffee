import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newGrinder } from "@/data/schema";
import { DEFAULT_TASTE } from "@/domain/core/evaluation";
import { scaleFor } from "@/domain/grind";
import { cleanClockText, digitsOnly, grindWarning, parseClock, parseDecimal, tasteUntouched, toggleIn } from "./reviewModel";

beforeAll(async () => {
  await catalog.load(["grinders"]);
});

describe("parseClock (ReviewViewModel)", () => {
  it("reads m:ss, m.ss, mss and bare seconds", () => {
    expect(parseClock("3:28")).toBe(208);
    expect(parseClock("3.28")).toBe(208);
    expect(parseClock("3,28")).toBe(208);
    expect(parseClock("328")).toBe(208);
    expect(parseClock("45")).toBe(45);
    expect(parseClock("12:05")).toBe(725);
  });

  it("half-typed values read as they stand; impossible ones are null", () => {
    expect(parseClock("3:")).toBe(180);
    expect(parseClock("3:4")).toBe(184);
    expect(parseClock(":30")).toBe(30);
    expect(parseClock("3:60")).toBeNull();
    expect(parseClock("399")).toBeNull();
    expect(parseClock("3:2:1")).toBeNull();
    expect(parseClock("3:456")).toBeNull();
    expect(parseClock("")).toBeNull();
  });

  it("cleans input like the Kotlin field", () => {
    expect(cleanClockText("3m 28s!")).toBe("328");
    expect(cleanClockText("12:34:56:78")).toBe("12:34:");
    expect(digitsOnly("1a2b3c4d5", 4)).toBe("1234");
    expect(parseDecimal("18,5")).toBe(18.5);
    expect(parseDecimal(".5")).toBe(0.5);
    expect(parseDecimal("1.2.3")).toBeNull();
    expect(toggleIn(["a"], "b")).toEqual(["a", "b"]);
    expect(toggleIn(["a", "b"], "a")).toEqual(["b"]);
    expect(tasteUntouched(DEFAULT_TASTE)).toBe(true);
    expect(tasteUntouched({ ...DEFAULT_TASTE, body: 6 })).toBe(false);
  });
});

describe("grind warnings (DataQuality.kt wording)", () => {
  const legacy = newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 10 });
  const linked = newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra" });

  it("zero clicks is burrs touching", () => {
    expect(grindWarning({ grindClicks: 0, totalTimeSec: 208 }, linked, scaleFor(linked), "pourover")).toBe(
      "Grind logged as 0 clicks, which is burrs touching. Nothing brews for 3:28 at 0, so the grind was not recorded.",
    );
  });

  it("under one turn on a 1.x row, filter brews only", () => {
    expect(grindWarning({ grindClicks: 8, totalTimeSec: 0 }, legacy, scaleFor(legacy), "pourover")).toBe(
      "8 clicks is less than one full turn, finer than espresso. For a filter brew that is almost certainly the dial reading rather than total clicks; 8 on the dial would be 80 clicks.",
    );
    expect(grindWarning({ grindClicks: 8, totalTimeSec: 0 }, legacy, scaleFor(legacy), "espresso")).toBeNull();
  });

  it("finer than the catalog's espresso range on the K-Ultra (lead decision 2)", () => {
    const w = grindWarning({ grindClicks: 8, totalTimeSec: 0 }, linked, scaleFor(linked), "pourover");
    expect(w).toMatch(/^8 is finer than this grinder's espresso starting range \(30–45/);
    expect(w).toMatch(/On this grinder the dial reading 7.5 is stored as 75\.$/);
    expect(grindWarning({ grindClicks: 80, totalTimeSec: 0 }, linked, scaleFor(linked), "pourover")).toBeNull();
    expect(grindWarning({ grindClicks: null, totalTimeSec: 0 }, linked, scaleFor(linked), "pourover")).toBeNull();
  });
});
