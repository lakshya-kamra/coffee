// A§7.7 RecipeValidation rules, the one-tap last-pour fix, and draft ⇄ recipe timings.
import { describe, expect, it } from "vitest";
import { makeBrewer, type Brewer } from "@/domain/core/brewer";
import type { Recipe } from "@/domain/core/recipe";
import {
  addStep,
  blankDraft,
  draftFromRecipe,
  duplicateDraft,
  moveStep,
  newCustomId,
  nudgeDuration,
  parseAmount,
  pourDelta,
  recipeFromDraft,
  removeStep,
  setLastPour,
  totalDurationSec,
} from "./draft";
import { check, hasBlocking } from "./validation";

const v60 = makeBrewer({ id: "hario-v60-02", label: "V60 02", brand: "Hario", category: "cone", method: "pourover" });
const coldJar = makeBrewer({ id: "cold-brew-jar", label: "Cold brew jar", category: "cold", method: "coldBrew" });
const brewers: Record<string, Brewer> = { [v60.id]: v60, [coldJar.id]: coldJar };
const resolve = (id: string) => brewers[id] ?? makeBrewer({ id, label: id });

const hoffmann: Recipe = Object.freeze<Recipe>({
  id: "hoffmann-v60",
  name: "The Ultimate V60 Technique",
  author: "James Hoffmann",
  brewer: v60,
  doseG: 30,
  waterG: 500,
  grind: "Medium-fine",
  tempC: 100,
  blurb: "",
  sourceNote: "Numbers follow Hoffmann's published Ultimate V60 Technique.",
  prep: ["Rinse the paper."],
  tips: [],
  steps: [
    { kind: "Bloom", title: "Bloom to 60 g", detail: "", startSec: 0, endSec: 45, targetWaterG: 60 },
    { kind: "Pour", title: "Up to 300 g", detail: "", startSec: 45, endSec: 75, targetWaterG: 300 },
    { kind: "Pour", title: "Up to 500 g", detail: "", startSec: 75, endSec: 105, targetWaterG: 500 },
    { kind: "Stir", title: "Stir", detail: "", startSec: 105, endSec: 115, targetWaterG: null },
    { kind: "Swirl", title: "Swirl", detail: "", startSec: 115, endSec: 120, targetWaterG: null },
    { kind: "Drawdown", title: "Drawdown", detail: "", startSec: 120, endSec: 210, targetWaterG: null },
  ],
  accent: 0xffe9a855,
  isBuiltIn: true,
  fidelity: "published",
  sources: [{ who: "James Hoffmann", url: "https://www.youtube.com/watch?v=AI4ynXzkSQo", note: "" }],
  basedOn: null, level: "", needs: [], prepNoScale: [],
});

const codes = (r: Recipe) => check(r).map((i) => i.code);

describe("draft ⇄ recipe", () => {
  it("round-trips a recipe with contiguous steps exactly", () => {
    const back = recipeFromDraft(draftFromRecipe(hoffmann), resolve);
    expect(back.steps).toEqual(hoffmann.steps);
    expect(back.doseG).toBe(30);
    expect(back.waterG).toBe(500);
    expect(back.tempC).toBe(100);
    expect(back.isBuiltIn).toBe(false);
  });

  it("derives start/end cumulatively, so a gap or overlap in the source closes", () => {
    const gappy: Recipe = { ...hoffmann, steps: [hoffmann.steps[0]!, { ...hoffmann.steps[1]!, startSec: 50, endSec: 80 }] };
    const back = recipeFromDraft(draftFromRecipe(gappy), resolve);
    expect(back.steps.map((s) => [s.startSec, s.endSec])).toEqual([
      [0, 45],
      [45, 75],
    ]);
  });

  it("re-times every later step when a duration changes or steps move", () => {
    let d = draftFromRecipe(hoffmann);
    d = nudgeDuration(d, 0, 5); // bloom 45 → 50
    expect(recipeFromDraft(d, resolve).steps.map((s) => s.startSec)).toEqual([0, 50, 80, 110, 120, 125]);
    d = moveStep(d, 3, -1); // stir before the last pour
    const r = recipeFromDraft(d, resolve);
    expect(r.steps.map((s) => s.kind)).toEqual(["Bloom", "Pour", "Stir", "Pour", "Swirl", "Drawdown"]);
    expect(r.steps.map((s) => s.endSec)).toEqual([50, 80, 90, 120, 125, 215]);
    expect(totalDurationSec(d)).toBe(215);
  });

  it("never lets a duration drop below 1 s", () => {
    expect(nudgeDuration(draftFromRecipe(hoffmann), 4, -60).steps[4]!.durationSec).toBe(1);
  });

  it("shows the grams each pour adds", () => {
    const d = draftFromRecipe(hoffmann);
    expect(pourDelta(d, 0)).toBe(60);
    expect(pourDelta(d, 1)).toBe(240);
    expect(pourDelta(d, 2)).toBe(200);
    expect(pourDelta(d, 3)).toBeNull();
  });

  it("parses typed amounts leniently", () => {
    expect(parseAmount("18")).toBe(18);
    expect(parseAmount("18,5")).toBe(18.5);
    expect(parseAmount(" 1. ")).toBe(1);
    expect(parseAmount("")).toBeNull();
    expect(parseAmount("12g")).toBeNull();
  });

  it("adds and removes steps", () => {
    let d = addStep(draftFromRecipe(hoffmann));
    expect(d.steps).toHaveLength(7);
    expect(d.steps[6]).toMatchObject({ kind: "Pour", durationSec: 30, pourTo: "" });
    d = removeStep(d, 6);
    expect(d.steps).toHaveLength(6);
  });
});

describe("new and duplicate", () => {
  it("makes custom ids of 8 lowercase hex", () => {
    expect(newCustomId()).toMatch(/^custom-[0-9a-f]{8}$/);
    expect(newCustomId(() => 0.999)).toBe("custom-ffffffff");
  });

  it("duplicates with (mine), your name, custom fidelity, basedOn and copied sources", () => {
    const d = duplicateDraft(hoffmann, "Lakshya");
    expect(d.id).toMatch(/^custom-[0-9a-f]{8}$/);
    expect(d.name).toBe("The Ultimate V60 Technique (mine)");
    expect(d.author).toBe("Lakshya");
    expect(d.fidelity).toBe("custom");
    expect(d.basedOn).toBe("hoffmann-v60");
    expect(d.sources).toEqual(hoffmann.sources);
    expect(d.sourceNote).toBe("Based on James Hoffmann's The Ultimate V60 Technique.");
    expect(duplicateDraft(hoffmann, "  ").author).toBe("You");
  });

  it("starts a blank recipe with one Bloom step on a V60", () => {
    const d = blankDraft("");
    expect(d.brewerId).toBe("hario-v60-02");
    expect(d.steps.map((s) => s.kind)).toEqual(["Bloom"]);
    expect(d.author).toBe("You");
    // Blank numbers are reported, not guessed.
    expect(codes(recipeFromDraft(d, resolve))).toEqual(["name", "dose", "water"]);
  });
});

describe("RecipeValidation", () => {
  it("passes a published recipe", () => {
    expect(check(hoffmann)).toEqual([]);
  });

  it("checks the name: 1–60 characters", () => {
    expect(codes({ ...hoffmann, name: "  " })).toEqual(["name"]);
    expect(codes({ ...hoffmann, name: "x".repeat(61) })).toEqual(["name"]);
    expect(codes({ ...hoffmann, name: "x".repeat(60) })).toEqual([]);
  });

  it("rejects the reserved id", () => {
    expect(codes({ ...hoffmann, id: "new" })).toEqual(["reserved-id"]);
  });

  it("checks dose 1–100 g, water 10–2000 g, temp 0–100 °C", () => {
    expect(codes({ ...hoffmann, doseG: 0.5 })).toEqual(["dose"]);
    expect(codes({ ...hoffmann, doseG: 101 })).toEqual(["dose"]);
    expect(codes({ ...hoffmann, doseG: 100 })).toEqual([]);
    expect(codes({ ...hoffmann, waterG: 2001 })).toContain("water");
    expect(codes({ ...hoffmann, waterG: 9 })).toContain("water");
    expect(codes({ ...hoffmann, tempC: 101 })).toEqual(["temp"]);
    expect(codes({ ...hoffmann, tempC: 0 })).toEqual([]);
  });

  it("needs 1–30 steps, each at least 1 s", () => {
    expect(codes({ ...hoffmann, steps: [] })).toEqual(["step-count"]);
    const many = Array.from({ length: 31 }, (_, i) => ({ kind: "Stir" as const, title: "", detail: "", startSec: i, endSec: i + 1, targetWaterG: null }));
    expect(codes({ ...hoffmann, steps: [...many.slice(0, 30), { ...many[30]!, targetWaterG: 500 }] })).toEqual(["step-count"]);
    const zero = hoffmann.steps.map((s, i) => (i === 3 ? { ...s, endSec: s.startSec } : s));
    expect(check({ ...hoffmann, steps: zero })).toMatchObject([{ code: "step-duration", stepIndex: 3 }]);
  });

  it("caps the total at an hour, or 24 hours for cold brew", () => {
    const long = hoffmann.steps.map((s, i) => (i === 5 ? { ...s, endSec: 3601 } : s));
    expect(codes({ ...hoffmann, steps: long })).toEqual(["total-time"]);
    expect(codes({ ...hoffmann, brewer: coldJar, steps: long })).toEqual([]);
    const day = hoffmann.steps.map((s, i) => (i === 5 ? { ...s, endSec: 86_401 } : s));
    expect(codes({ ...hoffmann, brewer: coldJar, steps: day })).toEqual(["total-time"]);
  });

  it("needs pour targets that never go down", () => {
    const down = hoffmann.steps.map((s, i) => (i === 1 ? { ...s, targetWaterG: 50 } : s));
    expect(check({ ...hoffmann, steps: down })).toMatchObject([{ code: "pour-order", stepIndex: 1 }]);
  });

  it("needs the last pour to reach the recipe's water, with a one-tap fix", () => {
    const short = hoffmann.steps.map((s, i) => (i === 2 ? { ...s, targetWaterG: 480 } : s));
    const r = { ...hoffmann, steps: short };
    const issues = check(r);
    expect(issues).toMatchObject([{ code: "last-pour", stepIndex: 2, fix: { label: "Set the last pour to 500 g", waterG: 500 } }]);
    expect(hasBlocking(issues)).toBe(true);
    const fixed = setLastPour(draftFromRecipe(r), issues[0]!.fix!.waterG);
    expect(check(recipeFromDraft(fixed, resolve))).toEqual([]);
    // Changing the water instead also clears it.
    expect(codes({ ...r, waterG: 480 })).toEqual([]);
  });
});
