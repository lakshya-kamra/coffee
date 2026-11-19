// PassportStats on a fixture: totals, streaks, best cup, ranked lists and the grind series.
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { epochDayOf } from "@/domain/core/format";
import { newBean, newBrew, newGrinder, type BrewRow } from "@/data/schema";
import { grindChartModel, gridSteps } from "./charts";
import { grindSeries, hasRecords, lastFiveAvg, longestStreak, passport, personalBests, prevFiveAvg, rankedBeans, rankedRecipes, scoreSeries, streaks } from "./passportStats";

beforeAll(async () => {
  await catalog.load(["brewers", "grinders"]);
});

const DAY = 86_400_000;
// Local noon, so a day boundary never depends on the machine's time zone.
const noon = (daysAgo: number) => {
  const d = new Date();
  d.setHours(12, 0, 0, 0);
  return d.getTime() - daysAgo * DAY;
};

let nextId = 1;
const brew = (p: Partial<BrewRow>): BrewRow => newBrew({ id: nextId++, brewer: "Hario V60", doseG: 15, waterG: 250, ...p });

const logs: BrewRow[] = [
  brew({ startedAt: noon(0), overall: 8, recipeId: "a", recipeName: "A", grinderId: 1, grindClicks: 70, beanId: 1, beanLabel: "Square Mile — Red Brick", tempC: 94 }),
  brew({ startedAt: noon(1), overall: 6, recipeId: "a", recipeName: "A", grinderId: 1, grindClicks: 74, beanId: 1, beanLabel: "Square Mile — Red Brick" }),
  brew({ startedAt: noon(2), overall: 8, recipeId: "a", recipeName: "A", grinderId: 1, grindClicks: 72, beanId: 2, beanLabel: "Tim Wendelboe — Kayon" }),
  brew({ startedAt: noon(5), overall: 0, recipeId: "b", recipeName: "B", grinderId: 1, grindClicks: 60, beanLabel: "Gone — Old bag" }),
  brew({ startedAt: noon(6), overall: 4, recipeId: "b", recipeName: "B", brewer: "AeroPress", beanId: 2, beanLabel: "Tim Wendelboe — Kayon" }),
];

describe("passport", () => {
  it("totals, averages over rated brews, stamps and span", () => {
    const p = passport(logs);
    expect(p.brews).toBe(5);
    expect(p.waterG).toBe(1250);
    expect(p.coffeeG).toBe(75);
    expect(p.avg).toBeCloseTo((8 + 6 + 8 + 4) / 4);
    expect(p.best).toBe(8);
    expect(p.greatCups).toBe(2);
    expect(p.spanDays).toBe(7);
    expect(p.recipesUsed).toBe(2);
    expect(p.coffees).toBe(3);
    expect(p.stamps.map((s) => [s.label, s.count])).toEqual([["Hario V60", 4], ["AeroPress", 1]]);
  });

  it("one stamp per brewer, whatever words each brew kept", () => {
    const mixed = [
      brew({ brewer: "Hario V60", brewerId: "hario-v60-02" }),
      brew({ brewer: "V60 02", brewerId: "hario-v60-02" }),
      brew({ brewer: "V60", brewerId: "hario-v60-02" }),
      brew({ brewer: "Grandad's pot", brewerId: null }),
    ];
    expect(passport(mixed).stamps.map((s) => [s.brewer?.id ?? null, s.count])).toEqual([["hario-v60-02", 3], [null, 1]]);
  });

  it("streaks: current run ends today or yesterday; best is the longest", () => {
    expect(streaks(logs)).toEqual([3, 3]);
    expect(streaks(logs.slice(1))).toEqual([2, 2]);
    const run = longestStreak(logs)!;
    expect(run).toMatchObject({ days: 3, end: epochDayOf(noon(0)), current: true });
    expect(streaks([])).toEqual([0, 0]);
  });

  it("score series is oldest first and skips unrated", () => {
    expect(scoreSeries(logs).map((p) => p.score)).toEqual([4, 8, 6, 8]);
    expect(lastFiveAvg(logs)).toBeCloseTo(6.5);
    expect(prevFiveAvg(logs)).toBeNull();
  });

  it("best cup: ties go to the most recent", () => {
    const b = personalBests(logs, []);
    expect(b.bestCup).toMatchObject({ score: 8, uid: logs[0]!.uid, clicks: 70, tempC: 94 });
    expect(hasRecords(b)).toBe(true);
  });

  it("best coffee needs two rated brews; labels split when the bean row is gone", () => {
    const beans = [newBean({ id: 1, name: "Red Brick", roaster: "Square Mile" }), newBean({ id: 2, name: "Kayon", roaster: "Tim Wendelboe" })];
    const b = personalBests(logs, beans);
    expect(b.bestCoffee).toMatchObject({ name: "Red Brick", sub: "Square Mile", count: 2, avg: 7 });
    const ranked = rankedBeans(logs, []);
    expect(ranked.map((r) => r.name)).toContain("Old bag");
  });

  it("recipes ranked by count then average", () => {
    expect(rankedRecipes(logs, []).map((r) => [r.name, r.count])).toEqual([["A", 3], ["B", 2]]);
  });
});

describe("grind series", () => {
  it("one grinder on its most-brewed recipe, best zone over scores ≥ 7", () => {
    const g = grindSeries(logs, [newGrinder({ id: 1, brand: "1Zpresso", model: "K-Ultra", clicksPerRotation: 100, catalogId: "1zpresso-k-ultra" })])!;
    expect(g.mainRecipeId).toBe("a");
    expect(g.points.map((p) => p.clicks)).toEqual([72, 74, 70]);
    expect(g.first).toBe(72);
    expect(g.latest).toBe(70);
    expect(g.bestZone).toEqual({ first: 70, last: 72 });
    expect(g.otherBrews).toBe(1);
    expect(g.grinderLabel).toBe("K-Ultra");
    expect(g.dialFormat).toBe("number-tick:10");
  });

  it("needs two points on one recipe", () => {
    expect(grindSeries(logs.slice(3), [])).toBeNull();
  });

  it("chart model: coarser is up for lower-is-finer dials", () => {
    const g = grindSeries(logs, [])!;
    const m = grindChartModel(g);
    const y = (c: number) => m.points.find((p) => p.clicks === c)!.y;
    expect(y(74)).toBeLessThan(y(70));
    expect(m.points[m.points.length - 1]!.latest).toBe(true);
    expect(m.band!.label).toBe("BEST 70–72");
    expect(gridSteps(67, 77)).toEqual([68, 70, 72, 74, 76]);
  });
});
