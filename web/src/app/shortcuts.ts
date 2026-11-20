/**
 * Keyboard shortcuts (v4 §2.7). Screens declare what is live with
 * `useShortcuts(scope, bindings, enabled)`; one window listener dispatches, the most recently
 * registered scope first, so a screen's `b` or Escape wins over the shell's. Bindings carry a
 * label and a group, so the `?` dialog lists exactly what is live.
 *
 * Key spelling: steps separated by spaces form a chord ("g t", 1 s window); inside a step,
 * modifiers joined with "+" ("mod+k", "ctrl+\\", "shift+d"). `mod` is Ctrl or Cmd. Letters
 * never match with Shift held unless the step says shift; punctuation ignores Shift ("?").
 *
 * Never while typing in a field (except Ctrl/Cmd combos, Escape and `whileTyping` bindings);
 * while a dialog is open only `inDialog` bindings run (the dialog handles its own keys);
 * nothing runs while focus is inside an open menu or popover, which handle their own keys.
 */
import { useEffect, useRef } from "react";

export interface Binding {
  /** "mod+k", "/", "g t", "[", "escape", "1", "arrowdown". */
  readonly keys: string;
  readonly label: string;
  /** "Navigation", "Lists", "Brewing", "General"… the `?` dialog's headings. */
  readonly group: string;
  readonly run: (e: KeyboardEvent) => void;
  readonly whileTyping?: boolean;
  readonly inDialog?: boolean;
  /** Left out of the `?` dialog (aliases such as J next to Down). */
  readonly hidden?: boolean;
  /** What the dialog prints instead of `keys` ("1-5"). */
  readonly display?: string;
}

export interface KeyStep {
  readonly key: string;
  readonly mod: boolean;
  readonly ctrl: boolean;
  readonly meta: boolean;
  readonly alt: boolean;
  readonly shift: boolean;
}

/** The subset of KeyboardEvent the engine reads (tests pass plain objects). */
export interface KeyEventLike {
  readonly key: string;
  readonly ctrlKey: boolean;
  readonly metaKey: boolean;
  readonly altKey: boolean;
  readonly shiftKey: boolean;
}

const ALIASES: Record<string, string> = {
  esc: "escape",
  space: " ",
  spacebar: " ",
  up: "arrowup",
  down: "arrowdown",
  left: "arrowleft",
  right: "arrowright",
  del: "delete",
  return: "enter",
  plus: "+",
};

function normKey(k: string): string {
  const l = k.toLowerCase();
  return ALIASES[l] ?? l;
}

export function parseStep(step: string): KeyStep {
  const tokens = step === "+" ? ["+"] : step.split("+");
  // "mod++" (a literal plus) leaves an empty last token.
  if (tokens.length > 1 && tokens[tokens.length - 1] === "") tokens.splice(-2, 2, "+");
  const key = normKey(tokens[tokens.length - 1] ?? "");
  const mods = new Set(tokens.slice(0, -1).map((t) => t.toLowerCase()));
  return {
    key,
    mod: mods.has("mod"),
    ctrl: mods.has("ctrl") || mods.has("control"),
    meta: mods.has("meta") || mods.has("cmd"),
    alt: mods.has("alt") || mods.has("option"),
    shift: mods.has("shift"),
  };
}

export function parseKeys(keys: string): KeyStep[] {
  return keys
    .trim()
    .split(/\s+/)
    .filter((s) => s !== "")
    .map(parseStep);
}

export function matchesStep(s: KeyStep, e: KeyEventLike): boolean {
  if (normKey(e.key) !== s.key) return false;
  if (s.mod) {
    if (!(e.ctrlKey || e.metaKey)) return false;
  } else {
    if (s.ctrl !== e.ctrlKey) return false;
    if (s.meta !== e.metaKey) return false;
  }
  if (s.alt !== e.altKey) return false;
  if (s.shift) return e.shiftKey;
  // Shift+J is not J; "?" is Shift+/ on most layouts and must still match "?".
  if (/^[a-z]$/.test(s.key) && e.shiftKey) return false;
  return true;
}

/** Combos that may run while typing: anything with Ctrl/Cmd, and Escape. */
export function allowedWhileTyping(steps: readonly KeyStep[]): boolean {
  const first = steps[0];
  return steps.length === 1 && first != null && (first.mod || first.ctrl || first.meta || first.key === "escape");
}

export const CHORD_WINDOW_MS = 1000;

interface Entry {
  readonly id: number;
  readonly scope: string;
  bindings: readonly Binding[];
  parsed: readonly (readonly KeyStep[])[];
  enabled: boolean;
}

export interface KeyContext {
  readonly typing: boolean;
  readonly dialogOpen: boolean;
  readonly now: number;
}

/** The dispatcher, separate from the DOM so it can be tested. */
export class ShortcutEngine {
  private entries: Entry[] = [];
  private seq = 0;
  private pending: { at: number; first: KeyEventLike } | null = null;
  private readonly listeners = new Set<() => void>();

  register(scope: string, bindings: readonly Binding[], enabled: boolean): number {
    const id = ++this.seq;
    this.entries.push({ id, scope, bindings, parsed: bindings.map((b) => parseKeys(b.keys)), enabled });
    this.emit();
    return id;
  }

  update(id: number, bindings: readonly Binding[], enabled: boolean): void {
    const e = this.entries.find((x) => x.id === id);
    if (!e) return;
    const changed = e.enabled !== enabled || e.bindings !== bindings;
    e.bindings = bindings;
    e.parsed = bindings.map((b) => parseKeys(b.keys));
    e.enabled = enabled;
    if (changed) this.emit();
  }

  unregister(id: number): void {
    this.entries = this.entries.filter((x) => x.id !== id);
    this.emit();
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit() {
    for (const l of this.listeners) l();
  }

  /** Enabled bindings, newest scope first, one per key spelling (the one that would run). */
  live(): Binding[] {
    const seen = new Set<string>();
    const out: Binding[] = [];
    for (let i = this.entries.length - 1; i >= 0; i--) {
      const e = this.entries[i]!;
      if (!e.enabled) continue;
      for (const b of e.bindings) {
        const k = b.keys.trim().toLowerCase();
        if (seen.has(k)) continue;
        seen.add(k);
        out.push(b);
      }
    }
    return out;
  }

  private permitted(b: Binding, steps: readonly KeyStep[], ctx: KeyContext): boolean {
    if (ctx.dialogOpen && !b.inDialog) return false;
    if (ctx.typing && !b.whileTyping && !allowedWhileTyping(steps)) return false;
    return true;
  }

  /**
   * Handles one keydown. Returns the binding that ran, "pending" when it started a chord, or
   * null. The caller prevents the default for anything but null.
   */
  handle(e: KeyEventLike, ctx: KeyContext): Binding | "pending" | null {
    const pend = this.pending;
    this.pending = null;
    // The second key of a chord.
    if (pend && ctx.now - pend.at <= CHORD_WINDOW_MS) {
      for (let i = this.entries.length - 1; i >= 0; i--) {
        const en = this.entries[i]!;
        if (!en.enabled) continue;
        for (let j = 0; j < en.bindings.length; j++) {
          const steps = en.parsed[j]!;
          const b = en.bindings[j]!;
          if (steps.length === 2 && matchesStep(steps[0]!, pend.first) && matchesStep(steps[1]!, e) && this.permitted(b, steps, ctx)) {
            b.run(e as KeyboardEvent);
            return b;
          }
        }
      }
    }
    // A single-step binding, newest scope first.
    let startsChord = false;
    for (let i = this.entries.length - 1; i >= 0; i--) {
      const en = this.entries[i]!;
      if (!en.enabled) continue;
      for (let j = 0; j < en.bindings.length; j++) {
        const steps = en.parsed[j]!;
        const b = en.bindings[j]!;
        if (!this.permitted(b, steps, ctx)) continue;
        if (steps.length === 1 && matchesStep(steps[0]!, e)) {
          b.run(e as KeyboardEvent);
          return b;
        }
        if (steps.length === 2 && matchesStep(steps[0]!, e)) startsChord = true;
      }
    }
    if (startsChord) {
      this.pending = { at: ctx.now, first: { key: e.key, ctrlKey: e.ctrlKey, metaKey: e.metaKey, altKey: e.altKey, shiftKey: e.shiftKey } };
      return "pending";
    }
    return null;
  }

  /** Test hook. */
  reset(): void {
    this.entries = [];
    this.pending = null;
    this.emit();
  }
}

export const shortcuts = new ShortcutEngine();

// ---------------------------------------------------------------- DOM glue

export function isTypingTarget(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  if (!el || typeof el.tagName !== "string") return false;
  if (el.isContentEditable) return true;
  if (el.tagName === "TEXTAREA" || el.tagName === "SELECT") return true;
  if (el.tagName !== "INPUT") return false;
  const type = (el as HTMLInputElement).type;
  // Buttons, checkboxes, radios and ranges take no text: shortcuts still work on them.
  return !["button", "submit", "reset", "checkbox", "radio", "range", "color", "file", "image"].includes(type);
}

function inOwnKeyHandler(t: EventTarget | null): boolean {
  const el = t as Element | null;
  return !!el?.closest?.('[role="menu"], [data-popover]');
}

let started = false;

/** Installs the one window listener (idempotent). App calls it. */
export function startShortcuts(): () => void {
  if (started) return () => undefined;
  started = true;
  const onKey = (e: KeyboardEvent) => {
    // Held keys repeat on purpose: holding Down walks a list.
    if (e.defaultPrevented || e.isComposing) return;
    if (inOwnKeyHandler(e.target)) return;
    const res = shortcuts.handle(e, { typing: isTypingTarget(e.target), dialogOpen: document.querySelector("dialog[open]") != null, now: performance.now() });
    if (res) e.preventDefault();
  };
  window.addEventListener("keydown", onKey);
  return () => {
    window.removeEventListener("keydown", onKey);
    started = false;
  };
}

/**
 * Registers `bindings` while mounted. Pass a new array to update them (memoise it if it is large);
 * `enabled` false keeps the scope's place in the order but runs and lists nothing.
 */
export function useShortcuts(scope: string, bindings: readonly Binding[], enabled = true): void {
  const id = useRef<number | null>(null);
  useEffect(() => {
    id.current = shortcuts.register(scope, bindings, enabled);
    return () => {
      if (id.current != null) shortcuts.unregister(id.current);
      id.current = null;
    };
    // Registered once per scope; later changes go through update() below, keeping the order.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope]);
  useEffect(() => {
    if (id.current != null) shortcuts.update(id.current, bindings, enabled);
  }, [bindings, enabled]);
}

/** Groups for the `?` dialog, in a fixed order with anything else after. */
export const GROUP_ORDER: readonly string[] = ["General", "Navigation", "Lists", "Brewing", "Review", "Editing"];

export function groupedLive(list: readonly Binding[] = shortcuts.live()): { group: string; bindings: Binding[] }[] {
  const by = new Map<string, Binding[]>();
  for (const b of list) {
    if (b.hidden) continue;
    const arr = by.get(b.group) ?? [];
    arr.push(b);
    by.set(b.group, arr);
  }
  const rank = (g: string) => {
    const i = GROUP_ORDER.indexOf(g);
    return i < 0 ? GROUP_ORDER.length : i;
  };
  return [...by.entries()].sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0])).map(([group, bindings]) => ({ group, bindings }));
}
