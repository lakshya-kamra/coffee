/**
 * One indicator that slides between equal-width slots with `transform` and spatialDefault
 * (bottom bar pill, SegmentedControl). Width is measured only in a ResizeObserver callback
 * (rule 8); the slide itself runs on the compositor (whole transform string, no onUpdate).
 */
import { animate, type AnimationPlaybackControls } from "motion";
import { useLayoutEffect, useRef, type RefObject } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { spatialDefault } from "./motion";

/** The element's current horizontal translation in px (works mid-animation). */
export function currentTranslateX(el: HTMLElement): number {
  const t = getComputedStyle(el).transform;
  if (!t || t === "none") return 0;
  try {
    return new DOMMatrixReadOnly(t).m41;
  } catch {
    return 0;
  }
}

export function useSlidingIndicator(
  trackRef: RefObject<HTMLElement | null>,
  indicatorRef: RefObject<HTMLElement | null>,
  index: number,
  count: number,
): void {
  const slot = useRef(0);
  const shown = useRef(index);
  const running = useRef<AnimationPlaybackControls | null>(null);

  useLayoutEffect(() => {
    const t = trackRef.current;
    const ind = indicatorRef.current;
    if (!t || !ind) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width ?? 0;
      slot.current = count > 0 ? w / count : 0;
      ind.style.width = `${slot.current}px`;
      // A resize is not a move: jump, no animation.
      ind.style.transform = `translateX(${shown.current * slot.current}px)`;
    });
    ro.observe(t);
    return () => ro.disconnect();
  }, [trackRef, indicatorRef, count]);

  useLayoutEffect(() => {
    const ind = indicatorRef.current;
    if (!ind || shown.current === index) return;
    const prev = shown.current;
    shown.current = index;
    const target = `translateX(${index * slot.current}px)`;
    if (prefersReducedMotion() || slot.current === 0) {
      ind.style.transform = target;
      return;
    }
    // Start from where the pill is now, as a translateX string of the same shape as the target,
    // so WAAPI interpolates one function rather than a matrix. A settled pill sits on its slot,
    // which is known without asking the DOM: reading computed style here runs in the same task
    // as a tab switch and forces a whole-document style pass before the frame even starts
    // (about 10 ms of a 4×-throttled CPU profile per switch). Only a pill caught mid-flight is read.
    const x = running.current ? currentTranslateX(ind) : prev * slot.current;
    running.current?.stop();
    const controls = animate(ind, { transform: [`translateX(${x}px)`, target] }, spatialDefault);
    running.current = controls;
    void controls.then(() => {
      if (running.current === controls) running.current = null;
    });
    return undefined;
  }, [indicatorRef, index]);

  useLayoutEffect(
    () => () => {
      running.current?.stop();
      running.current = null;
    },
    [],
  );
}
