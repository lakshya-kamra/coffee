/**
 * Espresso shot mode (v4 §4.9): a count-up shot timer with one button. Pure state machine plus
 * the recipe readings it shows (the target band and the pre-infusion window); the screen feeds it
 * performance.now() and draws the seconds from the frame loop.
 *
 *   ready --start--> pulling --stop--> stopped --again--> ready
 */
import type { Recipe, RecipeStep } from "@/domain/core/recipe";

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

export interface ShotBand {
  readonly from: number;
  readonly to: number;
  /** "25–30 s", or "about 35 s" when the recipe only gives an end time. */
  readonly label: string;
}

const BAND = /(\d{1,3})\s*(?:-|–|to)\s*(\d{1,3})\s*(?:s\b|sec|seconds)/i;

/**
 * The target band: the "25 to 30 seconds" a recipe states in its steps (or blurb), else the end
 * of its last step that is not "Serve" (stopping and tasting is not part of the shot).
 */
export function shotBand(r: Pick<Recipe, "steps" | "blurb">): ShotBand {
  // A pre-infusion step's "first drops at 8 to 12 seconds" is not the shot's target.
  const pull = r.steps.filter((s) => !isPreinfusion(s));
  for (const text of [...pull.map((s) => s.detail), ...pull.map((s) => s.title), r.blurb]) {
    const m = BAND.exec(text);
    if (!m) continue;
    const a = Number(m[1]);
    const b = Number(m[2]);
    if (a > 0 && b >= a && b <= 120) return { from: a, to: b, label: `${a}–${b} s` };
  }
  const pulling = r.steps.filter((s) => s.kind !== "Serve");
  const end = Math.max(0, ...(pulling.length ? pulling : r.steps).map((s) => s.endSec));
  return { from: end, to: end, label: `about ${end} s` };
}

export type BandStatus = "under" | "in" | "over";

/** Where a shot time sits against the band; a single-number band allows 2 s either side. */
export function bandStatus(sec: number, band: ShotBand): BandStatus {
  const slack = band.from === band.to ? 2 : 0;
  if (sec < band.from - slack) return "under";
  if (sec > band.to + slack) return "over";
  return "in";
}

/** The pre-infusion window when the recipe has one (a step titled or described as pre-infusion). */
export function preinfusion(r: Pick<Recipe, "steps">): { readonly endSec: number } | null {
  const s = r.steps.find(isPreinfusion);
  return s && s.endSec > 0 ? { endSec: s.endSec } : null;
}

function isPreinfusion(s: Pick<RecipeStep, "title" | "detail" | "startSec">): boolean {
  return /pre-?infusion/i.test(s.title) || (s.startSec === 0 && /pre-?infus/i.test(s.detail));
}
