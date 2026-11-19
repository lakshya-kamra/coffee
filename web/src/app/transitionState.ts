/**
 * Transition bookkeeping (web-spec §6.4, the web form of audit §3.6):
 * - begin()/end() count running screen transitions; afterTransition(cb) runs cb once none run,
 *   so a navigation-triggered Dexie write can never re-render a list mid-flight (rule 5).
 * - useTransitionSettled(): false inside a detail until its enter animation completes; heavy
 *   sections render fixed-height placeholders until then.
 * - The last pointerdown position, for the container-transform fallback's transform-origin.
 * - prefersReducedMotion(): the resolved motion preference, read by imperative transitions.
 */
import { createContext, useContext } from "react";

/** No screen transition lasts this long; past it a waiting callback runs anyway. */
export const MAX_TRANSITION_WAIT_MS = 1000;

let running = 0;
let queue: (() => void)[] = [];
let flushScheduled = false;
let overdue: ReturnType<typeof setTimeout> | null = null;

function flush(force = false) {
  flushScheduled = false;
  if (running > 0 && !force) return;
  if (overdue != null) {
    clearTimeout(overdue);
    overdue = null;
  }
  const q = queue;
  queue = [];
  for (const cb of q) {
    try {
      cb();
    } catch (e) {
      console.error("afterTransition callback failed", e);
    }
  }
}

function scheduleFlush() {
  if (flushScheduled || queue.length === 0) return;
  flushScheduled = true;
  // A macrotask, so the commit that ended the transition paints first.
  setTimeout(flush, 0);
}

export function beginTransition(): () => void {
  running++;
  let ended = false;
  return () => {
    if (ended) return;
    ended = true;
    running = Math.max(0, running - 1);
    if (running === 0) scheduleFlush();
  };
}

export function isTransitioning(): boolean {
  return running > 0;
}

/**
 * Runs cb after the current screen transition settles (next macrotask when none is running).
 * A transition that never reports its end must not hold a save back for good, so a waiting
 * callback runs after MAX_TRANSITION_WAIT_MS whatever the count says.
 */
export function afterTransition(cb: () => void): void {
  queue.push(cb);
  if (running === 0) scheduleFlush();
  else overdue ??= setTimeout(() => {
    overdue = null;
    flush(true);
  }, MAX_TRANSITION_WAIT_MS);
}

/** Test hook: forget every running transition and queued callback. */
export function resetTransitionsForTests(): void {
  running = 0;
  queue = [];
  flushScheduled = false;
  if (overdue != null) clearTimeout(overdue);
  overdue = null;
}

/** True once the enclosing detail's enter animation has finished. Tabs are always settled. */
export const SettledContext = createContext(true);

export function useTransitionSettled(): boolean {
  return useContext(SettledContext);
}

// ---------------------------------------------------------------- pointer origin

let lastPointer: { x: number; y: number; at: number } | null = null;

/** Called from a capture-phase pointerdown listener on the document. */
export function rememberPointer(x: number, y: number): void {
  lastPointer = { x, y, at: performance.now() };
}

/** The last pointerdown within 1 s, else null (keyboard navigation scales from the centre). */
export function recentPointer(): { x: number; y: number } | null {
  if (!lastPointer || performance.now() - lastPointer.at > 1000) return null;
  return { x: lastPointer.x, y: lastPointer.y };
}

// ---------------------------------------------------------------- reduced motion

const mq = typeof matchMedia === "function" ? matchMedia("(prefers-reduced-motion: reduce)") : null;

/** settings.motion "reduced" (mirrored to <html data-motion>) or the system setting. */
export function prefersReducedMotion(): boolean {
  if (typeof document !== "undefined" && document.documentElement.dataset.motion === "reduced") return true;
  return mq?.matches ?? false;
}

// ---------------------------------------------------------------- shell elements

/** Elements the transitions write to imperatively. Set by TabsHost / DetailHost refs. */
export const shellElements: { tabs: HTMLElement | null } = { tabs: null };
