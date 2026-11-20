/**
 * One indicator that slides between equal slots (bottom bar pill, SegmentedControl; vertically for
 * the v4 rail, `axis` "y"). Its size is measured only in a ResizeObserver callback (rule 8).
 *
 * `liquid` (the bar and the rail, Forge 2.0's FloatingNavBar): the pill rides a spatialDefault
 * spring in slot units and stretches along the slide with its velocity, |v| × 7 px per slot a
 * second capped at 30 px, squashing across by 12% of the stretch, so a jump across three tabs
 * reads as one liquid motion. The spring is solved up front and played as one WAAPI keyframe
 * animation (translate + scale about the centre), so it runs on the compositor with no script per
 * frame. A retap mid-slide reads the spring's own position and speed at that instant and solves a
 * new one from there: the pill bends toward the new tab, never restarts or jumps.
 *
 * Plain (the segmented control) slides with one Motion transform animation on the compositor.
 */
import { animate, type AnimationPlaybackControls } from "motion";
import { useLayoutEffect, useRef, type RefObject } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { SPRINGS, spatialDefault } from "./motion";

/** The element's current horizontal translation in px (works mid-animation). */
export function currentTranslateX(el: HTMLElement): number {
  return currentTranslate(el, "x");
}

/** The element's current translation along `axis` in px (works mid-animation). */
export function currentTranslate(el: HTMLElement, axis: "x" | "y"): number {
  const t = getComputedStyle(el).transform;
  if (!t || t === "none") return 0;
  try {
    const m = new DOMMatrixReadOnly(t);
    return axis === "x" ? m.m41 : m.m42;
  } catch {
    return 0;
  }
}

/** Forge's numbers: 7 per (slot per second), at most 30, and the cross axis gives up 12% of it. */
export const STRETCH_PER_SLOT_PER_SEC = 7;
export const STRETCH_MAX = 30;
export const SQUASH = 0.12;

/**
 * The pill's transform at `pos` slots moving at `velocity` slots per second. `along` and `across`
 * are its size in px along the slide and across it; the stretch is in px, as Forge's dp.
 */
export function liquidTransform(axis: "x" | "y", pos: number, velocity: number, slot: number, along: number, across: number): string {
  const stretch = Math.min(Math.abs(velocity) * STRETCH_PER_SLOT_PER_SEC, STRETCH_MAX);
  const sAlong = along > 0 ? (along + stretch) / along : 1;
  const sAcross = across > 0 ? Math.max(0, across - stretch * SQUASH) / across : 1;
  const r = (v: number) => Math.round(v * 1000) / 1000;
  const t = r(pos * slot);
  return axis === "x" ? `translateX(${t}px) scale(${r(sAlong)}, ${r(sAcross)})` : `translateY(${t}px) scale(${r(sAcross)}, ${r(sAlong)})`;
}

/** One solved spring: position and velocity (slots, slots per second) every `dt` seconds. */
export interface SpringPath {
  readonly dt: number;
  readonly x: readonly number[];
  readonly v: readonly number[];
}

/**
 * spatialDefault (ζ 0.9, k 700, mass 1) from (x0, v0) to `target`, sampled at 120 Hz until it rests
 * within 0.002 slots and 0.02 slots/s (at most 1.5 s). Semi-implicit Euler on 1 ms sub-steps.
 */
export function solveSpring(x0: number, v0: number, target: number, dt = 1 / 120): SpringPath {
  const k = SPRINGS.spatialDefault.k;
  const c = 2 * SPRINGS.spatialDefault.zeta * Math.sqrt(k);
  const xs = [x0];
  const vs = [v0];
  let x = x0;
  let v = v0;
  const sub = Math.max(1, Math.round(dt / 0.001));
  const h = dt / sub;
  for (let i = 0; i < 1.5 / dt; i++) {
    for (let j = 0; j < sub; j++) {
      v += (-k * (x - target) - c * v) * h;
      x += v * h;
    }
    xs.push(x);
    vs.push(v);
    if (Math.abs(x - target) < 0.002 && Math.abs(v) < 0.02) break;
  }
  xs[xs.length - 1] = target;
  vs[vs.length - 1] = 0;
  return { dt, x: xs, v: vs };
}

/** Where a solved spring is `sec` after it started (linear between samples), for a retarget. */
export function springAt(p: SpringPath, sec: number): { x: number; v: number } {
  const f = Math.max(0, sec / p.dt);
  const i = Math.floor(f);
  if (i >= p.x.length - 1) return { x: p.x[p.x.length - 1]!, v: 0 };
  const t = f - i;
  return { x: p.x[i]! + (p.x[i + 1]! - p.x[i]!) * t, v: p.v[i]! + (p.v[i + 1]! - p.v[i]!) * t };
}

export interface SlidingOptions {
  readonly axis?: "x" | "y";
  readonly liquid?: boolean;
}

interface LiquidRun {
  readonly anim: Animation;
  readonly path: SpringPath;
  readonly t0: number;
}

export function useSlidingIndicator(
  trackRef: RefObject<HTMLElement | null>,
  indicatorRef: RefObject<HTMLElement | null>,
  index: number,
  count: number,
  axisOrOptions: "x" | "y" | SlidingOptions = "x",
): void {
  const opts = typeof axisOrOptions === "string" ? { axis: axisOrOptions } : axisOrOptions;
  const axis = opts.axis ?? "x";
  const liquid = opts.liquid ?? false;
  const slot = useRef(0);
  // The pill's thickness across the slide, for the squash (px, measured with the slot).
  const across = useRef(0);
  const shown = useRef(index);
  const running = useRef<AnimationPlaybackControls | null>(null);
  const flow = useRef<LiquidRun | null>(null);
  const fn = axis === "x" ? "translateX" : "translateY";

  useLayoutEffect(() => {
    const t = trackRef.current;
    const ind = indicatorRef.current;
    if (!t || !ind) return;
    const ro = new ResizeObserver((entries) => {
      const r = entries[0]?.contentRect;
      const len = (axis === "x" ? r?.width : r?.height) ?? 0;
      slot.current = count > 0 ? len / count : 0;
      across.current = (axis === "x" ? r?.height : r?.width) ?? 0;
      if (axis === "x") ind.style.width = `${slot.current}px`;
      else ind.style.height = `${slot.current}px`;
      // A resize is not a move: jump, no animation.
      flow.current?.anim.cancel();
      flow.current = null;
      ind.style.transform = liquid ? liquidTransform(axis, shown.current, 0, slot.current, slot.current, across.current) : `${fn}(${shown.current * slot.current}px)`;
    });
    ro.observe(t);
    return () => ro.disconnect();
  }, [trackRef, indicatorRef, count, axis, fn, liquid]);

  useLayoutEffect(() => {
    const ind = indicatorRef.current;
    if (!ind || shown.current === index) return;
    const prev = shown.current;
    shown.current = index;
    if (liquid) {
      const rest = liquidTransform(axis, index, 0, slot.current, slot.current, across.current);
      const was = flow.current;
      // Mid-slide: the spring's own state now (no DOM read); settled: at rest on the old slot.
      const from = was ? springAt(was.path, (performance.now() - was.t0) / 1000) : { x: prev, v: 0 };
      was?.anim.cancel();
      flow.current = null;
      ind.style.transform = rest;
      if (prefersReducedMotion() || slot.current === 0 || typeof ind.animate !== "function") return;
      const path = solveSpring(from.x, from.v, index);
      const frames = path.x.map((x, i) => ({ transform: liquidTransform(axis, x, path.v[i]!, slot.current, slot.current, across.current) }));
      const anim = ind.animate(frames, { duration: (path.x.length - 1) * path.dt * 1000, easing: "linear" });
      const run: LiquidRun = { anim, path, t0: performance.now() };
      flow.current = run;
      anim.onfinish = () => {
        if (flow.current === run) flow.current = null;
      };
      return undefined;
    }
    const target = `${fn}(${index * slot.current}px)`;
    if (prefersReducedMotion() || slot.current === 0) {
      ind.style.transform = target;
      return;
    }
    // Start from where the pill is now, as a translate string of the same shape as the target,
    // so WAAPI interpolates one function rather than a matrix. A settled pill sits on its slot,
    // which is known without asking the DOM: reading computed style here runs in the same task
    // as a tab switch and forces a whole-document style pass before the frame even starts
    // (about 10 ms of a 4×-throttled CPU profile per switch). Only a pill caught mid-flight is read.
    const x = running.current ? currentTranslate(ind, axis) : prev * slot.current;
    running.current?.stop();
    const controls = animate(ind, { transform: [`${fn}(${x}px)`, target] }, spatialDefault);
    running.current = controls;
    void controls.then(() => {
      if (running.current === controls) running.current = null;
    });
    return undefined;
  }, [indicatorRef, index, axis, fn, liquid]);

  useLayoutEffect(
    () => () => {
      running.current?.stop();
      running.current = null;
      flow.current?.anim.cancel();
      flow.current = null;
    },
    [],
  );
}
