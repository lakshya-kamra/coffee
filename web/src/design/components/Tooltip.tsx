/**
 * Tooltips (v4 §2.6, A10): label + shortcut, after 500 ms of mouse hover or at once on keyboard
 * focus. One shared element for the whole app (TooltipLayer, mounted by the shell) driven by
 * `data-tip` attributes, so a hundred icon buttons cost nothing until one is hovered. The
 * accessible name stays the label alone; the shortcut goes to `aria-keyshortcuts`. No `title`
 * attribute alongside (it would show a second, native tooltip).
 */
import { cloneElement, useEffect, useLayoutEffect, useRef, useState, type ReactElement } from "react";
import { ariaKeyShortcuts } from "../keys";
import { Kbd } from "./Kbd";
import s from "./components.module.css";

export const TOOLTIP_DELAY_MS = 500;

export type TooltipSide = "bottom" | "top" | "right";

export interface TooltipAttrs {
  "data-tip": string;
  "data-tip-keys"?: string;
  "data-tip-side"?: TooltipSide;
  "aria-keyshortcuts"?: string;
}

/** Spread onto any focusable element: `<button {...tooltipProps("Pause", "space")}>`. */
export function tooltipProps(label: string, keys?: string, side?: TooltipSide): TooltipAttrs {
  const a: TooltipAttrs = { "data-tip": label };
  if (keys) {
    a["data-tip-keys"] = keys;
    const aks = ariaKeyShortcuts(keys);
    if (aks) a["aria-keyshortcuts"] = aks;
  }
  if (side) a["data-tip-side"] = side;
  return a;
}

/** Wraps one element and adds the tooltip attributes to it. */
export function Tooltip({ label, keys, side, children }: { label: string; keys?: string; side?: TooltipSide; children: ReactElement<Record<string, unknown>> }) {
  return cloneElement(children, { ...tooltipProps(label, keys, side) });
}

interface Shown {
  readonly label: string;
  readonly keys: string | null;
  readonly side: TooltipSide;
  readonly rect: DOMRect;
}

function tipTarget(t: EventTarget | null): HTMLElement | null {
  const el = t as Element | null;
  return (el?.closest?.("[data-tip]") as HTMLElement | null) ?? null;
}

function read(el: HTMLElement): Shown | null {
  const label = el.dataset.tip;
  if (!label) return null;
  const side = (el.dataset.tipSide as TooltipSide | undefined) ?? "bottom";
  return { label, keys: el.dataset.tipKeys ?? null, side, rect: el.getBoundingClientRect() };
}

/** The one tooltip element. Mount once, near the root. */
export function TooltipLayer() {
  const [shown, setShown] = useState<Shown | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    let current: HTMLElement | null = null;
    const clear = () => {
      if (timer) clearTimeout(timer);
      timer = null;
    };
    const hide = () => {
      clear();
      current = null;
      setShown(null);
    };
    const onOver = (e: PointerEvent) => {
      if (e.pointerType !== "mouse" && e.pointerType !== "pen") return;
      const el = tipTarget(e.target);
      if (el === current) return;
      hide();
      if (!el) return;
      current = el;
      timer = setTimeout(() => {
        if (current === el && el.isConnected) setShown(read(el));
      }, TOOLTIP_DELAY_MS);
    };
    const onOut = (e: PointerEvent) => {
      const to = tipTarget(e.relatedTarget);
      if (current && to !== current) hide();
    };
    const onFocus = (e: FocusEvent) => {
      const el = tipTarget(e.target);
      if (!el || el !== e.target) return;
      // Keyboard focus only (a click focuses too, and must not pop a tooltip).
      let visible = false;
      try {
        visible = el.matches(":focus-visible");
      } catch {
        visible = false;
      }
      if (!visible) return;
      clear();
      current = el;
      setShown(read(el));
    };
    const onBlur = (e: FocusEvent) => {
      if (current && e.target === current) hide();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") hide();
    };
    document.addEventListener("pointerover", onOver, { passive: true });
    document.addEventListener("pointerout", onOut, { passive: true });
    document.addEventListener("pointerdown", hide, { capture: true, passive: true });
    document.addEventListener("focusin", onFocus);
    document.addEventListener("focusout", onBlur);
    document.addEventListener("keydown", onKey, { capture: true });
    window.addEventListener("scroll", hide, { capture: true, passive: true });
    return () => {
      clear();
      document.removeEventListener("pointerover", onOver);
      document.removeEventListener("pointerout", onOut);
      document.removeEventListener("pointerdown", hide, { capture: true });
      document.removeEventListener("focusin", onFocus);
      document.removeEventListener("focusout", onBlur);
      document.removeEventListener("keydown", onKey, { capture: true });
      window.removeEventListener("scroll", hide, { capture: true });
    };
  }, []);

  // Place after render, when the tooltip's own size is known; clamp inside the window.
  useLayoutEffect(() => {
    const el = box.current;
    if (!shown || !el) return;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    const r = shown.rect;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    let left: number;
    let top: number;
    if (shown.side === "right") {
      left = r.right + 8;
      top = r.top + r.height / 2 - h / 2;
    } else {
      left = r.left + r.width / 2 - w / 2;
      const below = r.bottom + 8;
      top = shown.side === "top" || below + h > vh - 8 ? r.top - 8 - h : below;
    }
    setPos({ left: Math.max(8, Math.min(left, vw - w - 8)), top: Math.max(8, Math.min(top, vh - h - 8)) });
  }, [shown]);

  if (!shown) return null;
  return (
    <div ref={box} className={s.tooltip} role="tooltip" data-shown={pos != null} style={pos ? { left: pos.left, top: pos.top } : { left: -9999, top: 0 }}>
      <span className="t-label-m">{shown.label}</span>
      {shown.keys && <Kbd keys={shown.keys} />}
    </div>
  );
}
