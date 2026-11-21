import { describe, expect, it } from "vitest";
import { makeBrewer } from "@/domain/core/brewer";
import type { Recipe, RecipeStep } from "@/domain/core/recipe";
import { BrewEngine, type Clock } from "./engine";
import { GLIDE_MS, glideEase, glideSeek, SeekGlide } from "./glide";

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
  level: "",
  needs: [],
  prepNoScale: [],
};

/** One fake performance.now() shared by the engine and the glide, as in the page. */
function rig() {
  let perf = 1000;
  const clock: Clock = { perf: () => perf, wall: () => 1_700_000_000_000 + perf };
  const engine = new BrewEngine(RECIPE, { clock });
  const glide = new SeekGlide(() => perf);
  return {
    engine,
    glide,
    advance(ms: number) {
      perf += ms;
      engine.tick();
    },
    /** What the ring and journey line draw: true elapsed plus the glide's offset. */
    drawn: () => engine.elapsedMs() + glide.get(),
    seek: (s: number, reduced = false) => glideSeek(engine, glide, () => engine.addSeconds(s), reduced),
  };
}

/** Samples the drawn time every `every` ms for `ms`, the way a 120 Hz frame loop would. */
function sample(r: ReturnType<typeof rig>, ms: number, every = 8): number[] {
  const out = [r.drawn()];
  for (let t = 0; t < ms; t += every) {
    r.advance(every);
    out.push(r.drawn());
  }
  return out;
}

const nonDecreasing = (xs: number[]) => xs.every((x, i) => i === 0 || x >= xs[i - 1]! - 1e-9);
const nonIncreasing = (xs: number[]) => xs.every((x, i) => i === 0 || x <= xs[i - 1]! + 1e-9);

describe("glideEase", () => {
  it("runs 0 to 1 without ever turning back or overshooting", () => {
    expect(glideEase(0)).toBeCloseTo(0, 6);
    expect(glideEase(1)).toBeCloseTo(1, 6);
    const ys = Array.from({ length: 1001 }, (_, i) => glideEase(i / 1000));
    expect(nonDecreasing(ys)).toBe(true);
    expect(Math.max(...ys)).toBeLessThanOrEqual(1 + 1e-9);
    // Decelerate: most of the way is covered early, so the tap answers at once.
    expect(glideEase(0.25)).toBeGreaterThan(0.6);
  });
});

describe("SeekGlide", () => {
  it("eases an offset to zero over GLIDE_MS and then rests at exactly zero", () => {
    let now = 0;
    const g = new SeekGlide(() => now);
    expect(g.get()).toBe(0);
    g.retarget(-5000);
    expect(g.get()).toBe(-5000);
    const xs: number[] = [];
    for (now = 0; now <= GLIDE_MS + 50; now += 4) xs.push(g.get());
    expect(nonDecreasing(xs)).toBe(true);
    expect(xs.at(-1)).toBe(0);
    expect(g.active()).toBe(false);
    now = 150;
    g.retarget(-5000);
    now = 150 + GLIDE_MS;
    expect(g.get()).toBe(0);
  });

  it("reduced motion snaps: no offset at all", () => {
    const g = new SeekGlide(() => 0);
    g.retarget(-5000, true);
    expect(g.get()).toBe(0);
  });

  it("ignores sub-millisecond and non-finite offsets", () => {
    const g = new SeekGlide(() => 0);
    g.retarget(0.2);
    expect(g.active()).toBe(false);
    g.retarget(Number.NaN);
    expect(g.get()).toBe(0);
  });
});

describe("glideSeek with the engine", () => {
  it("+5 s while running: the clock jumps exactly, the drawing only ever moves forward", () => {
    const r = rig();
    r.engine.start();
    r.advance(10_000);
    const before = r.drawn();
    r.seek(5);
    // The truth jumps at once; nothing of the glide leaks into it.
    expect(r.engine.elapsedMs()).toBe(15_000);
    // The drawing starts where it was, not behind it.
    expect(r.drawn()).toBeCloseTo(before, 6);
    const xs = sample(r, GLIDE_MS + 100);
    expect(nonDecreasing(xs)).toBe(true);
    expect(r.drawn()).toBe(r.engine.elapsedMs());
    expect(r.engine.elapsedMs()).toBe(15_000 + 400);
  });

  it("+5 s while paused glides forward and lands on the new time", () => {
    const r = rig();
    r.engine.start();
    r.advance(20_000);
    r.engine.pause();
    r.seek(5);
    expect(r.engine.elapsedMs()).toBe(25_000);
    const xs = sample(r, GLIDE_MS + 50);
    expect(xs[0]).toBe(20_000);
    expect(nonDecreasing(xs)).toBe(true);
    expect(xs.at(-1)).toBe(25_000);
  });

  it("-5 s glides back without ever stepping forward first, then runs on", () => {
    const r = rig();
    r.engine.start();
    r.advance(30_000);
    r.seek(-5);
    expect(r.engine.elapsedMs()).toBe(25_000);
    // The first half of the glide is the way back; the tail meets the running clock.
    const xs = sample(r, GLIDE_MS / 2, 4);
    expect(xs[0]).toBeCloseTo(30_000, 6);
    expect(nonIncreasing(xs)).toBe(true);
    sample(r, GLIDE_MS);
    expect(r.drawn()).toBe(r.engine.elapsedMs());
  });

  it("-5 s paused glides straight back", () => {
    const r = rig();
    r.engine.start();
    r.advance(12_000);
    r.engine.pause();
    r.seek(-5);
    const xs = sample(r, GLIDE_MS + 50);
    expect(nonIncreasing(xs)).toBe(true);
    expect(xs.at(-1)).toBe(7_000);
  });

  it("-5 s near the start clamps the clock at 0:00 and glides there", () => {
    const r = rig();
    r.engine.start();
    r.advance(3_000);
    r.engine.pause();
    r.seek(-5);
    expect(r.engine.elapsedMs()).toBe(0);
    const xs = sample(r, GLIDE_MS + 50);
    expect(nonIncreasing(xs)).toBe(true);
    expect(xs.at(-1)).toBe(0);
  });

  it("rapid taps retarget from the drawn position: no jump, no step back, exact truth", () => {
    const r = rig();
    r.engine.start();
    r.advance(5_000);
    const xs: number[] = [r.drawn()];
    for (let tap = 0; tap < 6; tap++) {
      const at = r.drawn();
      r.seek(5);
      // Continuous across the retarget.
      expect(r.drawn()).toBeCloseTo(at, 6);
      xs.push(...sample(r, 60, 4));
    }
    xs.push(...sample(r, GLIDE_MS + 50, 4));
    expect(nonDecreasing(xs)).toBe(true);
    // Six taps and the running time between them: the glide added nothing to the clock.
    expect(r.engine.elapsedMs()).toBe(5_000 + 6 * 5_000 + 6 * 60 + 352);
    expect(r.drawn()).toBe(r.engine.elapsedMs());
  });

  it("crossing a step boundary changes the step at once, on the true time", () => {
    const r = rig();
    const heard: number[] = [];
    r.engine.onStep((i) => heard.push(i));
    r.engine.start();
    r.advance(42_000);
    expect(r.engine.state.stepIndex).toBe(0);
    r.seek(5);
    expect(r.engine.state.stepIndex).toBe(1);
    expect(heard).toEqual([1]);
    const xs = sample(r, GLIDE_MS + 50);
    expect(nonDecreasing(xs)).toBe(true);
    // The glide passing the boundary again fires nothing more.
    expect(heard).toEqual([1]);
  });

  it("Next step glides too", () => {
    const r = rig();
    r.engine.start();
    r.advance(10_000);
    glideSeek(r.engine, r.glide, () => r.engine.skipToNextStep(), false);
    expect(r.engine.elapsedMs()).toBe(45_000);
    expect(r.drawn()).toBeCloseTo(10_000, 6);
    const xs = sample(r, GLIDE_MS + 50);
    expect(nonDecreasing(xs)).toBe(true);
    expect(r.drawn()).toBe(r.engine.elapsedMs());
  });

  it("at the end the clock reads past the plan and the drawing only rises to it (the ring clamps at 1)", () => {
    const r = rig();
    r.engine.start();
    r.advance(178_000);
    r.engine.pause();
    r.seek(5);
    expect(r.engine.elapsedMs()).toBe(183_000);
    expect(r.engine.state.done).toBe(true);
    const frac = sample(r, GLIDE_MS + 50).map((ms) => Math.min(1, ms / 180_000));
    expect(nonDecreasing(frac)).toBe(true);
    expect(frac.at(-1)).toBe(1);
  });

  it("reduced motion: the drawing snaps with the clock", () => {
    const r = rig();
    r.engine.start();
    r.advance(10_000);
    r.seek(5, true);
    expect(r.drawn()).toBe(15_000);
  });
});
