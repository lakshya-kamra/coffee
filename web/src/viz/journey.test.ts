import { describe, expect, it } from "vitest";
import { makeBrewer } from "@/domain/core/brewer";
import { waterAtF, type Recipe, type RecipeStep } from "@/domain/core/recipe";
import { journeyNodes, lastPourEndSec, nodeFraction, plannedTimelineRows, pourNodes, stepMood } from "./journey";

const step = (kind: RecipeStep["kind"], startSec: number, endSec: number, targetWaterG: number | null = null, title: string = kind, detail = ""): RecipeStep => ({ kind, title, detail, startSec, endSec, targetWaterG });

const RECIPE: Recipe = {
  id: "t",
  name: "Test",
  author: "Me",
  brewer: makeBrewer({ id: "hario-v60-02", label: "Hario V60" }),
  doseG: 15,
  waterG: 250,
  grind: "",
  tempC: 94,
  blurb: "",
  sourceNote: "",
  prep: [],
  tips: [],
  steps: [step("Bloom", 0, 45, 50, "Bloom", "Swirl to wet"), step("Pour", 45, 75, 150), step("Pour", 75, 105, 250), step("Swirl", 105, 110), step("Drawdown", 110, 180)],
  accent: 0xffe9a855,
  isBuiltIn: true,
  fidelity: "published",
  sources: [],
  basedOn: null, level: "", needs: [], prepNoScale: [],
};

describe("journey models (JourneyLine.kt helpers)", () => {
  it("pour nodes are pour starts in TIME, skipping the very start and end", () => {
    expect(pourNodes(RECIPE)).toEqual([45 / 180, 75 / 180]);
  });

  it("journey nodes label pours and the drawdown; the last pour's end is the top priority", () => {
    const n = journeyNodes(RECIPE);
    expect(n.map((x) => [x.label, x.kind, x.priority])).toEqual([
      ["0:45", "pour", 1],
      ["1:15", "pour", 1],
      ["1:45", "step", 2], // the swirl starts where the last pour ends
      ["1:50", "event", 0],
    ]);
    expect(lastPourEndSec(RECIPE)).toBe(105);
  });

  it("adds the last pour's end when no step starts there", () => {
    const r = { ...RECIPE, steps: [step("Bloom", 0, 40, 50), step("Pour", 40, 90, 250), step("Drawdown", 100, 200)] };
    const n = journeyNodes(r);
    expect(n.at(-1)).toMatchObject({ fraction: 90 / 200, label: "1:30", kind: "pour", priority: 2 });
  });

  it("planned timeline: every step, grams as trailing, then Done", () => {
    const rows = plannedTimelineRows(RECIPE);
    expect(rows[0]).toEqual({ time: "0:00", kind: "Bloom", title: "Bloom", detail: "Swirl to wet", trailing: "50 g" });
    expect(rows[3]).toMatchObject({ time: "1:45", trailing: null, detail: null });
    expect(rows.at(-1)).toEqual({ time: "3:00", kind: null, title: "Done", terminal: true });
  });

  it("moods follow the step kind (BrewScreen.kt)", () => {
    expect(["Prep", "Bloom", "Pour", "Swirl", "Steep", "Press", "Drawdown", "Serve"].map((k) => stepMood(k as RecipeStep["kind"]))).toEqual([
      "idle", "bloom", "pouring", "pouring", "steeping", "steeping", "drawdown", "done",
    ]);
  });

  it("node fraction from atSec needs the total", () => {
    expect(nodeFraction({ atSec: 90 }, 180)).toBe(0.5);
    expect(nodeFraction({ atSec: 90 }, undefined)).toBeNull();
    expect(nodeFraction({ fraction: 0.2 }, undefined)).toBe(0.2);
  });
});

describe("waterAtF (the ring's liquid)", () => {
  it("interpolates inside a pour and holds between pours", () => {
    expect(waterAtF(RECIPE, 0)).toBe(0);
    expect(waterAtF(RECIPE, 22_500)).toBeCloseTo(25, 6);
    expect(waterAtF(RECIPE, 45_000)).toBe(50);
    expect(waterAtF(RECIPE, 60_000)).toBeCloseTo(100, 6);
    expect(waterAtF(RECIPE, 105_000)).toBe(250);
    expect(waterAtF(RECIPE, 500_000)).toBe(250);
  });
});
