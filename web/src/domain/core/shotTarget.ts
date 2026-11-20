/**
 * The one espresso shot target (v4 §4.9, A4): shot mode's hint, the review's time cell, Today's
 * SHOT TIME, the brew detail, Log, Compare and the first read all compare against this, never the
 * recipe's total (which includes "Serve" and so read 0:45 where shot mode said "about 35 s").
 *
 * One rule on both platforms (Kotlin twin: domain/ShotTarget.kt; parity cases "shotBand" in
 * shared/fixtures/domain-v4-cases.json):
 * 1. A band the recipe's words state ("25 to 30 seconds", "25-30 s"), read from the details of
 *    its steps, then their titles, then its tips, then its blurb, skipping pre-infusion steps.
 *    Every range in a text is tried in order; the first with 0 < low < high and 15 <= high <= 120
 *    wins (a range ending under 15 s is first drops, not the shot).
 * 2. Else "about N s", N the latest end of a step that is not "Serve" (of any step when every
 *    step is "Serve").
 * 3. Else, with no steps or no time at all, the shot-mode default of 25 to 30 seconds.
 */
import type { Recipe, RecipeStep } from "./recipe";
import { timeStatus, type Status } from "./status";

export interface ShotBand {
  readonly from: number;
  readonly to: number;
  /** "25–30 s", or "about 35 s" when the recipe only gives an end time. */
  readonly label: string;
}

/** Shot mode's default when a recipe gives no time at all. */
export const DEFAULT_SHOT_BAND: ShotBand = { from: 25, to: 30, label: "25–30 s" };

const BAND = /(\d{1,3})\s*(?:-|–|to)\s*(\d{1,3})\s*(?:s\b|sec|seconds)/gi;

/** A pre-infusion step: "infus" in its title, or a first step whose detail says pre-infusion. */
export function isPreinfusion(s: Pick<RecipeStep, "title" | "detail" | "startSec">): boolean {
  return /infus/i.test(s.title) || (s.startSec === 0 && /pre-?infus/i.test(s.detail));
}

function statedBand(text: string): ShotBand | null {
  for (const m of text.matchAll(BAND)) {
    const a = Number(m[1]);
    const b = Number(m[2]);
    if (a > 0 && a < b && b >= 15 && b <= 120) return { from: a, to: b, label: `${a}–${b} s` };
  }
  return null;
}

export function shotBand(r: Pick<Recipe, "steps" | "blurb"> & { readonly tips?: readonly string[] }): ShotBand {
  // A pre-infusion step's "first drops at 8 to 12 seconds" is not the shot's target.
  const pull = r.steps.filter((s) => !isPreinfusion(s));
  for (const text of [...pull.map((s) => s.detail), ...pull.map((s) => s.title), ...(r.tips ?? []), r.blurb]) {
    const band = statedBand(text);
    if (band) return band;
  }
  const pulling = r.steps.filter((s) => s.kind !== "Serve");
  const end = Math.max(0, ...(pulling.length ? pulling : r.steps).map((s) => s.endSec));
  return end > 0 ? { from: end, to: end, label: `about ${end} s` } : DEFAULT_SHOT_BAND;
}

export type BandStatus = "under" | "in" | "over";

/** "about 35 s" is one number, so it allows 2 s either side; a stated band is taken as written. */
function slack(band: ShotBand): number {
  return band.from === band.to ? 2 : 0;
}

/** Where a shot time sits against the band. */
export function bandStatus(sec: number, band: ShotBand): BandStatus {
  if (sec < band.from - slack(band)) return "under";
  if (sec > band.to + slack(band)) return "over";
  return "in";
}

/**
 * Seconds outside the band, from its nearer edge: 0 inside it, negative when the shot ran fast,
 * positive when slow. Kotlin twin: ShotTarget.offBy.
 */
export function shotOffBy(sec: number, band: ShotBand): number {
  if (sec < band.from - slack(band)) return sec - band.from;
  if (sec > band.to + slack(band)) return sec - band.to;
  return 0;
}

/** The words shot mode shows for a stopped shot, reused wherever a shot time is judged. */
export const BAND_WORDS: Record<BandStatus, { readonly status: "attention" | "good"; readonly lead: string }> = {
  under: { status: "attention", lead: "Fast" },
  in: { status: "good", lead: "In the band" },
  over: { status: "attention", lead: "Slow" },
};

/**
 * One number for the places that need a single target (a "plan" sub line, Compare's plan): the
 * band's middle, rounded, for espresso; the recipe's total for everything else.
 */
export function targetSec(r: Pick<Recipe, "steps" | "blurb" | "brewer"> & { readonly tips?: readonly string[] }, total: number): number {
  if (r.brewer.method !== "espresso") return total;
  const b = shotBand(r);
  return Math.round((b.from + b.to) / 2);
}

/** What a brew's time is judged against: the recipe's total in seconds, or an espresso's band. */
export type TimeTarget = number | ShotBand;

export function timeTarget(r: Pick<Recipe, "steps" | "blurb" | "brewer"> & { readonly tips?: readonly string[] }, total: number): TimeTarget {
  return r.brewer.method === "espresso" ? shotBand(r) : total;
}

/**
 * A finished time against its target, for the lists (Log rows and table, Today's Recent): the
 * status, the signed seconds a miss shows, and the words a hit shows ("on time", or "in band" for
 * a shot, as Android's Log rows say). An espresso shot is judged the way shot mode and Review
 * judge it (in the band or not, the delta from the nearer edge), never by the ±15 s window filter
 * coffee gets around a single number. Null without a time or a target.
 */
export function judgeTime(sec: number, target: TimeTarget | null): { status: Status; delta: number; hit: string } | null {
  if (target == null || sec <= 0) return null;
  if (typeof target === "number") return target > 0 ? { status: timeStatus(sec, target)[0], delta: sec - target, hit: "on time" } : null;
  return { status: BAND_WORDS[bandStatus(sec, target)].status, delta: shotOffBy(sec, target), hit: "in band" };
}
