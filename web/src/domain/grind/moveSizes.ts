/**
 * A§3.3 move sizes. These are the APP'S OWN RULE OF THUMB, not anyone's published advice, and
 * any copy that shows them must say so. All values rounded to the grinder's step, ≥ one step.
 */
import type { BrewMethod } from "@/domain/core/brewer";
import type { GrindScale } from "./grindScale";

export type MoveBasis = "microns" | "range" | "legacy-turn" | "generic";

export interface MoveSizes {
  readonly nudge: number;
  readonly clearMiss: number;
  readonly maxAtOnce: number;
  readonly jumpLimit: number;
  readonly basis: MoveBasis;
}

function toStep(v: number, step: number): number {
  const r = Math.round(v / step) * step;
  // Keep 0.1-step dials free of float dust (0.30000000000000004).
  return Math.max(step, Math.round(r * 1e6) / 1e6);
}

/** Same order and rounding as Kotlin `GrindScale.moves`. */
export function moveSizes(s: GrindScale, method: BrewMethod | null | undefined): MoveSizes {
  const step = s.step > 0 ? s.step : 1;
  const r = (v: number) => toStep(v, step);
  const um = s.micronsPerStep;
  if (um != null && um > 0) {
    // About 40 µm is a nudge, 120 µm a clear miss, 250 µm the most in one go (K-Ultra: 2/6/13/25 clicks).
    const nudge = r(40 / um);
    const clearMiss = r(Math.max(2 * nudge, 120 / um));
    return { nudge, clearMiss, maxAtOnce: r(Math.max(clearMiss, 250 / um)), jumpLimit: r(500 / um), basis: "microns" };
  }
  const range = method ? s.ranges[method] : undefined;
  if (range && range.to > range.from) {
    const w = range.to - range.from;
    return { nudge: r(0.05 * w), clearMiss: r(0.15 * w), maxAtOnce: r(0.3 * w), jumpLimit: r(0.5 * w), basis: "range" };
  }
  const cpr = s.clicksPerRotation;
  if (s.style === "clicks" && cpr != null && cpr >= 1 && cpr <= 40) {
    // Identical to 1.x: a nudge is 2 clicks, a clear miss one full turn, never more than two turns.
    return { nudge: 2, clearMiss: cpr, maxAtOnce: 2 * cpr, jumpLimit: 2 * cpr, basis: "legacy-turn" };
  }
  return { nudge: r(2 * step), clearMiss: r(5 * step), maxAtOnce: r(10 * step), jumpLimit: r(20 * step), basis: "generic" };
}
