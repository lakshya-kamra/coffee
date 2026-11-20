/**
 * Which Today layout a window gets (v4 §3.1). Pure, so the choices are tested; the CSS reads the
 * result from data attributes and does the rest. Window class comes from the shell's matchMedia
 * listeners, so a resize re-renders Today once per class change and never in a loop.
 */
import type { WindowClass } from "@/app/windowClass";

/**
 * column: the phone layout (one column, Start from as a carousel).
 * split: medium (720 column, Recent beside Your coffee, Start from as a 2-up grid).
 * dashboard: expanded and up (12-column grid, span 7 / 5, Start from full width below).
 */
export type TodayMode = "column" | "split" | "dashboard";

export interface TodayLayout {
  readonly mode: TodayMode;
  /** Recent rows shown (Android shows 3; wide screens have room for more). */
  readonly recentCount: number;
  /** Start from columns; 1 = the touch carousel. */
  readonly startCols: number;
  /** How many Start from cards: two rows of the grid, or the carousel's dozen. */
  readonly startCount: number;
  /** "Your coffee" tile (bean on deck). */
  readonly yourCoffee: boolean;
  /** "Last grind" tile (grinder and setting used last for the next recipe). */
  readonly lastGrind: boolean;
  /** Short landscape: Start brew sits on the numbers row so it is reachable without scrolling. */
  readonly startInline: boolean;
  /** The dashboard's max width (1200 at large, 1360 at XL), else null (the column token). */
  readonly maxWidth: number | null;
}

export function todayLayout(wc: WindowClass): TodayLayout {
  const startInline = wc.short && wc.landscape;
  switch (wc.width) {
    case "compact":
      return { mode: "column", recentCount: 3, startCols: 1, startCount: 12, yourCoffee: false, lastGrind: false, startInline, maxWidth: null };
    case "medium":
      return { mode: "split", recentCount: 3, startCols: 2, startCount: 4, yourCoffee: true, lastGrind: false, startInline, maxWidth: null };
    case "expanded":
      // A phone on its side (844 x 390) is "expanded" but has no height for a dashboard.
      if (wc.short) return { mode: "split", recentCount: 3, startCols: 3, startCount: 6, yourCoffee: true, lastGrind: false, startInline, maxWidth: null };
      return { mode: "dashboard", recentCount: 6, startCols: 3, startCount: 6, yourCoffee: true, lastGrind: false, startInline, maxWidth: 1200 };
    case "large":
    case "xl": {
      const maxWidth = wc.width === "xl" ? 1360 : 1200;
      if (wc.short) return { mode: "split", recentCount: 3, startCols: 4, startCount: 8, yourCoffee: true, lastGrind: true, startInline, maxWidth: null };
      return { mode: "dashboard", recentCount: 8, startCols: 4, startCount: 8, yourCoffee: true, lastGrind: true, startInline, maxWidth };
    }
  }
}
