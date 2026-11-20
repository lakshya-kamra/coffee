/**
 * Chart models from InsightsCharts.kt as pure geometry (web-spec §11 Insights: charts are inline
 * SVG from pure models). Everything is computed once per data change; the screen only draws.
 */
import { epochDayOf } from "@/domain/core/format";
import type { GrindSeries } from "./passportStats";

const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"] as const;

/** "6 SEP", or "TODAY" when `allowToday` and the brew was today. */
export function axisDate(at: number, allowToday: boolean, now: number = Date.now()): string {
  if (allowToday && epochDayOf(at) === epochDayOf(now)) return "TODAY";
  const d = new Date(at);
  return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
}

/** "14 SEP": the upper-cased "d MMM" Android uses for eyebrows and date chips. */
export function dayMonthCaps(at: number): string {
  const d = new Date(at);
  return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
}

/** Round gridline steps (1, 2, 5, 10…) with at most five lines, so the gaps are even. */
export function gridSteps(lo: number, hi: number): number[] {
  const step = [1, 2, 5, 10, 20, 50].find((s) => (hi - lo) / s <= 5) ?? 100;
  const out: number[] = [];
  for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) out.push(v);
  return out;
}

export interface GrindChartPoint {
  readonly x: number;
  readonly y: number;
  readonly clicks: number;
  readonly score: number;
  readonly latest: boolean;
}

export interface GrindChartModel {
  readonly width: number;
  readonly height: number;
  readonly plotRight: number;
  readonly points: readonly GrindChartPoint[];
  /** SVG path "M x y L x y …"; "" with one point. */
  readonly line: string;
  readonly grid: readonly { readonly y: number; readonly value: number; readonly showLabel: boolean }[];
  readonly band: { readonly top: number; readonly bottom: number; readonly label: string } | null;
  /** Where the latest point's own number sits (above its ring). */
  readonly latestLabel: { readonly x: number; readonly y: number; readonly text: string } | null;
}

/** Micro text is 10 px with a 12 px line (tokens.css .micro). */
const LABEL_H = 12;

/**
 * Clicks over time for one grinder on one recipe, coarser always up (the axis flips for dials
 * numbered the other way round). Geometry in a `width` × `height` viewBox.
 */
export function grindChartModel(series: GrindSeries, width = 320, height = 176): GrindChartModel {
  const pts = series.points;
  const minClicks = pts.length ? Math.min(...pts.map((p) => p.clicks)) : 0;
  const maxClicks = pts.length ? Math.max(...pts.map((p) => p.clicks)) : 0;
  const lo = minClicks - 3;
  const hi = maxClicks + 3;
  // The axis words live in their own top and bottom gutters, never over a point.
  const plotTop = LABEL_H + 4;
  const plotBottom = height - LABEL_H - 4;
  const plotH = plotBottom - plotTop;
  const plotRight = width - 28;
  const inset = 8;
  const xLeft = inset;
  const xRight = plotRight - inset;
  const yOf = (clicks: number) => {
    const f = (clicks - lo) / (hi - lo);
    return series.lowerIsFiner ? plotBottom - f * plotH : plotTop + f * plotH;
  };
  const t0 = pts[0]?.at ?? 0;
  const span = (pts[pts.length - 1]?.at ?? 0) - t0;
  const points = pts.map((p, i): GrindChartPoint => {
    const f = span > 0 ? (p.at - t0) / span : pts.length > 1 ? i / (pts.length - 1) : 0.5;
    return { x: xLeft + f * (xRight - xLeft), y: yOf(p.clicks), clicks: p.clicks, score: p.score, latest: i === pts.length - 1 };
  });
  const line = points.length > 1 ? points.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(" ") : "";

  const last = points[points.length - 1];
  const rLatest = 6;
  const ring = 2;
  const gap = 2;
  // The latest point's own number sits inside the plot, just left of its ring and level with it
  // (x is its right edge). Above the ring, at the right end, it read as one more axis tick, and
  // hiding the axis number beside it left the ticks unevenly spaced (80, 70, 65).
  const latestLabel = last ? { x: last.x - rLatest - ring - gap, y: last.y, text: String(last.clicks) } : null;
  const grid = gridSteps(lo, hi).map((v) => ({ y: yOf(v), value: v, showLabel: true }));
  const band = series.bestZone
    ? (() => {
        const a = yOf(series.bestZone.first - 0.5);
        const b = yOf(series.bestZone.last + 0.5);
        return { top: Math.min(a, b), bottom: Math.max(a, b), label: `BEST ${series.bestZone.first}–${series.bestZone.last}` };
      })()
    : null;
  return { width, height, plotRight, points, line, grid, band, latestLabel };
}
