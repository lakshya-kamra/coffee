/**
 * One requestAnimationFrame loop for everything that draws per frame (the ring, the live journey
 * line, the clock digits). Callbacks write to canvas, attributes or textContent; they never set
 * React state (rule 4). The loop stops when nothing is registered or the page is hidden.
 */
import { useEffect, useRef } from "react";

export type FrameFn = (now: number) => void;

const fns = new Set<FrameFn>();
let raf = 0;

function loop(now: number) {
  raf = 0;
  for (const fn of fns) {
    try {
      fn(now);
    } catch (e) {
      console.error("frame callback failed", e);
    }
  }
  if (fns.size > 0 && !document.hidden) raf = requestAnimationFrame(loop);
}

function start() {
  if (raf === 0 && fns.size > 0 && typeof document !== "undefined" && !document.hidden) raf = requestAnimationFrame(loop);
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", start);
}

/** Registers a callback for every frame; returns the unregister function. */
export function addFrame(fn: FrameFn): () => void {
  fns.add(fn);
  start();
  return () => {
    fns.delete(fn);
    if (fns.size === 0 && raf !== 0) {
      cancelAnimationFrame(raf);
      raf = 0;
    }
  };
}

/** Calls `fn` every frame while `active`; always the latest `fn`, without re-subscribing. */
export function useFrame(fn: FrameFn, active = true): void {
  const ref = useRef(fn);
  useEffect(() => {
    ref.current = fn;
  });
  useEffect(() => {
    if (!active) return;
    return addFrame((now) => ref.current(now));
  }, [active]);
}

/**
 * Writes `text` into an element's textContent only when it changed: the per-frame leaves (clock,
 * countdown, water readout) cost a string compare on most frames and a text write once a second.
 */
export function writeText(el: HTMLElement | null, text: string): void {
  if (el && el.textContent !== text) el.textContent = text;
}
