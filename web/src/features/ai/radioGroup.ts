/**
 * Roving radio groups for the AI settings (v4 §5 A2): the group is one Tab stop (the checked
 * radio, else the first); Up/Down and Left/Right move and check, wrapping at the ends; Home/End
 * jump. Space and Enter on a radio check it (the rows are buttons, so that is their click).
 */
import { useRef, type KeyboardEvent } from "react";

/** Pure key → next index, wrapping (the ARIA radio group pattern); null for other keys. */
export function radioStep(key: string, index: number, count: number): number | null {
  if (count <= 0) return null;
  switch (key) {
    case "ArrowDown":
    case "ArrowRight":
      return (index + 1) % count;
    case "ArrowUp":
    case "ArrowLeft":
      return (index - 1 + count) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}

export interface RadioItemProps {
  ref: (el: HTMLElement | null) => void;
  tabIndex: number;
  onKeyDown: (e: KeyboardEvent<HTMLElement>) => void;
}

/** `checked` is the checked index (-1 for none); `onCheck(i)` checks radio i. */
export function useRadioGroup(count: number, checked: number, onCheck: (index: number) => void): (index: number) => RadioItemProps {
  const els = useRef(new Map<number, HTMLElement>());
  const stop = checked >= 0 && checked < count ? checked : 0;
  return (index) => ({
    ref: (el) => {
      if (el) els.current.set(index, el);
      else els.current.delete(index);
    },
    tabIndex: index === stop ? 0 : -1,
    onKeyDown: (e) => {
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      const next = radioStep(e.key, index, count);
      if (next == null) return;
      e.preventDefault();
      onCheck(next);
      els.current.get(next)?.focus();
    },
  });
}
