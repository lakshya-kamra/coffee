/**
 * The interactive bits of the Insights charts (v4 §3.9) as pure functions: which point is under
 * the pointer, which dates get an x-axis tick, how the tooltip reads and which way it leans.
 */

/**
 * The index of the point nearest to `x` (both as fractions of the plot width, 0..1); -1 with no
 * points. `xs` are in drawing order (left to right); ties go to the later point.
 */
export function nearestIndex(xs: readonly number[], x: number): number {
  let best = -1;
  let bestD = Infinity;
  for (let i = 0; i < xs.length; i++) {
    const d = Math.abs(xs[i]! - x);
    if (d <= bestD) {
      best = i;
      bestD = d;
    }
  }
  return best;
}

/** Bar centres for `n` evenly spaced slots: (i + 0.5) / n. */
export function slotCentres(n: number): number[] {
  return Array.from({ length: n }, (_, i) => (i + 0.5) / n);
}

/** At most `max` tick positions, evenly spread, always the first and the last. */
export function tickIndices(n: number, max = 5): number[] {
  if (n <= 0) return [];
  if (n === 1) return [0];
  const k = Math.max(2, Math.min(max, n));
  const out = new Set<number>();
  for (let j = 0; j < k; j++) out.add(Math.round((j * (n - 1)) / (k - 1)));
  return [...out].sort((a, b) => a - b);
}

/** Ticks whose dates collide are dropped, keeping the ends: two ticks closer than `minGap` (fraction of width). */
export function spacedTicks(fracs: readonly number[], indices: readonly number[], minGap = 0.2): number[] {
  const out: number[] = [];
  for (const i of indices) {
    const last = out[out.length - 1];
    if (last == null || fracs[i]! - fracs[last]! >= minGap) out.push(i);
    else if (i === indices[indices.length - 1]) {
      // The last date always shows; it replaces a crowded neighbour.
      if (out.length > 1) out[out.length - 1] = i;
    }
  }
  return out;
}

/** "Thu 12 Nov" in en-GB, "Thu, Nov 12" in en-US. */
export function tooltipDate(at: number, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { weekday: "short", day: "numeric", month: "short" }).format(new Date(at));
}

/** "12 Nov" / "Nov 12": the x-axis tick. */
export function tickDate(at: number, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { day: "numeric", month: "short" }).format(new Date(at));
}

/** "Thu 12 Nov · Hoffmann V60 · 8/10" (§3.9); `extra` sits before the score ("72 clicks"). */
export function tooltipText(at: number, recipe: string, score: number, extra?: string | null, locale?: string): string {
  return [tooltipDate(at, locale), recipe.trim() || "Brew", extra ?? null, score > 0 ? `${score}/10` : "not rated"].filter(Boolean).join(" · ");
}

/** The tooltip hangs right of a point near the left edge, left of one near the right edge, else centred. */
export function tooltipAlign(f: number): "start" | "center" | "end" {
  if (f < 0.2) return "start";
  if (f > 0.8) return "end";
  return "center";
}

/** Keyboard step: Left/Right move, Home/End jump; the first key starts at the newest point. Null: not a chart key. */
export function chartKeyStep(key: string, index: number | null, count: number): number | null {
  if (count === 0) return null;
  const i = index ?? count;
  switch (key) {
    case "ArrowLeft":
      return Math.max(0, i - 1);
    case "ArrowRight":
      return index == null ? count - 1 : Math.min(count - 1, i + 1);
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}
