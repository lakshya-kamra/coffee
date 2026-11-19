/**
 * Pure recipe → drawing models (ports of the helpers at the bottom of JourneyLine.kt and
 * Timeline.kt, and BrewScreen.kt's StepKind.mood()). No React, fully unit-tested.
 */
import { clock } from "@/domain/core/format";
import { totalSec, type Recipe, type StepKind } from "@/domain/core/recipe";
import type { Status } from "@/domain/core/status";

export type NodeKind = "step" | "pour" | "event" | "actual";

/**
 * A stop on a journey line. Give `fraction` (of the planned total; Historical may run past 1)
 * or `atSec` with the line's `totalSec`. Higher `priority` labels win when labels collide.
 */
export interface JourneyNode {
  fraction?: number;
  atSec?: number;
  label?: string | null;
  kind?: NodeKind;
  status?: Status | null;
  priority?: number;
  /** Informational (cumulative grams at this stop); not drawn. */
  waterG?: number | null;
  done?: boolean;
}

/** Background wash keyed to where you are in a brew (Color.kt BrewMood). */
export type BrewMood = "idle" | "bloom" | "pouring" | "steeping" | "drawdown" | "done";

export const BREW_MOODS: readonly BrewMood[] = ["idle", "bloom", "pouring", "steeping", "drawdown", "done"];

/** BrewScreen.kt StepKind.mood(). */
export function stepMood(kind: StepKind): BrewMood {
  switch (kind) {
    case "Prep": return "idle";
    case "Bloom": return "bloom";
    case "Pour": case "Swirl": case "Stir": return "pouring";
    case "Steep": case "Press": return "steeping";
    case "Drawdown": return "drawdown";
    case "Serve": return "done";
  }
}

/** When the last pour finishes, in seconds from the start, or null for a recipe with no pours. */
export function lastPourEndSec(r: Recipe): number | null {
  let end: number | null = null;
  for (const s of r.steps) if (s.targetWaterG != null && (end == null || s.endSec > end)) end = s.endSec;
  return end;
}

/**
 * Route nodes for a recipe card: where each pour starts, as a fraction of the total TIME.
 * Kasuya's five pours show more stops than Hoffmann's two.
 */
export function pourNodes(r: Recipe): number[] {
  const total = totalSec(r);
  if (total <= 0) return [];
  const out: number[] = [];
  for (const s of r.steps) {
    if (s.targetWaterG == null) continue;
    const f = s.startSec / total;
    if (f > 0.02 && f < 0.98 && !out.includes(f)) out.push(f);
  }
  return out;
}

/**
 * Nodes for the live and historical journey lines: every step start after the first, with clock
 * labels on pours and the drawdown, and the end of the last pour marked most important (that is
 * when the bed starts to drain).
 */
export function journeyNodes(r: Recipe): JourneyNode[] {
  const total = Math.max(1, totalSec(r));
  const starts: number[] = [];
  const out: JourneyNode[] = [];
  r.steps.forEach((s, i) => {
    if (i === 0 || s.startSec <= 0 || s.startSec >= total) return;
    const kind: NodeKind = s.kind === "Pour" || s.kind === "Bloom" ? "pour" : s.kind === "Drawdown" ? "event" : "step";
    const labelled = kind === "pour" || kind === "event";
    starts.push(s.startSec);
    out.push({ fraction: s.startSec / total, label: labelled ? clock(s.startSec) : null, kind, priority: kind === "pour" ? 1 : 0 });
  });
  const end = lastPourEndSec(r);
  if (end != null && end >= 1 && end < total) {
    const at = starts.indexOf(end);
    if (at >= 0) out[at] = { ...out[at]!, label: clock(end), priority: 2 };
    else out.push({ fraction: end / total, label: clock(end), kind: "pour", priority: 2 });
  }
  return out;
}

/** One stop on a timeline (Timeline.kt TimelineRow). `time` falls back to clock(startSec). */
export interface TimelineRow {
  time?: string;
  /** Older stub fields, still accepted. */
  startSec?: number;
  endSec?: number;
  kind?: string | null;
  title: string;
  detail?: string | null;
  trailing?: string | null;
  actual?: boolean;
  delta?: string | null;
  deltaStatus?: Status | null;
  terminal?: boolean;
  /** The plan an actual time replaced, shown struck through under it. */
  planned?: string | null;
}

/** The recipe as a planned timeline: every step, then a terminal "Done" at the total time. */
export function plannedTimelineRows(r: Recipe): TimelineRow[] {
  return [
    ...r.steps.map((s) => ({
      time: clock(s.startSec),
      kind: s.kind,
      title: s.title,
      detail: s.detail.trim() ? s.detail : null,
      trailing: s.targetWaterG != null ? `${s.targetWaterG} g` : null,
    })),
    { time: clock(totalSec(r)), kind: null, title: "Done", terminal: true },
  ];
}

/** A node's position as a fraction of the plan. */
export function nodeFraction(n: JourneyNode, totalSecs: number | undefined): number | null {
  if (n.fraction != null) return n.fraction;
  if (n.atSec != null && totalSecs != null && totalSecs > 0) return n.atSec / totalSecs;
  return null;
}
