/**
 * The hot store (web-spec §8.3): immutable snapshots of every table the screens read, loaded
 * before first render and kept current by one Dexie liveQuery per table (which also carries
 * changes made in other browser tabs). Screens read synchronously, so data is there before any
 * transition starts (rule 5). Hooks re-render only when their selected slice changes.
 */
import { liveQuery, type Subscription } from "dexie";
import { useMemo, useSyncExternalStore } from "react";
import { Brewers, catalog } from "@/catalog/catalog";
import { decodeRecipe, type Recipe } from "@/domain/core/recipe";
import { db } from "./db";
import { mergeRecipes, type ActiveBrew } from "./repo";
import type { BeanRow, BrewRow, GrinderRow, KvRow, SavedRecipeRow } from "./schema";
import { KV, withAiDefaults, withSettingsDefaults, type AiPrefs, type Settings } from "./settings";

export interface Snapshot {
  readonly ready: boolean;
  readonly beans: readonly BeanRow[];
  readonly grinders: readonly GrinderRow[];
  /** Newest first. */
  readonly brews: readonly BrewRow[];
  readonly savedRecipes: readonly SavedRecipeRow[];
  /** Built-ins merged with the user's saved recipes (repo.mergeRecipes). */
  readonly recipes: readonly Recipe[];
  readonly settings: Settings;
  readonly aiPrefs: AiPrefs;
  readonly activeBrew: ActiveBrew | null;
}

const EMPTY: Snapshot = Object.freeze({
  ready: false,
  beans: [],
  grinders: [],
  brews: [],
  savedRecipes: [],
  recipes: [],
  settings: withSettingsDefaults(null),
  aiPrefs: withAiDefaults(null),
  activeBrew: null,
});

let snap: Snapshot = EMPTY;
const listeners = new Set<() => void>();
let subs: Subscription[] = [];
let catalogUnsub: (() => void) | null = null;

function emit(next: Snapshot) {
  if (next === snap) return;
  snap = next;
  for (const l of listeners) l();
}

function decodeSaved(rows: readonly SavedRecipeRow[]): Recipe[] {
  const out: Recipe[] = [];
  for (const row of rows) {
    const r = decodeRecipe(row.recipe, Brewers.resolve, false);
    if (r.ok) out.push({ ...r.value, isBuiltIn: false });
  }
  return out;
}

let savedDecodedFor: readonly SavedRecipeRow[] | null = null;
let savedDecodedBrewers: readonly unknown[] | null = null;
let savedDecoded: Recipe[] = [];
let mergedFor: { builtIns: readonly Recipe[]; saved: Recipe[] } | null = null;
let merged: readonly Recipe[] = [];

/** Memoised by input identity (audit rank 10: no derivation work on unrelated changes). */
function recipesFor(savedRows: readonly SavedRecipeRow[]): readonly Recipe[] {
  // Saved recipes name their brewer by id; decode again once brewers.json has loaded.
  if (savedRows !== savedDecodedFor || catalog.brewers !== savedDecodedBrewers) {
    savedDecodedFor = savedRows;
    savedDecodedBrewers = catalog.brewers;
    savedDecoded = decodeSaved(savedRows);
  }
  const builtIns = catalog.recipes;
  if (!mergedFor || mergedFor.builtIns !== builtIns || mergedFor.saved !== savedDecoded) {
    mergedFor = { builtIns, saved: savedDecoded };
    merged = Object.freeze(mergeRecipes(builtIns, savedDecoded));
  }
  return merged;
}

function same(a: unknown, b: unknown): boolean {
  return a === b || JSON.stringify(a) === JSON.stringify(b);
}

/** kv rows → settings / ai / activeBrew, keeping each object's identity when its value is unchanged. */
function applyKv(rows: readonly KvRow[], base: Snapshot): Snapshot {
  const byKey = new Map(rows.map((r) => [r.key, r.value]));
  const settings = withSettingsDefaults(byKey.get(KV.settings));
  const ai = withAiDefaults(byKey.get(KV.ai));
  const active = (byKey.get(KV.activeBrew) as ActiveBrew | undefined) ?? null;
  const next = {
    ...base,
    settings: same(settings, base.settings) ? base.settings : settings,
    aiPrefs: same(ai, base.aiPrefs) ? base.aiPrefs : ai,
    activeBrew: same(active, base.activeBrew) ? base.activeBrew : active,
  };
  return next.settings === base.settings && next.aiPrefs === base.aiPrefs && next.activeBrew === base.activeBrew ? base : next;
}

async function readAll(): Promise<Snapshot> {
  const [beans, grinders, brews, savedRecipes, kv] = await Promise.all([
    db.beans.toArray(),
    db.grinders.toArray(),
    db.brew_logs.orderBy("startedAt").reverse().toArray(),
    db.saved_recipes.toArray(),
    db.kv.toArray(),
  ]);
  const base: Snapshot = { ...EMPTY, ready: true, beans, grinders, brews, savedRecipes, recipes: recipesFor(savedRecipes) };
  return applyKv(kv, base);
}

function watch<T>(query: () => Promise<T>, apply: (value: T) => void): Subscription {
  return liveQuery(query).subscribe({ next: apply, error: (e: unknown) => console.error("store: live query failed", e) });
}

/** One level deep: rows hold primitives plus small arrays/objects (descriptors, taste, recipe JSON). */
function sameRow(a: object, b: object): boolean {
  const ka = Object.keys(a);
  if (ka.length !== Object.keys(b).length) return false;
  for (const k of ka) {
    const x = (a as Record<string, unknown>)[k];
    const y = (b as Record<string, unknown>)[k];
    if (Object.is(x, y)) continue;
    if (x == null || y == null || typeof x !== "object" || typeof y !== "object" || JSON.stringify(x) !== JSON.stringify(y)) return false;
  }
  return true;
}

/**
 * Live queries hand back fresh row objects every time. Reuse the previous object for each row
 * whose content is unchanged, and the previous array when nothing changed, so memoised rows and
 * slices keep their identity (the first emission after init() changes nothing at all).
 */
function reconcile<T extends { id?: number | string }>(prev: readonly T[], next: T[]): readonly T[] {
  const byId = new Map<unknown, T>();
  for (const r of prev) byId.set(r.id, r);
  let changed = prev.length !== next.length;
  const out = next.map((r, i) => {
    const old = byId.get(r.id);
    if (old !== undefined && sameRow(old, r)) {
      if (prev[i] !== old) changed = true;
      return old;
    }
    changed = true;
    return r;
  });
  return changed ? out : prev;
}

export const store = {
  get: (): Snapshot => snap,
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },

  /** Loads every table before first render, then keeps the snapshot live. */
  async init(): Promise<void> {
    emit(await readAll());
    subs.forEach((s) => s.unsubscribe());
    subs = [
      watch(() => db.beans.toArray(), (rows) => {
        const beans = reconcile(snap.beans, rows);
        if (beans !== snap.beans) emit({ ...snap, beans });
      }),
      watch(() => db.grinders.toArray(), (rows) => {
        const grinders = reconcile(snap.grinders, rows);
        if (grinders !== snap.grinders) emit({ ...snap, grinders });
      }),
      watch(() => db.brew_logs.orderBy("startedAt").reverse().toArray(), (rows) => {
        const brews = reconcile(snap.brews, rows);
        if (brews !== snap.brews) emit({ ...snap, brews });
      }),
      watch(() => db.saved_recipes.toArray(), (rows) => {
        const savedRecipes = reconcile(snap.savedRecipes, rows);
        if (savedRecipes !== snap.savedRecipes) emit({ ...snap, savedRecipes, recipes: recipesFor(savedRecipes) });
      }),
      watch(() => db.kv.toArray(), (rows) => emit(applyKv(rows, snap))),
    ];
    // Built-in recipes arrive with the catalog; re-merge when it changes.
    catalogUnsub?.();
    catalogUnsub = catalog.subscribe(() => {
      const recipes = recipesFor(snap.savedRecipes);
      if (recipes !== snap.recipes) emit({ ...snap, recipes });
    });
  },

  /** Stops live queries (tests, and the versionchange handler before closing the db). */
  stop(): void {
    subs.forEach((s) => s.unsubscribe());
    subs = [];
    catalogUnsub?.();
    catalogUnsub = null;
  },

  /** Test hook. */
  resetForTests(): void {
    store.stop();
    savedDecodedFor = null;
    mergedFor = null;
    snap = EMPTY;
  },
};

/**
 * Subscribe to a slice. `isEqual` decides whether a new slice is "the same" so the component
 * skips the render; a change of `key` rebuilds the selector (e.g. the uid an inline selector
 * closes over).
 */
export function useStore<T>(selector: (s: Snapshot) => T, isEqual: (a: T, b: T) => boolean = Object.is, key: unknown = null): T {
  // The selector is rebuilt only when `key` changes; inline lambdas are fine.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const getSelected = useMemo(() => cachedSelector(selector, isEqual), [key]);
  return useSyncExternalStore(store.subscribe, getSelected, getSelected);
}

/** A getSnapshot that returns the same value (by `isEqual`) until the slice really changes. */
function cachedSelector<T>(selector: (s: Snapshot) => T, isEqual: (a: T, b: T) => boolean): () => T {
  let last: { s: Snapshot; v: T } | null = null;
  return () => {
    const s = snap;
    if (last && last.s === s) return last.v;
    const v = selector(s);
    last = { s, v: last && isEqual(last.v, v) ? last.v : v };
    return last.v;
  };
}

export const useSettings = (): Settings => useStore((s) => s.settings);
export const useAiPrefs = (): AiPrefs => useStore((s) => s.aiPrefs);
export const useActiveBrew = (): ActiveBrew | null => useStore((s) => s.activeBrew);
export const useRecipes = (): readonly Recipe[] => useStore((s) => s.recipes);
export const useBrews = (): readonly BrewRow[] => useStore((s) => s.brews);

export function useBeans(opts: { includeArchived?: boolean } = {}): readonly BeanRow[] {
  const all = useStore((s) => s.beans);
  return useMemo(() => (opts.includeArchived ? all : all.filter((b) => !b.isArchived)), [all, opts.includeArchived]);
}

export function useGrinders(opts: { includeArchived?: boolean } = {}): readonly GrinderRow[] {
  const all = useStore((s) => s.grinders);
  return useMemo(() => (opts.includeArchived ? all : all.filter((g) => !g.isArchived)), [all, opts.includeArchived]);
}

/** Synchronous: a detail's first frame already has its recipe (as Android's RecipeDetailViewModel). */
export function useRecipe(id: string): Recipe | null {
  return useStore((s) => s.recipes.find((r) => r.id === id) ?? null, same, id);
}

/** Live queries hand back fresh row objects; equal content keeps the old one so nothing re-renders. */
export function useBrew(uid: string): BrewRow | null {
  return useStore((s) => s.brews.find((b) => b.uid === uid) ?? null, same, uid);
}

export function getSnapshot(): Snapshot {
  return snap;
}
