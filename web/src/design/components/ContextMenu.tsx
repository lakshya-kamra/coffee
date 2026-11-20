/**
 * Context menus (v4 §2.6, web only): right-click on a row, or its "..." button, opens the row's
 * actions at the pointer (or under the button). A popover with role="menu": focus moves to the
 * first item, Up/Down (wrapping), Home/End, Enter/Space run, Escape and Tab close, and focus goes
 * back to what opened it. Rendered into the open <dialog> when there is one, else <body>, so it is
 * never hidden under the top layer.
 */
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { IconName } from "../icons";
import { IconWell } from "./Buttons";
import { Icon } from "./Icon";
import { Kbd } from "./Kbd";
import s from "./components.module.css";

export interface MenuItem {
  readonly label: string;
  readonly onSelect: () => void;
  readonly icon?: IconName;
  /** Shortcut shown on the right ("e", "delete"). */
  readonly keys?: string;
  readonly danger?: boolean;
  readonly disabled?: boolean;
}

type At = { x: number; y: number } | { el: HTMLElement };

interface OpenState {
  readonly at: At;
  readonly items: readonly MenuItem[];
  readonly opener: HTMLElement | null;
}

export interface ContextMenuApi {
  /** Spread on the row: `onContextMenu={menu.onContextMenu}`. */
  readonly onContextMenu: (e: MouseEvent<HTMLElement>) => void;
  /** Opens under an element (the "..." button). */
  readonly openFrom: (el: HTMLElement) => void;
  /** Opens at a point (client coordinates). */
  readonly openAt: (x: number, y: number) => void;
  readonly close: () => void;
  readonly isOpen: boolean;
  /** Render once, anywhere: `{menu.element}`. */
  readonly element: ReactNode;
}

/**
 * `items` is read when the menu opens (pass a function to build them lazily). `label` names the
 * menu for screen readers ("Actions for Hoffmann V60").
 */
export function useContextMenu(items: readonly MenuItem[] | (() => readonly MenuItem[]), label = "Actions"): ContextMenuApi {
  const [open, setOpen] = useState<OpenState | null>(null);
  const itemsRef = useRef(items);
  useLayoutEffect(() => {
    itemsRef.current = items;
  });
  const resolve = () => (typeof itemsRef.current === "function" ? itemsRef.current() : itemsRef.current);

  const openAt = useCallback((x: number, y: number) => {
    setOpen({ at: { x, y }, items: resolve(), opener: document.activeElement as HTMLElement | null });
  }, []);
  const openFrom = useCallback((el: HTMLElement) => {
    setOpen({ at: { el }, items: resolve(), opener: el });
  }, []);
  const onContextMenu = useCallback(
    (e: MouseEvent<HTMLElement>) => {
      // Shift+right-click keeps the browser's own menu (copy, inspect).
      if (e.shiftKey) return;
      e.preventDefault();
      openAt(e.clientX, e.clientY);
    },
    [openAt],
  );
  const close = useCallback(() => setOpen(null), []);

  const element = open ? <MenuPopover state={open} label={label} onClose={close} /> : null;
  return { onContextMenu, openFrom, openAt, close, isOpen: open != null, element };
}

/** The "..." button that appears on row hover (always focusable). */
export function MenuButton({ label, onOpen, className }: { label: string; onOpen: (el: HTMLElement) => void; className?: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  return (
    <span ref={ref} className={className} style={{ display: "inline-flex" }}>
      <IconWell
        icon="MoreHoriz"
        label={label}
        onClick={() => {
          const btn = ref.current?.querySelector("button");
          if (btn) onOpen(btn);
        }}
      />
    </span>
  );
}

function MenuPopover({ state, label, onClose }: { state: OpenState; label: string; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const id = useId();
  const enabled = state.items.map((it, i) => (it.disabled ? -1 : i)).filter((i) => i >= 0);
  const [active, setActive] = useState(enabled[0] ?? -1);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  const finish = useCallback(
    (restore: boolean) => {
      onClose();
      if (restore && state.opener?.isConnected) state.opener.focus({ preventScroll: true });
    },
    [onClose, state.opener],
  );

  // Position once its size is known, clamped inside the window.
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    let left: number;
    let top: number;
    if ("el" in state.at) {
      const r = state.at.el.getBoundingClientRect();
      left = r.right - w;
      top = r.bottom + 4 + h > vh - 8 ? r.top - 4 - h : r.bottom + 4;
    } else {
      left = state.at.x;
      top = state.at.y + h > vh - 8 ? state.at.y - h : state.at.y;
    }
    setPos({ left: Math.max(8, Math.min(left, vw - w - 8)), top: Math.max(8, Math.min(top, vh - h - 8)) });
  }, [state.at]);

  useEffect(() => {
    if (pos) refs.current[active]?.focus({ preventScroll: true });
    // Focus follows the active item once placed.
  }, [pos, active]);

  // Outside press, scroll, resize or the window losing focus close it.
  useEffect(() => {
    const onDown = (e: PointerEvent) => {
      if (!box.current?.contains(e.target as Node)) finish(false);
    };
    const onAway = () => finish(false);
    document.addEventListener("pointerdown", onDown, { capture: true });
    window.addEventListener("resize", onAway);
    window.addEventListener("blur", onAway);
    window.addEventListener("scroll", onAway, { capture: true, passive: true });
    return () => {
      document.removeEventListener("pointerdown", onDown, { capture: true });
      window.removeEventListener("resize", onAway);
      window.removeEventListener("blur", onAway);
      window.removeEventListener("scroll", onAway, { capture: true });
    };
  }, [finish]);

  const move = (d: number) => {
    if (enabled.length === 0) return;
    const at = enabled.indexOf(active);
    setActive(enabled[(at + d + enabled.length) % enabled.length]!);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    switch (e.key) {
      case "ArrowDown":
        move(1);
        break;
      case "ArrowUp":
        move(-1);
        break;
      case "Home":
        setActive(enabled[0] ?? -1);
        break;
      case "End":
        setActive(enabled[enabled.length - 1] ?? -1);
        break;
      case "Escape":
        finish(true);
        break;
      case "Tab":
        finish(true);
        break;
      default:
        return;
    }
    e.preventDefault();
    e.stopPropagation();
  };

  const host = (("el" in state.at ? state.at.el : state.opener)?.closest("dialog[open]") as HTMLElement | null) ?? document.body;
  return createPortal(
    <div
      ref={box}
      role="menu"
      aria-label={label}
      id={id}
      tabIndex={-1}
      className={s.menu}
      style={pos ? { left: pos.left, top: pos.top } : { left: -9999, top: 0 }}
      onKeyDown={onKeyDown}
    >
      {state.items.map((it, i) => (
        <button
          key={`${i}:${it.label}`}
          ref={(el) => {
            refs.current[i] = el;
          }}
          type="button"
          role="menuitem"
          tabIndex={i === active ? 0 : -1}
          className={`${s.menuItem} t-body-m`}
          data-danger={it.danger || undefined}
          data-active={i === active || undefined}
          disabled={it.disabled}
          onPointerMove={() => !it.disabled && i !== active && setActive(i)}
          onClick={() => {
            finish(true);
            it.onSelect();
          }}
        >
          {it.icon && <Icon name={it.icon} size={20} />}
          <span className={s.menuLabel}>{it.label}</span>
          {it.keys && <Kbd keys={it.keys} />}
        </button>
      ))}
    </div>,
    host,
  );
}
