/**
 * The one espresso shot target (v4 §4.9, A4): shot mode's hint, the review's time cell, Today's
 * SHOT TIME, the brew detail and the first read all compare against this, never the recipe's
 * total (which includes "Serve" and so read 0:45 where shot mode said "about 35 s").
 *
 * The recipe's own shot time when it has one: a band its words state ("25 to 30 seconds"), else
 * the end of its last step that is not "Serve". A recipe with neither gets the shot-mode default.
 */
import type { Recipe, RecipeStep } from "./recipe";

export interface ShotBand {
  readonly from: number;
  readonly to: number;
  /** "25–30 s", or "about 35 s" when the recipe only gives an end time. */
  readonly label: string;
}

/** Shot mode's default when a recipe gives no time at all. */
export const DEFAULT_SHOT_BAND: ShotBand = { from: 25, to: 30, label: "25–30 s" };

const BAND = /(\d{1,3})\s*(?:-|–|to)\s*(\d{1,3})\s*(?:s\b|sec|seconds)/i;

export function isPreinfusion(s: Pick<RecipeStep, "title" | "detail" | "startSec">): boolean {
  return /pre-?infusion/i.test(s.title) || (s.startSec === 0 && /pre-?infus/i.test(s.detail));
}

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
  return end > 0 ? { from: end, to: end, label: `about ${end} s` } : DEFAULT_SHOT_BAND;
}

export type BandStatus = "under" | "in" | "over";

/** Where a shot time sits against the band; a single-number band allows 2 s either side. */
export function bandStatus(sec: number, band: ShotBand): BandStatus {
  const slack = band.from === band.to ? 2 : 0;
  if (sec < band.from - slack) return "under";
  if (sec > band.to + slack) return "over";
  return "in";
}

/** The words shot mode shows for a stopped shot, reused wherever a shot time is judged. */
export const BAND_WORDS: Record<BandStatus, { readonly status: "attention" | "good"; readonly lead: string }> = {
  under: { status: "attention", lead: "Fast" },
  in: { status: "good", lead: "In the band" },
  over: { status: "attention", lead: "Slow" },
};

/**
 * One number for the places that need a single target (a signed delta, a "plan" sub line): the
 * band's middle, rounded, for espresso; the recipe's total for everything else.
 */
export function targetSec(r: Pick<Recipe, "steps" | "blurb" | "brewer">, total: number): number {
  if (r.brewer.method !== "espresso") return total;
  const b = shotBand(r);
  return Math.round((b.from + b.to) / 2);
}
