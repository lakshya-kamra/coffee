// Compare two brews (v4 §3.8): the difference words and the picker's order.
import { describe, expect, it } from "vitest";
import { newBrew, type BrewRow } from "@/data/schema";
import { coffeeWord, compareRows, comparePath, comparePickerGroups, dateWord, faultsWord, gramsWord, grindWord, ratioWord, scoreWord, tempWord, timeWord } from "./compare";

const DAY = 86_400_000;
const T0 = new Date(2026, 10, 12, 8, 14).getTime();

function brew(p: Partial<BrewRow>): BrewRow {
  return newBrew({ recipeId: "hoffmann-v60", recipeName: "The Ultimate V60 Technique", grinderId: 1, grinderLabel: "1Zpresso K-Ultra", beanId: 1, beanLabel: "Onyx Coffee Lab — Geometry", doseG: 15, waterG: 250, tempC: 96, totalTimeSec: 180, ...p }, p.startedAt ?? T0);
}

describe("difference words", () => {
  it("grind: finer or coarser by clicks, by the grinder's direction; only on the same grinder", () => {
    expect(grindWord(brew({ grindClicks: 80 }), brew({ grindClicks: 75 }))).toBe("finer by 5 clicks");
    expect(grindWord(brew({ grindClicks: 80 }), brew({ grindClicks: 81 }))).toBe("coarser by 1 click");
    expect(grindWord(brew({ grindClicks: 80 }), brew({ grindClicks: 75 }), false)).toBe("coarser by 5 clicks");
    expect(grindWord(brew({ grindValue: 4.5 }), brew({ grindValue: 4.2 }))).toBe("finer by 0.3");
    expect(grindWord(brew({ grindClicks: 80 }), brew({ grindClicks: 80 }))).toBeNull();
    expect(grindWord(brew({ grindClicks: 80 }), brew({ grinderId: 2, grindClicks: 40 }))).toBe("other grinder");
    expect(grindWord(brew({ grindClicks: 80 }), brew({ grindClicks: null }))).toBeNull();
  });

  it("time, temperature, grams, ratio, score", () => {
    expect(timeWord(180, 200)).toBe("+0:20");
    expect(timeWord(200, 185)).toBe("−0:15");
    expect(timeWord(180, 180)).toBeNull();
    expect(timeWord(0, 180)).toBeNull();
    expect(tempWord(94, 96, { fahrenheit: false })).toBe("2 °C hotter");
    expect(tempWord(96, 93, { fahrenheit: false })).toBe("3 °C cooler");
    expect(tempWord(94, 96, { fahrenheit: true })).toBe("4 °F hotter");
    expect(tempWord(96, 96, { fahrenheit: false })).toBeNull();
    expect(gramsWord(15, 17)).toBe("+2 g");
    expect(gramsWord(18, 17.5)).toBe("−0.5 g");
    expect(ratioWord(15, 250, 16, 250)).toBe("stronger");
    expect(ratioWord(15, 250, 15, 270)).toBe("weaker");
    expect(ratioWord(15, 250, 15, 251)).toBeNull();
    expect(scoreWord(7, 8)).toBe("+1");
    expect(scoreWord(8, 6)).toBe("−2");
    expect(scoreWord(0, 6)).toBeNull();
  });

  it("dates, faults and coffee", () => {
    expect(dateWord(T0, T0 + 3 * DAY)).toBe("3 days later");
    expect(dateWord(T0, T0 - DAY)).toBe("1 day earlier");
    expect(dateWord(T0, T0 + 3600_000)).toBe("same day");
    expect(faultsWord(["Thin"], ["Bitter"])).toBe("+Bitter, −Thin");
    expect(faultsWord([], [])).toBeNull();
    expect(coffeeWord(brew({ daysOffRoast: 8 }), brew({ daysOffRoast: 13 }))).toBe("+5 days off roast");
    expect(coffeeWord(brew({ daysOffRoast: 8 }), brew({ beanId: 2, beanLabel: "Square Mile — Red Brick" }))).toBe("other coffee");
  });
});

describe("compare rows", () => {
  it("lists every row in order with a word only where it changed", () => {
    const a = brew({ uid: "a", grindClicks: 80, overall: 7, faults: ["Thin"] });
    const b = brew({ uid: "b", startedAt: T0 + 2 * DAY, grindClicks: 75, overall: 8, totalTimeSec: 200, tempC: 94 });
    const rows = compareRows(a, b, { units: { fahrenheit: false, ounces: false }, planA: 180, planB: 180, lowerIsFiner: true, waterLabel: "Water", dateOf: () => "d" });
    expect(rows.map((r) => r.label)).toEqual(["Date", "Recipe", "Coffee", "Grind", "Dose", "Water", "Ratio", "Temp", "Time vs plan", "Overall", "Faults", "Notes"]);
    const word = (label: string) => rows.find((r) => r.label === label)!.word;
    expect(word("Grind")).toBe("finer by 5 clicks");
    expect(word("Time vs plan")).toBe("+0:20");
    expect(word("Temp")).toBe("2 °C cooler");
    expect(word("Overall")).toBe("+1");
    expect(word("Faults")).toBe("−Thin");
    expect(word("Recipe")).toBeNull();
    expect(word("Dose")).toBeNull();
    expect(rows.find((r) => r.label === "Time vs plan")!.right).toBe("3:20 (+0:20 vs plan)");
  });
});

describe("compare picker", () => {
  it("same recipe first, then same coffee, then the rest, newest first, without the brew itself", () => {
    const base = brew({ uid: "base" });
    const brews = [
      base,
      brew({ uid: "r1", startedAt: T0 - DAY }),
      brew({ uid: "r2", startedAt: T0 + DAY }),
      brew({ uid: "c1", recipeId: "kasuya-46", recipeName: "4:6" }),
      brew({ uid: "x1", recipeId: "kasuya-46", beanId: 2, beanLabel: "Other" }),
    ];
    const groups = comparePickerGroups(base, brews);
    expect(groups.map((g) => [g.title, g.brews.map((b) => b.uid)])).toEqual([
      ["Same recipe", ["r2", "r1"]],
      ["Same coffee", ["c1"]],
      ["Everything else", ["x1"]],
    ]);
    expect(comparePath("a b", "c")).toBe("/log/a%20b/compare/c");
  });
});
