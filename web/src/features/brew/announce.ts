/**
 * Screen-reader announcements for the brew timer (v4 §5 A4): the exact strings, and a tiny
 * writer that clears the polite live region before each write so the same sentence twice is
 * still read twice. The strings are pure; the writer touches one element.
 */
import { clock } from "@/domain/core/format";
import { noScaleText } from "@/domain/core/noScale";
import type { RecipeStep } from "@/domain/core/recipe";

/** Steps longer than this get "10 seconds left" once. */
export const TEN_LEFT_MIN_STEP_SEC = 30;
export const TEN_LEFT = "10 seconds left";
export const TIME_FOR_NEXT = "Time for the next step.";

/** "about 60 ml": a jug is read to the nearest 10 ml. */
export const aboutMl = (g: number): number => Math.max(0, Math.round(g / 10) * 10);

/**
 * "Bloom to 50 g", "Pour to 300 g", "Drawdown", else the step's title. Without a scale the target
 * is a jug reading, "Pour to about 300 ml", and a title's grams read as ml (noScaleText), so the
 * banner, the status line and the screen reader never say a weight nobody can measure.
 */
export function stepLead(s: Pick<RecipeStep, "kind" | "title" | "targetWaterG">, hasScale = true): string {
  const amount = (t: number) => (hasScale ? `${t} g` : `about ${aboutMl(t)} ml`);
  if (s.kind === "Bloom" && s.targetWaterG != null) return `Bloom to ${amount(s.targetWaterG)}`;
  if (s.kind === "Pour" && s.targetWaterG != null) return `Pour to ${amount(s.targetWaterG)}`;
  if (s.kind === "Drawdown") return "Drawdown";
  return hasScale ? s.title : noScaleText(s.title);
}

const sentence = (t: string): string => {
  const x = t.trim();
  return !x ? "" : /[.!?]$/.test(x) ? x : `${x}.`;
};

/** "Step 2 of 6, Pour to 300 g. Steady spiral from the centre outward. Next at 1:15." */
export function stepAnnouncement(steps: readonly RecipeStep[], index: number, hasScale = true): string {
  const s = steps[index];
  if (!s) return "";
  const next = steps[index + 1];
  const parts = [`Step ${index + 1} of ${steps.length}, ${sentence(stepLead(s, hasScale))}`];
  const detail = sentence(hasScale ? s.detail : noScaleText(s.detail));
  if (detail) parts.push(detail);
  if (next) parts.push(`Next at ${clock(next.startSec)}.`);
  return parts.join(" ");
}

/** "Brew done, 3:32. Rate this brew." */
export function doneAnnouncement(elapsedSec: number): string {
  return `Brew done, ${clock(Math.max(0, Math.floor(elapsedSec)))}. Rate this brew.`;
}

/**
 * True exactly when "10 seconds left" is due: the step is longer than 30 s and the clock has just
 * reached its last 10 seconds (and not run past its end).
 */
export function tenLeftDue(step: Pick<RecipeStep, "startSec" | "endSec"> | undefined, elapsedSec: number): boolean {
  if (!step) return false;
  const length = step.endSec - step.startSec;
  if (length <= TEN_LEFT_MIN_STEP_SEC) return false;
  const left = step.endSec - elapsedSec;
  return left <= 10 && left > 0;
}

/** Writes to a polite live region, clearing it first so a repeat is announced again. */
export class Announcer {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private node: HTMLElement | null = null;
  private readonly el: () => HTMLElement | null;
  /** `el` finds the region; without it, pass `attach` as the region's ref. */
  constructor(el?: () => HTMLElement | null) {
    this.el = el ?? (() => this.node);
  }

  /** A callback ref for the live region. */
  readonly attach = (node: HTMLElement | null): void => {
    this.node = node;
  };

  say(text: string): void {
    const el = this.el();
    if (!el || !text) return;
    if (this.timer) clearTimeout(this.timer);
    el.textContent = "";
    // A beat between clear and write: screen readers skip a region that changes within one task.
    this.timer = setTimeout(() => {
      this.timer = null;
      const now = this.el();
      if (now) now.textContent = text;
    }, 60);
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
}
