/**
 * Which layout the brew screen uses (v4 §3.4, §3.5), from the window class alone. Pure, so the
 * choice is testable by size; the screen reads useWindowClass() and recomputes it live. The
 * engine holds every bit of brew state, so switching layout mid-brew loses nothing.
 */
import { atLeast, isWideLandscape, windowClassFor, type WindowClass } from "@/app/windowClass";

/** portrait: phones, tablet portrait. stage: wide landscape. short: height under 480. */
export type RunLayout = "portrait" | "stage" | "short";
/** single: one column. two: two columns. short: art, title and dose left; the rest right. */
export type SetupLayout = "single" | "two" | "short";

/** §3.5: `short` if height < 480; else `stage` if width >= 600 and wider than tall; else `portrait`. */
export function runLayoutFor(wc: WindowClass): RunLayout {
  if (wc.short) return "short";
  return isWideLandscape(wc) ? "stage" : "portrait";
}

/** §3.4: two columns at expanded and up and in wide landscape; the short split when short and >= 600 wide. */
export function setupLayoutFor(wc: WindowClass): SetupLayout {
  if (wc.short) return wc.width === "compact" ? "single" : "short";
  return atLeast(wc.width, "expanded") || isWideLandscape(wc) ? "two" : "single";
}

export const runLayoutForSize = (width: number, height: number): RunLayout => runLayoutFor(windowClassFor(width, height));
export const setupLayoutForSize = (width: number, height: number): SetupLayout => setupLayoutFor(windowClassFor(width, height));
