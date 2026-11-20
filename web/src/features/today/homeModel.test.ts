import { describe, expect, it } from "vitest";
import { newBean, newBrew, type BrewRow } from "@/data/schema";
import { DEFAULT_SETTINGS } from "@/data/settings";
import { makeBrewer, type Brewer } from "@/domain/core/brewer";
import { epochDayOf } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { beanOnDeck, brewsThisWeek, brewsToday, daysOffRoast, headline, lastFiveAvg, lastGrindFor, mugFill, nextRecipe, playfulTitle, prevFiveAvg, startFromRecipes, streakDays } from "./homeModel";

const NOW = new Date(2026, 10, 17, 9, 0).getTime();
const DAY = 86_400_000;
const at = (daysAgo: number, hour = 8) => new Date(2026, 10, 17 - daysAgo, hour, 0).getTime();
const brew = (p: Partial<BrewRow>): BrewRow => newBrew(p, p.startedAt ?? NOW);

describe("Today counts (HomeViewModel)", () => {
  const logs = [brew({ startedAt: at(0) }), brew({ startedAt: at(0, 7) }), brew({ startedAt: at(1) }), brew({ startedAt: at(2) }), brew({ startedAt: at(6) }), brew({ startedAt: at(7) })];

  it("today, rolling seven days, streak", () => {
    expect(brewsToday(logs, NOW)).toBe(2);
    expect(brewsThisWeek(logs, NOW)).toBe(5);
    expect(streakDays(logs, NOW)).toBe(3);
    // Nothing today yet: a streak that ended yesterday still counts.
    expect(streakDays(logs.slice(2), NOW)).toBe(2);
    expect(streakDays([brew({ startedAt: NOW - 3 * DAY })], NOW)).toBe(0);
    expect(streakDays([], NOW)).toBe(0);
  });

  it("titles and the mug", () => {
    expect([0, 1, 2, 3, 7].map((n) => playfulTitle(n))).toEqual(["No coffee yet.", "One down.", "Two down.", "3 today. Steady on.", "7 today. Steady on."]);
    // Brewed on earlier days but not today: not the brand-new line (A7).
    expect(playfulTitle(0, true)).toBe("None yet today.");
    expect(playfulTitle(2, true)).toBe("Two down.");
    expect(mugFill(0)).toBe(0.12);
    expect(mugFill(2)).toBeCloseTo(2 / 3);
    expect(mugFill(9)).toBe(1);
  });
});

describe("headline (first match wins)", () => {
  const scored = (...scores: number[]) => scores.map((s, i) => brew({ overall: s, startedAt: NOW - i * 1000 }));

  it("no brews, dialled in, worth a tweak, steady", () => {
    expect(headline([])).toEqual({ status: "neutral", lead: "No brews yet", detail: "start with a recipe below" });
    expect(headline(scored(8, 8, 8))).toEqual({ status: "good", lead: "Dialled in", detail: "last 3 brews scored 8" });
    expect(headline(scored(9, 7, 8, 2))).toEqual({ status: "good", lead: "Dialled in", detail: "last 3 avg 8.0" });
    expect(headline(scored(4, 9, 9))).toEqual({ status: "attention", lead: "Worth a tweak", detail: "last cup 4/10" });
    expect(headline(scored(7, 6))).toEqual({ status: "neutral", lead: "Brewing steady", detail: "avg 6.5" });
    // Unrated brews never move an average.
    expect(headline([brew({ overall: 0 })])).toEqual({ status: "neutral", lead: "Brewing steady", detail: null });
  });

  it("last five against the five before", () => {
    const logs = scored(8, 8, 8, 8, 8, 6, 6, 6, 6, 6, 1);
    expect(lastFiveAvg(logs)).toBe(8);
    expect(prevFiveAvg(logs)).toBe(6);
  });
});

describe("next brew", () => {
  const v60 = makeBrewer({ id: "hario-v60-02", label: "Hario V60", method: "pourover" });
  const v60one = makeBrewer({ id: "hario-v60-01", label: "Hario V60-01", method: "pourover" });
  const aero = makeBrewer({ id: "aeropress", label: "AeroPress", method: "aeropress" });
  const r = (id: string, brewer: Brewer): Recipe => ({
    id, name: id, author: "", brewer, doseG: 15, waterG: 250, grind: "", tempC: 94, blurb: "", sourceNote: "", prep: [], tips: [], steps: [], accent: 0, isBuiltIn: true, fidelity: "", sources: [], basedOn: null, level: "", needs: [], prepNoScale: [],
  });
  const recipes = [r("aero-1", aero), r("v60-1", v60), r("v60-2", v60)];
  const byId = (id: string) => [v60, v60one, aero].find((b) => b.id === id) ?? null;

  it("last brewed, else owned brewer family, else first", () => {
    expect(nextRecipe(recipes, [], DEFAULT_SETTINGS, byId)?.id).toBe("aero-1");
    expect(nextRecipe(recipes, [], { ...DEFAULT_SETTINGS, ownedBrewerIds: ["hario-v60-01"] }, byId)?.id).toBe("v60-1");
    expect(nextRecipe(recipes, [brew({ recipeId: "v60-2" })], DEFAULT_SETTINGS, byId)?.id).toBe("v60-2");
    expect(nextRecipe(recipes, [brew({ recipeId: "v60-2" })], { ...DEFAULT_SETTINGS, lastRecipeId: "aero-1" }, byId)?.id).toBe("aero-1");
    expect(nextRecipe([], [], DEFAULT_SETTINGS, byId)).toBeNull();
  });

  it("Start from: owned families first, without the next brew, capped", () => {
    const families = new Set(["pourover:V60"]);
    expect(startFromRecipes(recipes, families, "v60-1", 5).map((x) => x.id)).toEqual(["v60-2", "aero-1"]);
    expect(startFromRecipes(recipes, new Set(), null, 2).map((x) => x.id)).toEqual(["aero-1", "v60-1"]);
    expect(startFromRecipes(recipes, families, null, 0)).toEqual([]);
  });

  it("the grind to offer skips 0 clicks", () => {
    expect(lastGrindFor([brew({ recipeId: "x", grindClicks: 0 }), brew({ recipeId: "x", grindClicks: 82 })], "x")).toBe(82);
    expect(lastGrindFor([brew({ recipeId: "x", grindClicks: null })], "x")).toBeNull();
  });
});

describe("Your coffee (bean on deck)", () => {
  const bean = (id: number, name: string, p: Partial<Parameters<typeof newBean>[0]> = {}) => ({ ...newBean({ name, ...p }, NOW - id * 1000), id });
  const a = bean(1, "A", { roastedOnEpochDay: epochDayOf(NOW) - 12 });
  const b = bean(2, "B");
  const gone = { ...bean(3, "Gone"), isArchived: true };

  it("default, else the newest brew's coffee, else the newest coffee; never archived", () => {
    expect(beanOnDeck([a, b], 2, [])?.name).toBe("B");
    expect(beanOnDeck([a, b], 0, [brew({ beanId: 1 })])?.name).toBe("A");
    expect(beanOnDeck([a, b, gone], 3, [brew({ beanId: 3 })])?.name).toBe("A");
    expect(beanOnDeck([gone], 3, [])).toBeNull();
  });

  it("days off roast from the roast day; null when not recorded", () => {
    expect(daysOffRoast(a, NOW)).toBe(12);
    expect(daysOffRoast(b, NOW)).toBeNull();
  });
});
