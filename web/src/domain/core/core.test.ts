import { describe, expect, it } from "vitest";
import { makeBrewer } from "./brewer";
import { BrewMath, DEFAULT_TASTE } from "./evaluation";
import { clipWords, clock, dayLabel, daysOffRoastLabel, epochDayOf, epochDayToIso, grams, greeting, groupedInt, halves, isoToEpochDay, oneDp, plural, ratio, screenDate, screenDayLabel, screenTime, timeLabel, todayEyebrow } from "./format";
import { EMPTY_LOG_FILTER, filterLog, isFilterActive, logCountLabel, type LogFilterable } from "./logQuery";
import { quickRead, type QuickReadInput } from "./quickRead";
import type { Recipe } from "./recipe";
import { cupLabel, roastStatus, roastWord, scoreStatus, signedClock, timeStatus } from "./status";
import { bigWeight, cToF, temp, tempLabel, tempUnit } from "./units";

describe("format (Format.kt)", () => {
  it("grams drops .0 but keeps a real decimal", () => {
    expect(grams(18)).toBe("18");
    expect(grams(18.5)).toBe("18.5");
    expect(grams(18.02)).toBe("18");
    expect(grams(16.666)).toBe("16.7");
  });
  it("one decimal place, always a dot", () => {
    expect(oneDp(16.666)).toBe("16.7");
    expect(ratio(15, 250)).toBe("1:16.7");
    expect(ratio(0, 250)).toBe("—");
  });
  it("clock and time labels", () => {
    expect(clock(210)).toBe("3:30");
    expect(clock(65)).toBe("1:05");
    const d = new Date(2026, 10, 17, 8, 14);
    expect(timeLabel(d.getTime())).toBe("08:14");
  });
  it("dayLabel: today, yesterday, else '5 Nov'", () => {
    const now = new Date(2026, 10, 17, 9, 0).getTime();
    expect(dayLabel(new Date(2026, 10, 17, 1, 0).getTime(), now)).toBe("Today");
    expect(dayLabel(new Date(2026, 10, 16, 23, 0).getTime(), now)).toBe("Yesterday");
    expect(dayLabel(new Date(2026, 10, 5, 12, 0).getTime(), now)).toBe("5 Nov");
    expect(dayLabel(new Date(2026, 8, 18, 12, 0).getTime(), now)).toBe("18 Sep");
  });
  it("greeting and roast labels", () => {
    expect(greeting(3)).toBe("Still up");
    expect(greeting(8)).toBe("Morning");
    expect(greeting(14)).toBe("Afternoon");
    expect(greeting(19)).toBe("Evening");
    expect(greeting(23)).toBe("Late one");
    expect(daysOffRoastLabel(null)).toBe("—");
    expect(daysOffRoastLabel(0)).toBe("Roasted today");
    expect(daysOffRoastLabel(1)).toBe("1 day off roast");
    expect(daysOffRoastLabel(12)).toBe("12 days off roast");
  });
  it("epoch days are local calendar dates (LocalDate.toEpochDay)", () => {
    expect(epochDayOf(new Date(1970, 0, 2, 23, 59).getTime())).toBe(1);
    expect(isoToEpochDay("2026-11-17")).toBe(20774);
    expect(epochDayToIso(20774)).toBe("2026-11-17");
    expect(isoToEpochDay("17/11/2026")).toBeNull();
    expect(groupedInt(1500)).toBe("1,500");
  });
});

describe("units (A§7.8, lead decision 3)", () => {
  const c = { fahrenheit: false, ounces: false };
  const f = { fahrenheit: true, ounces: true };
  it("°F rounds to whole degrees", () => {
    expect(cToF(93)).toBe(199);
    expect(cToF(100)).toBe(212);
    expect(temp(f, 92)).toBe("198");
    expect(temp(c, 92)).toBe("92");
    expect(tempUnit(f)).toBe("°F");
    expect(tempLabel(c, 94)).toBe("94 °C");
  });
  it("bag sizes and totals in ounces to 1 dp", () => {
    expect(bigWeight(f, 250)).toEqual(["8.8", "oz"]);
    expect(bigWeight(c, 250)).toEqual(["250", "g"]);
    expect(bigWeight(f, 1000)).toEqual(["35.3", "oz"]);
  });
});

describe("status (Status.kt)", () => {
  it("score bands", () => {
    expect([10, 8, 7, 6, 5, 4, 3, 1, 0].map(scoreStatus)).toEqual(["good", "good", "neutral", "neutral", "attention", "attention", "fault", "fault", "unrated"]);
    expect(cupLabel(9)).toBe("GREAT CUP");
    expect(cupLabel(0)).toBe("UNRATED");
  });
  it("time status: ±15 s on time, ±45 s attention, beyond fault", () => {
    expect(timeStatus(210, 210)).toEqual(["good", "On time"]);
    expect(timeStatus(225, 210)).toEqual(["good", "On time"]);
    expect(timeStatus(226, 210)).toEqual(["attention", "Slow by 0:16"]);
    expect(timeStatus(165, 210)).toEqual(["attention", "Fast by 0:45"]);
    expect(timeStatus(164, 210)).toEqual(["fault", "Fast by 0:46"]);
    expect(timeStatus(200, 0)).toEqual(["neutral", "No target"]);
    expect(timeStatus(0, 210)).toEqual(["unrated", "No time"]);
  });
  it("roast freshness and signed clock", () => {
    expect([roastStatus(null), roastStatus(2), roastStatus(10), roastStatus(40)]).toEqual(["unrated", "attention", "neutral", "attention"]);
    expect([roastWord(1), roastWord(10), roastWord(31)]).toEqual(["resting", null, "fading"]);
    expect(signedClock(-65)).toBe("−1:05");
    expect(signedClock(12)).toBe("+0:12");
    expect(signedClock(0)).toBe("0:00");
  });
});

describe("row text", () => {
  it("a count agrees with its noun", () => {
    expect([plural(0, "brew"), plural(1, "brew"), plural(2, "brew")]).toEqual(["0 brews", "1 brew", "2 brews"]);
    expect([plural(1, "day"), plural(3, "day")]).toEqual(["1 day", "3 days"]);
    expect([plural(1, "analysis", "analyses"), plural(4, "analysis", "analyses")]).toEqual(["1 analysis", "4 analyses"]);
  });
  it("free text is cut at a word, never inside one", () => {
    expect(clipWords("about 10 seconds finer than last time", 14)).toBe("about 10…");
    expect(clipWords("  medium-fine  ", 14)).toBe("medium-fine");
    // A word that ends exactly at the limit stays whole.
    expect(clipWords("83 clicks, 8.3 on the dial", 14)).toBe("83 clicks, 8.3…");
    expect(clipWords("medium-fine, like table salt", 14)).toBe("medium-fine…");
    // One long word has no boundary to use.
    expect(clipWords("supercalifragilistic", 14)).toBe("supercalifragi…");
  });
});

describe("BrewMath", () => {
  it("extraction yield and retention", () => {
    expect(BrewMath.extractionYield(15, 210, 1.4)).toBeCloseTo(19.6);
    expect(BrewMath.extractionYield(15, null, 1.4)).toBeNull();
    expect(BrewMath.extractionYield(0, 210, 1.4)).toBeNull();
    expect(BrewMath.expectedRetentionG(15)).toBe(30);
    expect(BrewMath.expectedBeverageG(15, 250)).toBe(220);
    expect(BrewMath.expectedBeverageG(15, 20)).toBe(0);
  });
});

describe("quickRead v2 (BrewReport.quickRead)", () => {
  const recipe = {
    id: "r",
    steps: [
      { kind: "Pour", title: "", detail: "", startSec: 0, endSec: 120, targetWaterG: 250 },
      { kind: "Drawdown", title: "", detail: "", startSec: 120, endSec: 180, targetWaterG: null },
    ],
    brewer: makeBrewer({ id: "hario-v60-02", label: "V60 02", category: "cone" }),
  } as unknown as Recipe;
  const base: QuickReadInput = { taste: DEFAULT_TASTE, faults: [], bedShape: null, channeling: null, totalTimeSec: 0, daysOffRoast: null, extractionYield: null };

  it("under-extracted, flat bed, long run, fresh beans, measured EY", () => {
    expect(quickRead({ ...base, faults: ["Sour"], bedShape: "flat", totalTimeSec: 250, daysOffRoast: 2, extractionYield: 20 }, recipe)).toEqual([
      "Grind a little finer next time.",
      "Flat bed. Whatever you did not like about this cup, distribution was not it.",
      "You ran 1:10 past the recipe's 3:00. That is a lot of extra contact — grind coarser if the cup came out heavy.",
      "Only 2 days off roast. Expect an aggressive bloom and a faster-than-usual drawdown from all that CO2. Not a fault.",
      "Measured extraction yield 20.0%. For filter, most people land somewhere between 18 and 22 — but taste beats the number.",
    ]);
  });

  it("over-extracted, stranded grounds, channelling, fast, old beans", () => {
    expect(quickRead({ ...base, faults: ["Bitter", "Drying"], bedShape: "high-and-dry", channeling: "obvious", totalTimeSec: 120, daysOffRoast: 40 }, recipe)).toEqual([
      "Grind a little coarser next time.",
      "Grounds stranded up the wall never brewed at all — that is dose you paid for and did not extract. Swirl right after the bloom, or pour more centrally.",
      "You called obvious channelling — fix that before reading anything else into the taste. Channelled brews taste under and over extracted at the same time.",
      "You finished 1:00 early against 3:00. A fast drawdown with a thin cup means go finer.",
      "40 days off roast. Flat and papery at this age is the beans, not your technique.",
    ]);
  });

  it("pre-ground never says finer or coarser; no scale says tablespoons", () => {
    const pre = { ...base, grindKind: "preground", faults: ["Too weak", "Bitter"], totalTimeSec: 250 };
    expect(quickRead(pre, recipe, { hasScale: false, fahrenheit: true })).toEqual([
      "Pour more slowly, and use water just off the boil.",
      "Or use a little more coffee: +1 tbsp.",
      "Use water a little cooler, about 194 °F.",
      "You ran 1:10 past the recipe's 3:00. Pre-ground coffee often drains slowly; if the cup came out heavy, use a little less coffee next time.",
    ]);
  });

  it("a French press never hears about drawdown or the bed", () => {
    const press = makeBrewer({ id: "french-press", label: "French press", category: "immersion", method: "frenchPress" });
    const lines = quickRead({ ...base, faults: ["Sour"], bedShape: "crater", totalTimeSec: 100, daysOffRoast: 2 }, recipe, { brewer: press });
    expect(lines).toEqual([
      "Steep 30 seconds longer, or grind a little finer.",
      "You stopped 1:20 before the recipe's 3:00. If it tasted thin or sour, give it the full time.",
      "Only 2 days off roast. Expect a lively bloom from all that CO2. Not a fault.",
    ]);
    expect(lines.join(" ")).not.toMatch(/drawdown/i);
  });

  it("time advice is skipped when the time cannot be the whole brew", () => {
    expect(quickRead({ ...base, totalTimeSec: 60 }, recipe)).toEqual([quickRead(base, null)[0]]);
    expect(quickRead({ ...base, totalTimeSec: 250 }, recipe, { timeIgnored: true })).toEqual([quickRead(base, null)[0]]);
  });

  it("one day off roast is singular", () => {
    expect(quickRead({ ...base, daysOffRoast: 1 }, null)[0]).toMatch(/^Only 1 day off roast\./);
  });

  it("nothing logged that says anything", () => {
    expect(quickRead(base, null)).toEqual([
      "Nothing obviously wrong in what you logged. If the cup still is not right, the next useful move is changing one variable and brewing it again.",
    ]);
  });
});

describe("v4 screen formats (locale-aware; exports stay pinned)", () => {
  it("Today's eyebrow and screen times in en-US", () => {
    const d = new Date(2026, 10, 19, 19, 5).getTime();
    expect(todayEyebrow(d, "en-US")).toBe("EVENING · THU, NOV 19");
    expect(screenTime(d, "en-US")).toBe("7:05 PM");
    expect(screenDate(d, "en-GB")).toBe("19 Nov");
    expect(screenDayLabel(d, d, "en-US")).toBe("Today");
    // The pinned formats exports and prompts use are unchanged.
    expect(timeLabel(d)).toBe("19:05");
  });
  it("halves", () => {
    expect([halves(1), halves(1.5), halves(4.5), halves(0.5)]).toEqual(["1", "1½", "4½", "½"]);
  });
});

describe("v4 log filters", () => {
  const b = (p: Partial<LogFilterable>): LogFilterable => ({
    recipeName: "", recipeAuthor: "", beanLabel: "", grinderLabel: "", brewer: "", notes: "", nextTime: "", descriptors: [],
    startedAt: 0, overall: 0, isFavourite: false, recipeId: "", brewerId: null, ...p,
  });
  const brews = [
    b({ id: 1, startedAt: 1, overall: 9, recipeId: "hoffmann-v60", brewerId: "hario-v60-02", beanLabel: "A" }),
    b({ id: 2, startedAt: 2, overall: 6, isFavourite: true, recipeName: "One-off", brewer: "Chemex", beanLabel: "B" }),
    b({ id: 3, startedAt: 3, overall: 8, recipeId: "hoffmann-v60", brewerId: "hario-v60-02", notes: "Café crème", beanLabel: "A" }),
  ];
  it("filters, sorts and counts", () => {
    expect(filterLog(brews, EMPTY_LOG_FILTER).map((x) => x.id)).toEqual([3, 2, 1]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, sort: "best" }).map((x) => x.id)).toEqual([1, 3, 2]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, rated8: true, coffee: "A" }).map((x) => x.id)).toEqual([3, 1]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, favourites: true }).map((x) => x.id)).toEqual([2]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, recipe: "One-off" }).map((x) => x.id)).toEqual([2]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, brewer: "Chemex" }).map((x) => x.id)).toEqual([2]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, withPhotos: true }, new Set([1])).map((x) => x.id)).toEqual([1]);
    expect(filterLog(brews, { ...EMPTY_LOG_FILTER, query: "cafe" }).map((x) => x.id)).toEqual([3]);
    expect(isFilterActive(EMPTY_LOG_FILTER)).toBe(false);
    expect([logCountLabel(1), logCountLabel(12)]).toEqual(["1 brew", "12 brews"]);
  });
});
