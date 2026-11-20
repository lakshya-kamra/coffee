/**
 * Espresso shot mode (v4 §4.9): a count-up shot timer with one button. Pure state machine plus
 * the recipe readings it shows (the target band and the pre-infusion window); the screen feeds it
 * performance.now() and draws the seconds from the frame loop.
 *
 *   ready --start--> pulling --stop--> stopped --again--> ready
 */
import type { Recipe } from "@/domain/core/recipe";
import { isPreinfusion } from "@/domain/core/shotTarget";

export type ShotPhase = "ready" | "pulling" | "stopped";

export interface ShotState {
  readonly phase: ShotPhase;
  /** performance.now() when the pump started. */
  readonly startedAt: number | null;
  /** Whole seconds, set by stop. */
  readonly shotSec: number | null;
  /** "How much came out?", as typed (grams). */
  readonly yieldText: string;
}

export type ShotAction =
  | { readonly type: "start"; readonly now: number }
  | { readonly type: "stop"; readonly now: number }
  /** Space: start when ready, stop when pulling, nothing once stopped. */
  | { readonly type: "toggle"; readonly now: number }
  | { readonly type: "again" }
  | { readonly type: "yield"; readonly text: string };

export function initialShot(targetYieldG: number): ShotState {
  return { phase: "ready", startedAt: null, shotSec: null, yieldText: targetYieldG > 0 ? String(Math.round(targetYieldG * 10) / 10) : "" };
}

export function shotReducer(s: ShotState, a: ShotAction): ShotState {
  switch (a.type) {
    case "start":
      return s.phase === "ready" ? { ...s, phase: "pulling", startedAt: a.now, shotSec: null } : s;
    case "stop":
      if (s.phase !== "pulling" || s.startedAt == null) return s;
      return { ...s, phase: "stopped", shotSec: Math.max(0, Math.floor((a.now - s.startedAt) / 1000)) };
    case "toggle":
      return s.phase === "ready" ? shotReducer(s, { type: "start", now: a.now }) : s.phase === "pulling" ? shotReducer(s, { type: "stop", now: a.now }) : s;
    case "again":
      return { ...s, phase: "ready", startedAt: null, shotSec: null };
    case "yield":
      return { ...s, yieldText: a.text.replace(/[^\d.,]/g, "").slice(0, 5) };
  }
}

/** Seconds on the hero: live while pulling, frozen once stopped. */
export function shotSeconds(s: ShotState, now: number): number {
  if (s.phase === "stopped") return s.shotSec ?? 0;
  if (s.phase === "pulling" && s.startedAt != null) return Math.max(0, Math.floor((now - s.startedAt) / 1000));
  return 0;
}

/** The typed yield in grams, or null when it is not a positive number. */
export function shotYield(s: ShotState): number | null {
  const t = s.yieldText.trim().replace(",", ".");
  if (!/^\d+(\.\d+)?$|^\d*\.\d+$/.test(t)) return null;
  const n = Number(t);
  return n > 0 ? n : null;
}

// The target lives in the domain: the review, Today and the first read judge against the same one.
export { bandStatus, shotBand, type BandStatus, type ShotBand } from "@/domain/core/shotTarget";

/** The pre-infusion window when the recipe has one (a step titled or described as pre-infusion). */
export function preinfusion(r: Pick<Recipe, "steps">): { readonly endSec: number } | null {
  const s = r.steps.find(isPreinfusion);
  return s && s.endSec > 0 ? { endSec: s.endSec } : null;
}
