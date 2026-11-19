/**
 * Pure status mappers from ui/components/Status.kt. A status colour always travels with a word:
 * every mapper that a screen shows returns the word too, or the caller supplies one.
 */
import type { BedShape, Channeling, FlowFeel, Sludge } from "./evaluation";

export type Status = "live" | "good" | "neutral" | "attention" | "fault" | "unrated";

/** CSS variable for the status ink (Status.color()). */
export function statusColorVar(s: Status): string {
  switch (s) {
    case "live": return "var(--crema-ink)";
    case "good": return "var(--good)";
    case "neutral": return "var(--ink-high)";
    case "attention": return "var(--attention)";
    case "fault": return "var(--fault)";
    case "unrated": return "var(--ink-low)";
  }
}

/** CSS variable for the status container (Status.container()). */
export function statusContainerVar(s: Status): string {
  switch (s) {
    case "live": return "var(--crema-container)";
    case "good": return "var(--good-container)";
    case "neutral": return "var(--surface2)";
    case "attention": return "var(--attention-container)";
    case "fault": return "var(--fault-container)";
    case "unrated": return "var(--surface2)";
  }
}

export function scoreStatus(score: number): Status {
  if (score >= 8 && score <= 10) return "good";
  if (score >= 6 && score <= 7) return "neutral";
  if (score >= 4 && score <= 5) return "attention";
  if (score >= 1 && score <= 3) return "fault";
  return "unrated";
}

function mss(sec: number): string {
  const s = Math.trunc(sec);
  const r = s % 60;
  return `${Math.trunc(s / 60)}:${r < 10 ? "0" + r : r}`;
}

/** Total time against the recipe target, with the words to show beside it. */
export function timeStatus(actualSec: number, targetSec: number): [Status, string] {
  if (targetSec <= 0) return ["neutral", "No target"];
  if (actualSec <= 0) return ["unrated", "No time"];
  const d = actualSec - targetSec;
  const words = (d < 0 ? "Fast by " : "Slow by ") + mss(Math.abs(d));
  if (Math.abs(d) <= 15) return ["good", "On time"];
  if (Math.abs(d) <= 45) return ["attention", words];
  return ["fault", words];
}

export function flowStatus(flow: FlowFeel | null): Status | null {
  switch (flow) {
    case "on-target": return "good";
    case "slow": case "fast": return "attention";
    case "stalled": case "gusher": return "fault";
    default: return null;
  }
}

export function bedStatus(bed: BedShape | null): Status | null {
  switch (bed) {
    case "flat": return "good";
    case "domed": return "neutral";
    case "crater": case "high-and-dry": case "sloped": return "attention";
    case "sinkhole": case "muddy": return "fault";
    default: return null;
  }
}

export function sludgeStatus(s: Sludge | null): Status | null {
  switch (s) {
    case "none": return "good";
    case "light": return "neutral";
    case "heavy": return "attention";
    default: return null;
  }
}

export function channelingStatus(c: Channeling | null): Status | null {
  switch (c) {
    case "none": return "good";
    case "suspected": return "attention";
    case "obvious": return "fault";
    default: return null;
  }
}

/** Freshness: resting (0-3 days) and fading (31+) are worth a look; in between is neutral. */
export function roastStatus(days: number | null): Status {
  if (days == null) return "unrated";
  const d = Math.max(0, days);
  if (d <= 3) return "attention";
  if (d <= 30) return "neutral";
  return "attention";
}

export function roastWord(days: number | null): string | null {
  if (days == null) return null;
  const d = Math.max(0, days);
  if (d <= 3) return "resting";
  if (d <= 30) return null;
  return "fading";
}

export function cupLabel(score: number): string {
  if (score >= 8 && score <= 10) return "GREAT CUP";
  if (score >= 6 && score <= 7) return "GOOD";
  if (score >= 4 && score <= 5) return "OFF";
  if (score >= 1 && score <= 3) return "FAULTY";
  return "UNRATED";
}

/** "+0:12", "−1:05" (true minus sign), "0:00". */
export function signedClock(deltaSec: number): string {
  if (deltaSec === 0) return "0:00";
  return deltaSec < 0 ? "−" + mss(-deltaSec) : "+" + mss(deltaSec);
}
