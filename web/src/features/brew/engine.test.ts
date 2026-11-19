import { describe, expect, it } from "vitest";
import { makeBrewer } from "@/domain/core/brewer";
import type { Recipe, RecipeStep } from "@/domain/core/recipe";
import { BrewEngine, stepIndexAt, type Clock } from "./engine";
import { cueOffsetsSec, CUE_GAIN } from "./cues";

const step = (kind: RecipeStep["kind"], startSec: number, endSec: number, targetWaterG: number | null = null): RecipeStep => ({ kind, title: kind, detail: "", startSec, endSec, targetWaterG });

/** Bloom 0–45, pours to 45–75 and 75–105, swirl, drawdown to 3:00. */
const RECIPE: Recipe = {
  id: "t",
  name: "Test",
  author: "Me",
  brewer: makeBrewer({ id: "hario-v60-02", label: "Hario V60" }),
  doseG: 15,
  waterG: 250,
  grind: "Medium-fine",
  tempC: 94,
  blurb: "",
  sourceNote: "",
  prep: [],
  tips: [],
  steps: [step("Bloom", 0, 45, 50), step("Pour", 45, 75, 150), step("Pour", 75, 105, 250), step("Swirl", 105, 110), step("Drawdown", 110, 180)],
  accent: 0xffe9a855,
  isBuiltIn: true,
  fidelity: "published",
  sources: [],
  basedOn: null,
};

/** perf and wall advance together unless told otherwise (a sleeping device stops perf). */
function fakeClock() {
  let perf = 1000;
  let wall = 1_700_000_000_000;
  const clock: Clock = { perf: () => perf, wall: () => wall };
  return {
    clock,
    advance(ms: number) {
      perf += ms;
      wall += ms;
    },
    sleep(ms: number) {
      wall += ms;
    },
  };
}

/** Runs the clock forward a second at a time, ticking like the frame loop does. */
function run(e: BrewEngine, c: ReturnType<typeof fakeClock>, ms: number) {
  for (let left = ms; left > 0; left -= 1000) {
    c.advance(Math.min(1000, left));
    e.tick();
  }
}

describe("BrewEngine", () => {
  it("start, pause, resume derive elapsed from anchors", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    expect(e.state.started).toBe(false);
    e.start();
    expect(e.state).toMatchObject({ started: true, running: true, stepIndex: 0 });
    c.advance(10_000);
    expect(e.elapsedMs()).toBe(10_000);
    e.pause();
    c.advance(30_000);
    expect(e.elapsedMs()).toBe(10_000);
    expect(e.state).toMatchObject({ running: false, accumulatedMs: 10_000 });
    e.resume();
    c.advance(5_000);
    expect(e.elapsedMs()).toBe(15_000);
    e.toggle();
    expect(e.state.running).toBe(false);
  });

  it("listeners hear discrete changes only, never the clock", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    let calls = 0;
    e.subscribe(() => calls++);
    e.start();
    const afterStart = calls;
    for (let i = 0; i < 30; i++) {
      c.advance(1000);
      e.tick();
    }
    expect(calls).toBe(afterStart); // 0:30 is still the bloom
    c.advance(16_000);
    e.tick();
    expect(e.state.stepIndex).toBe(1);
    expect(calls).toBe(afterStart + 1);
  });

  it("crossing a boundary fires the cue once and records the pour end", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    const cues: number[] = [];
    e.onStep((i) => cues.push(i));
    e.start();
    run(e, c, 46_000);
    e.tick();
    expect(cues).toEqual([1]);
    expect(e.state.lastPourEndSec).toBe(45);
    run(e, c, 60_000); // 1:46, in the swirl
    expect(e.state.stepIndex).toBe(3);
    expect(e.state.lastPourEndSec).toBe(105);
    expect(cues).toEqual([1, 2, 3]);
  });

  it("±5 s seeks from the true elapsed time and bumps the seek serial", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(12_345);
    e.addSeconds(5);
    expect(e.elapsedMs()).toBe(17_345);
    expect(e.state.seekSerial).toBe(1);
    c.advance(1000);
    e.addSeconds(-5);
    expect(e.elapsedMs()).toBe(13_345);
    e.addSeconds(-60);
    expect(e.elapsedMs()).toBe(0);
  });

  it("skip jumps to the next step's start", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(3000);
    e.skipToNextStep();
    expect(e.elapsedMs()).toBe(45_000);
    expect(e.state.stepIndex).toBe(1);
    e.skipToNextStep();
    e.skipToNextStep();
    e.skipToNextStep();
    expect(e.state.stepIndex).toBe(4);
    e.skipToNextStep(); // nothing after the drawdown
    expect(e.state.stepIndex).toBe(4);
  });

  it("runs past the plan: done, with the overrun on the clock", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(179_000);
    e.tick();
    expect(e.state.done).toBe(false);
    c.advance(13_000);
    e.tick();
    expect(e.state.done).toBe(true);
    expect(Math.floor(e.elapsedMs() / 1000) - 180).toBe(12);
  });

  it("bed exposed gives the drawdown against the last pour's end", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    run(e, c, 106_000); // leaves the last pour: lastPourEnd = 105
    run(e, c, 42_000); // 2:28
    e.markBedExposed();
    e.markBedExposed(); // only the first tap counts
    expect(e.state.bedExposedSec).toBe(148);
    c.advance(10_000);
    expect(e.finish()).toEqual({ totalTimeSec: 158, drawdownSec: 43 });
    expect(e.state.running).toBe(false);
  });

  it("no drawdown without a bed tap", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(200_000);
    e.tick();
    expect(e.finish()).toEqual({ totalTimeSec: 200, drawdownSec: null });
  });

  it("re-anchors to the wall clock after the device slept", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(10_000);
    c.sleep(60_000); // perf stood still for a minute
    expect(e.elapsedMs()).toBe(10_000);
    expect(e.reanchorToWall()).toBe(true);
    expect(e.elapsedMs()).toBe(70_000);
    expect(e.state.stepIndex).toBe(1);
    c.sleep(500); // under a second of drift is left alone
    expect(e.reanchorToWall()).toBe(false);
  });

  it("auto-advance off: the step card waits for a tap while the clock runs on", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock, autoAdvance: false });
    const cues: number[] = [];
    e.onStep((i) => cues.push(i));
    e.start();
    c.advance(50_000);
    e.tick();
    expect(e.state.stepIndex).toBe(0);
    expect(e.state.clockStepIndex).toBe(1);
    expect(cues).toEqual([]);
    e.advanceStep();
    expect(e.state.stepIndex).toBe(1);
    expect(e.state.lastPourEndSec).toBe(45);
    expect(cues).toEqual([1]);
    expect(e.elapsedMs()).toBe(50_000);
  });

  it("reset returns to setup", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(100_000);
    e.tick();
    e.markBedExposed();
    e.reset();
    expect(e.state).toMatchObject({ started: false, running: false, stepIndex: 0, done: false, bedExposedSec: null, lastPourEndSec: null });
    expect(e.elapsedMs()).toBe(0);
  });

  it("a snapshot survives a reload from the wall clock", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(30_000);
    e.pause();
    c.advance(5_000);
    e.resume();
    c.advance(20_000);
    const snap = e.snapshot();
    // The page reloads 15 s later: a new engine, a new perf origin.
    c.advance(15_000);
    const c2 = { perf: () => 5, wall: c.clock.wall };
    const restored = new BrewEngine(RECIPE, { clock: c2 });
    restored.restore(snap);
    expect(restored.elapsedMs()).toBe(65_000);
    expect(restored.state).toMatchObject({ started: true, running: true, stepIndex: 1 });
    // A paused brew resumes paused, at the same second.
    e.pause();
    const paused = new BrewEngine(RECIPE, { clock: c2 });
    paused.restore(e.snapshot());
    expect(paused.elapsedMs()).toBe(e.elapsedMs());
    expect(paused.state.running).toBe(false);
  });

  it("msToNextBoundary drives the hidden-tab timer", () => {
    const c = fakeClock();
    const e = new BrewEngine(RECIPE, { clock: c.clock });
    e.start();
    c.advance(40_000);
    expect(e.msToNextBoundary()).toBe(5_000);
    c.advance(150_000);
    expect(e.msToNextBoundary()).toBeNull();
  });

  it("stepIndexAt picks the last started step", () => {
    expect(stepIndexAt(RECIPE, 0)).toBe(0);
    expect(stepIndexAt(RECIPE, 44)).toBe(0);
    expect(stepIndexAt(RECIPE, 45)).toBe(1);
    expect(stepIndexAt(RECIPE, 500)).toBe(4);
  });
});

describe("cues", () => {
  it("schedules a cue at every step start still ahead", () => {
    expect(cueOffsetsSec(RECIPE, 0)).toEqual([45, 75, 105, 110]);
    expect(cueOffsetsSec(RECIPE, 70_500)).toEqual([4.5, 34.5, 39.5]);
    expect(cueOffsetsSec(RECIPE, 120_000)).toEqual([]);
  });

  it("plays at −18 dBFS", () => {
    expect(20 * Math.log10(CUE_GAIN)).toBeCloseTo(-18, 6);
  });
});
