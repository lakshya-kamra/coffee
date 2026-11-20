/**
 * Layout reads happen only in ResizeObserver callbacks (rule 8). One shared observer serves every
 * drawing that needs its width, so a list of cards costs one observer, not one per card.
 */
import { useEffect, useState, type RefObject } from "react";

type Cb = (width: number, height: number) => void;
const callbacks = new WeakMap<Element, Cb>();
let shared: ResizeObserver | null = null;
/**
 * Sizes that arrived while their keep-alive tab was hidden. A window resize used to re-render
 * every journey line in all five tabs (§2.9.2: the only resize work is the matchMedia
 * callbacks); now a hidden tab keeps just the last size and applies it once when it is shown.
 */
const deferred = new Map<Element, readonly [number, number]>();

function observer(): ResizeObserver | null {
  if (shared) return shared;
  if (typeof ResizeObserver === "undefined") return null;
  shared = new ResizeObserver((entries) => {
    for (const e of entries) {
      const box = e.contentBoxSize?.[0];
      const w = box ? box.inlineSize : e.contentRect.width;
      const h = box ? box.blockSize : e.contentRect.height;
      if (e.target.closest("[data-hidden]")) deferred.set(e.target, [w, h]);
      else {
        deferred.delete(e.target);
        callbacks.get(e.target)?.(w, h);
      }
    }
  });
  return shared;
}

/** A hidden layer is being shown: hand its drawings the sizes they missed while it was hidden. */
export function flushDeferredSizes(layer: Element): void {
  if (deferred.size === 0) return;
  for (const [el, [w, h]] of deferred) {
    if (!layer.contains(el)) continue;
    deferred.delete(el);
    callbacks.get(el)?.(w, h);
  }
}

export function observeSize(el: Element, cb: Cb): () => void {
  const ro = observer();
  if (!ro) return () => undefined;
  callbacks.set(el, cb);
  ro.observe(el);
  return () => {
    ro.unobserve(el);
    callbacks.delete(el);
    deferred.delete(el);
  };
}

/** The element's content width in CSS px (0 until first measured). Re-renders only when it changes by ≥ 0.5 px. */
export function useWidth(ref: RefObject<HTMLElement | null>): number {
  const [w, setW] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    return observeSize(el, (width) => setW((old) => (Math.abs(old - width) < 0.5 ? old : width)));
  }, [ref]);
  return w;
}

/**
 * Resolves a CSS colour that may be a custom property ("var(--crema)") to a concrete value a
 * canvas can use, against `el`'s computed style.
 */
export function resolveColor(el: Element, value: string): string {
  const m = /^var\((--[\w-]+)\)$/.exec(value.trim());
  if (!m) return value;
  return getComputedStyle(el).getPropertyValue(m[1]!).trim() || "transparent";
}

/** Calls `cb` whenever <html data-theme> changes (theme switch), so drawings re-resolve colours. */
export function onThemeChange(cb: () => void): () => void {
  if (typeof MutationObserver === "undefined") return () => undefined;
  const mo = new MutationObserver(cb);
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  return () => mo.disconnect();
}
