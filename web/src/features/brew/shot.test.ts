import { describe, expect, it } from "vitest";
import { makeBrewer } from "@/domain/core/brewer";
import type { Recipe, RecipeStep } from "@/domain/core/recipe";
import { bandStatus, initialShot, preinfusion, shotBand, shotReducer, shotSeconds, shotYield, type ShotAction, type ShotState } from "./shot";

const ESPRESSO = makeBrewer({ id: "espresso-machine", label: "Espresso machine", method: "espresso" });

function recipe(steps: RecipeStep[], blurb = ""): Pick<Recipe, "steps" | "blurb" | "brewer"> {
  return { steps, blurb, brewer: ESPRESSO };
}

const classic = recipe([{ kind: "Pour", title: "Pull to 36 g", detail: "Stop at 36 g in the cup, 25 to 30 seconds in.", startSec: 0, endSec: 30, targetWaterG: 36 }]);
const hedrick = recipe([
  { kind: "Steep", title: "Pre-infusion", detail: "Low pressure. First drops around 8 to 12 seconds.", startSec: 0, endSec: 12, targetWaterG: 2 },
  { kind: "Pour", title: "Ramp and pull", detail: "Full pressure. Chase 45 g.", startSec: 12, endSec: 35, targetWaterG: 45 },
  { kind: "Serve", title: "Stop and taste", detail: "", startSec: 35, endSec: 45, targetWaterG: null },
]);

const run = (s: ShotState, ...actions: ShotAction[]) => actions.reduce(shotReducer, s);

describe("shot mode state machine (§4.9)", () => {
  it("ready → pulling → stopped, with the seconds frozen at stop", () => {
    const s0 = initialShot(36);
    expect(s0).toMatchObject({ phase: "ready", yieldText: "36" });
    const pulling = run(s0, { type: "start", now: 1000 });
    expect(pulling.phase).toBe("pulling");
    expect(shotSeconds(pulling, 1000 + 12_400)).toBe(12);
    const stopped = run(pulling, { type: "stop", now: 1000 + 27_900 });
    expect(stopped).toMatchObject({ phase: "stopped", shotSec: 27 });
    expect(shotSeconds(stopped, 999_999)).toBe(27);
    expect(shotSeconds(s0, 5000)).toBe(0);
  });

  it("Space toggles start and stop, and does nothing once stopped", () => {
    const s = run(initialShot(36), { type: "toggle", now: 0 }, { type: "toggle", now: 25_000 });
    expect(s).toMatchObject({ phase: "stopped", shotSec: 25 });
    expect(run(s, { type: "toggle", now: 40_000 })).toBe(s);
  });

  it("ignores out-of-order actions and pulls again from ready", () => {
    const s0 = initialShot(36);
    expect(run(s0, { type: "stop", now: 10 })).toBe(s0);
    const twice = run(s0, { type: "start", now: 0 }, { type: "start", now: 5000 });
    expect(twice.startedAt).toBe(0);
    const again = run(twice, { type: "stop", now: 9000 }, { type: "again" });
    expect(again).toMatchObject({ phase: "ready", startedAt: null, shotSec: null, yieldText: "36" });
  });

  it("reads the yield field as grams", () => {
    const s = initialShot(36);
    expect(shotYield(s)).toBe(36);
    expect(shotYield(run(s, { type: "yield", text: "38,5" }))).toBe(38.5);
    expect(shotYield(run(s, { type: "yield", text: "" }))).toBeNull();
    expect(run(s, { type: "yield", text: "4a0g" }).yieldText).toBe("40");
    expect(initialShot(0).yieldText).toBe("");
  });
});

describe("shot band and pre-infusion", () => {
  it("uses the band the recipe states", () => {
    expect(shotBand(classic)).toMatchObject({ from: 25, to: 30, label: "25–30 s" });
  });

  it("never takes the pre-infusion's first drops as the band; falls back to the end of the pull", () => {
    expect(shotBand(hedrick)).toMatchObject({ from: 35, to: 35, label: "about 35 s" });
    expect(preinfusion(hedrick)).toEqual({ endSec: 12 });
    expect(preinfusion(classic)).toBeNull();
  });

  it("status against the band", () => {
    const band = shotBand(classic);
    expect(bandStatus(22, band)).toBe("under");
    expect(bandStatus(25, band)).toBe("in");
    expect(bandStatus(31, band)).toBe("over");
    const single = shotBand(hedrick);
    expect(bandStatus(33, single)).toBe("in");
    expect(bandStatus(38, single)).toBe("over");
  });
});
