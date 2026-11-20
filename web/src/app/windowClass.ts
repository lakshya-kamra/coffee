/**
 * Window classes (v4 §1.1, §2.1). Five width classes plus two modifiers, read from matchMedia
 * listeners only: resizing never runs JS layout work, just these callbacks when a boundary is
 * crossed. Exposed with useSyncExternalStore; also mirrored to <html data-wc data-short> for
 * shell CSS. Only the shell, the router glue and the brew screen read this in JS; everything
 * else is CSS (media queries for the shell, container queries inside panes).
 */
import { useSyncExternalStore } from "react";

export type WidthClass = "compact" | "medium" | "expanded" | "large" | "xl";

export interface WindowClass {
  readonly width: WidthClass;
  /** Height < 480 (phone landscape, split screen, 200% zoom on a laptop). */
  readonly short: boolean;
  /** Width > height. */
  readonly landscape: boolean;
}

export const WIDTH_ORDER: readonly WidthClass[] = ["compact", "medium", "expanded", "large", "xl"];

/** Lower bound of each class in CSS px, exactly §1.1. */
export const WIDTH_MIN: Readonly<Record<WidthClass, number>> = { compact: 0, medium: 600, expanded: 840, large: 1200, xl: 1600 };
export const SHORT_MAX_HEIGHT = 480;

/** The literal media queries; CSS uses the same strings. */
export const QUERIES = {
  compact: "(max-width: 599.98px)",
  medium: "(min-width: 600px)",
  expanded: "(min-width: 840px)",
  large: "(min-width: 1200px)",
  xl: "(min-width: 1600px)",
  short: "(max-height: 479.98px)",
  landscape: "(orientation: landscape)",
} as const;

export function widthClassFor(widthPx: number): WidthClass {
  if (widthPx >= WIDTH_MIN.xl) return "xl";
  if (widthPx >= WIDTH_MIN.large) return "large";
  if (widthPx >= WIDTH_MIN.expanded) return "expanded";
  if (widthPx >= WIDTH_MIN.medium) return "medium";
  return "compact";
}

/** Pure mapping from a window size, for tests and for anything that already has the numbers. */
export function windowClassFor(widthPx: number, heightPx: number): WindowClass {
  return { width: widthClassFor(widthPx), short: heightPx < SHORT_MAX_HEIGHT, landscape: widthPx > heightPx };
}

/** True when `w` is `min` or wider. */
export function atLeast(w: WidthClass, min: WidthClass): boolean {
  return WIDTH_ORDER.indexOf(w) >= WIDTH_ORDER.indexOf(min);
}

/** §1.1 wide-landscape: width >= 600, wider than tall, height >= 480. */
export function isWideLandscape(wc: WindowClass): boolean {
  return wc.width !== "compact" && wc.landscape && !wc.short;
}

// ---------------------------------------------------------------- the live store

const FALLBACK: WindowClass = Object.freeze({ width: "compact", short: false, landscape: false });

let snap: WindowClass = FALLBACK;
let override: WindowClass | null = null;
const listeners = new Set<() => void>();
let mqs: Record<keyof typeof QUERIES, MediaQueryList> | null = null;

function read(): WindowClass {
  if (!mqs) return FALLBACK;
  const width: WidthClass = mqs.xl.matches ? "xl" : mqs.large.matches ? "large" : mqs.expanded.matches ? "expanded" : mqs.medium.matches ? "medium" : "compact";
  return { width, short: mqs.short.matches, landscape: mqs.landscape.matches };
}

function mirror(wc: WindowClass) {
  if (typeof document === "undefined") return;
  const el = document.documentElement;
  if (el.dataset.wc !== wc.width) el.dataset.wc = wc.width;
  if (wc.short) el.dataset.short = "";
  else delete el.dataset.short;
}

function update() {
  const next = override ?? read();
  if (next.width === snap.width && next.short === snap.short && next.landscape === snap.landscape) return;
  snap = Object.freeze({ ...next });
  mirror(snap);
  for (const l of listeners) l();
}

/** Starts the listeners (idempotent). main.tsx calls it before the first render. */
export function startWindowClass(): void {
  if (mqs || typeof matchMedia !== "function") return;
  const make = (q: string) => matchMedia(q);
  mqs = {
    compact: make(QUERIES.compact),
    medium: make(QUERIES.medium),
    expanded: make(QUERIES.expanded),
    large: make(QUERIES.large),
    xl: make(QUERIES.xl),
    short: make(QUERIES.short),
    landscape: make(QUERIES.landscape),
  };
  for (const mq of Object.values(mqs)) mq.addEventListener?.("change", update);
  snap = FALLBACK;
  // Force the first mirror even when the class equals the fallback.
  const first = read();
  snap = Object.freeze({ ...first });
  mirror(snap);
}

export const windowClass = {
  get: (): WindowClass => snap,
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

export function useWindowClass(): WindowClass {
  return useSyncExternalStore(windowClass.subscribe, windowClass.get, windowClass.get);
}

/** Test hook: pin the class (null returns to the media queries). */
export function setWindowClassForTests(wc: WindowClass | null): void {
  override = wc;
  if (wc == null) {
    snap = FALLBACK;
    const next = read();
    snap = Object.freeze({ ...next });
    mirror(snap);
    for (const l of listeners) l();
    return;
  }
  update();
}
