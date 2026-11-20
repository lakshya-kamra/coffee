/**
 * The inline editor's rules at expanded+ (v4 §3.10): the detail pane is the editor, so moving the
 * selection away from unsaved changes asks "Discard changes?" first ("Keep editing" is the safe,
 * focused answer). Pure: GearScreen keeps the state, the tests drive it directly.
 */

/** Two rows hold the same edit. `updatedAt` is bookkeeping, never a change you made. */
export function rowsDiffer<T extends object>(a: T, b: T): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const k of keys) {
    if (k === "updatedAt") continue;
    const x = (a as Record<string, unknown>)[k];
    const y = (b as Record<string, unknown>)[k];
    if (x === y) continue;
    // Arrays (varietal ids) and plain values only; rows hold nothing deeper.
    if (Array.isArray(x) && Array.isArray(y) && x.length === y.length && x.every((v, i) => v === y[i])) continue;
    if ((x === undefined || x === null) && (y === undefined || y === null)) continue;
    return true;
  }
  return false;
}

/** The pane's selection: a row key (a uid) or null for the default. */
export interface InlineSelection {
  readonly selected: string | null;
  /** A move that waits on "Discard changes?"; undefined when nothing is asked. */
  readonly pending?: string | null;
}

/**
 * Asks to move the selection to `to`. With unsaved changes on the current row the move waits
 * (pending) for an answer; otherwise it happens at once. Selecting the current row is a no-op.
 */
export function requestSelect(s: InlineSelection, to: string | null, dirty: boolean): InlineSelection {
  if (to === s.selected) return s.pending === undefined ? s : { selected: s.selected };
  if (dirty) return { selected: s.selected, pending: to };
  return { selected: to };
}

/** "Discard" moves on (the caller drops the draft); "Keep editing" stays put. */
export function answerDiscard(s: InlineSelection, discard: boolean): InlineSelection {
  if (s.pending === undefined) return s;
  return discard ? { selected: s.pending } : { selected: s.selected };
}

/** A confirm is showing. */
export function isAsking(s: InlineSelection): boolean {
  return s.pending !== undefined;
}

/**
 * Drafts kept per row while you look elsewhere (another Gear section, another tab, a resize
 * across 840), so typed text is never lost (§2.10). Keyed by row uid.
 */
export class Drafts<T extends object> {
  private readonly map = new Map<string, T>();

  get(key: string | null): T | undefined {
    return key == null ? undefined : this.map.get(key);
  }

  /** Stores the draft, or forgets it when it matches the saved row again. */
  put(key: string, draft: T, saved: T | null): void {
    if (saved != null && !rowsDiffer(draft, saved)) this.map.delete(key);
    else this.map.set(key, draft);
  }

  drop(key: string | null): void {
    if (key != null) this.map.delete(key);
  }

  dirty(key: string | null): boolean {
    return key != null && this.map.has(key);
  }
}
