/**
 * Roving-tabindex lists (v4 §2.6, §2.3 rule 5): Tab enters the list once (on the selected row,
 * else the first), Up/Down and J/K move, Home/End jump, Enter opens. At expanded and up the screen
 * passes `onSettle`, called once focus has rested `settleMs` (120) on a row, to select it into the
 * pane with `replace` (rule 2) without re-rendering the detail on every key repeat.
 */
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";

export const SETTLE_MS = 120;

/** Pure key -> next index (null: not a list key). Exported for tests. */
export function rovingNext(key: string, index: number, count: number): number | null {
  if (count <= 0) return null;
  switch (key) {
    case "ArrowDown":
    case "j":
      return Math.min(count - 1, index + 1);
    case "ArrowUp":
    case "k":
      return Math.max(0, index - 1);
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}

export interface RovingItemProps {
  ref: (el: HTMLElement | null) => void;
  tabIndex: number;
  onKeyDown: (e: KeyboardEvent<HTMLElement>) => void;
  onFocus: () => void;
  "data-roving-index": number;
}

export interface RovingList {
  /** The row that holds the tab stop. */
  readonly activeIndex: number;
  /** Spread on each row: `<button {...list.itemProps(i)}>`. */
  readonly itemProps: (index: number) => RovingItemProps;
  /** Moves focus to a row (and the tab stop with it). */
  readonly focusIndex: (index: number) => void;
}

export function useRovingList({
  count,
  selectedIndex = -1,
  onOpen,
  onSettle,
  settleMs = SETTLE_MS,
}: {
  count: number;
  /** The selected row (aria-current); the tab stop follows it when it changes from outside. */
  selectedIndex?: number;
  /** Enter on a row. Leave unset when rows are buttons or links that open on Enter themselves. */
  onOpen?: (index: number) => void;
  /** Focus has rested on a row (keyboard movement only). */
  onSettle?: (index: number) => void;
  settleMs?: number;
}): RovingList {
  const [active, setActive] = useState(selectedIndex >= 0 ? selectedIndex : 0);
  const [seenSelected, setSeenSelected] = useState(selectedIndex);
  // Follow a selection made elsewhere (a click, the URL), React's adjust-state-while-rendering pattern.
  if (seenSelected !== selectedIndex) {
    setSeenSelected(selectedIndex);
    if (selectedIndex >= 0) setActive(selectedIndex);
  }
  const clamped = count === 0 ? 0 : Math.min(Math.max(active, 0), count - 1);

  const els = useRef(new Map<number, HTMLElement>());
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const settleRef = useRef(onSettle);
  const openRef = useRef(onOpen);
  useEffect(() => {
    settleRef.current = onSettle;
    openRef.current = onOpen;
  });
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const focusIndex = useCallback((i: number) => {
    setActive(i);
    const el = els.current.get(i);
    el?.focus({ preventScroll: true });
    el?.scrollIntoView({ block: "nearest" });
  }, []);

  const itemProps = (index: number): RovingItemProps => ({
    ref: (el) => {
      if (el) els.current.set(index, el);
      else els.current.delete(index);
    },
    tabIndex: index === clamped ? 0 : -1,
    "data-roving-index": index,
    onFocus: () => {
      if (index !== clamped) setActive(index);
    },
    onKeyDown: (e) => {
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === "Enter" && openRef.current) {
        e.preventDefault();
        openRef.current(index);
        return;
      }
      // J/K only without Shift, so Shift+K stays free.
      if ((e.key === "j" || e.key === "k") && e.shiftKey) return;
      const next = rovingNext(e.key, index, count);
      if (next == null) return;
      e.preventDefault();
      focusIndex(next);
      if (settleRef.current) {
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(() => {
          timer.current = null;
          settleRef.current?.(next);
        }, settleMs);
      }
    },
  });

  return { activeIndex: clamped, itemProps, focusIndex };
}
