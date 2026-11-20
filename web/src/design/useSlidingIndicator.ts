/**
 * One indicator that slides between equal slots with `transform` and spatialDefault (bottom bar
 * pill, SegmentedControl; vertically for the v4 rail, `axis` "y"). Width is measured only in a ResizeObserver callback
 * (rule 8); the slide itself runs on the compositor (whole transform string, no onUpdate).
 */
import { animate, type AnimationPlaybackControls } from "motion";
import { useLayoutEffect, useRef, type RefObject } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { spatialDefault } from "./motion";

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

export function useSlidingIndicator(
  trackRef: RefObject<HTMLElement | null>,
  indicatorRef: RefObject<HTMLElement | null>,
  index: number,
  count: number,
  axis: "x" | "y" = "x",
): void {
  const slot = useRef(0);
  const shown = useRef(index);
  const running = useRef<AnimationPlaybackControls | null>(null);
  const fn = axis === "x" ? "translateX" : "translateY";

  useLayoutEffect(() => {
    const t = trackRef.current;
    const ind = indicatorRef.current;
    if (!t || !ind) return;
    const ro = new ResizeObserver((entries) => {
      const r = entries[0]?.contentRect;
      const len = (axis === "x" ? r?.width : r?.height) ?? 0;
      slot.current = count > 0 ? len / count : 0;
      if (axis === "x") ind.style.width = `${slot.current}px`;
      else ind.style.height = `${slot.current}px`;
      // A resize is not a move: jump, no animation.
      ind.style.transform = `${fn}(${shown.current * slot.current}px)`;
    });
    ro.observe(t);
    return () => ro.disconnect();
  }, [trackRef, indicatorRef, count, axis, fn]);

  useLayoutEffect(() => {
    const ind = indicatorRef.current;
    if (!ind || shown.current === index) return;
    const prev = shown.current;
    shown.current = index;
    const target = `${fn}(${index * slot.current}px)`;
    if (prefersReducedMotion() || slot.current === 0) {
      ind.style.transform = target;
      return;
    }
    // Start from where the pill is now, as a translateX string of the same shape as the target,
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
  }, [indicatorRef, index, axis, fn]);

  useLayoutEffect(
    () => () => {
      running.current?.stop();
      running.current = null;
    },
    [],
  );
}
