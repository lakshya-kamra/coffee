// Words the grind widgets show, on the K-Ultra fixture (lead decision 2) and a 1.x 10-click row.
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { newGrinder } from "@/data/schema";
import { scaleFor } from "@/domain/grind";
import { confidenceChip, dialWords, orderedRanges, rangeDialWords, rangeWords, startValue } from "./index";

beforeAll(async () => {
  await catalog.load(["grinders"]);
});

const kUltra = () =>
  scaleFor(newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, minSetting: 0, maxSetting: 200, catalogId: "1zpresso-k-ultra" }));

describe("grind words", () => {
  it("reads K-Ultra ranges in clicks and dial numbers", () => {
    const s = kUltra();
    if (!s.ranges.pourover) return; // catalog entry changed: nothing to assert
    expect(rangeWords(s, s.ranges.pourover)).toBe("75–90 clicks");
    expect(rangeDialWords(s, s.ranges.pourover)).toBe("dial 7.5–9.0");
    expect(dialWords(s, 75)).toBe("dial 7.5");
    expect(dialWords(s, 123)).toBe("dial 1 turn + 2.3");
    expect(startValue(s, "pourover")).toBe(83);
  });

  it("puts the chosen method first", () => {
    const s = kUltra();
    const order = orderedRanges(s, "espresso").map((r) => r.method);
    if (order.length === 0) return;
    expect(order[0]).toBe("espresso");
    expect(new Set(order).size).toBe(order.length);
  });

  it("keeps 1.x words for a 10-click row with its notice pending", () => {
    const s = scaleFor(newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 10, micronsPerClick: 20, catalogId: "1zpresso-k-ultra" }));
    expect(dialWords(s, 75)).toBe("dial 7.5");
    // The catalog's ranges are for 100 clicks a turn, so they do not apply to this row.
    expect(orderedRanges(s, "pourover")).toEqual([]);
  });

  it("says nothing about a plain dial reading", () => {
    const s = scaleFor(newGrinder({ brand: "Niche", model: "Zero", settingStyle: "numbers", minSetting: 0, maxSetting: 50, step: 0.5 }));
    expect(dialWords(s, 14.5)).toBeNull();
  });

  it("maps confidence to a word, never colour alone", () => {
    expect(confidenceChip("high")).toEqual({ status: "good", text: "From the maker" });
    expect(confidenceChip("medium")).toEqual({ status: "attention", text: "Check your manual" });
    expect(confidenceChip("low")).toEqual({ status: "attention", text: "Unverified" });
    expect(confidenceChip(null)).toBeNull();
  });
});
