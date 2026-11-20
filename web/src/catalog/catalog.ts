/**
 * The app-wide catalog registry (A§2.3 `Catalog`, web form). Lists are frozen arrays, empty until
 * their file is loaded; lookups are Maps built lazily. `load()` is idempotent and concurrent-safe.
 * Brewers and recipes load before first render (boot), the rest in idle time or on demand.
 *
 * A file that fails to load (offline before the service worker has it, a deploy race) is not
 * "loaded": `failed(name)` says so, the next `load()` tries again, and coming back online retries
 * by itself. An empty list is never passed off as the real one.
 */
import { LegacyIds } from "@/domain/core/legacyIds";
import { brewMethodOf, makeBrewer, resolveBrewerIn, unknownBrewer, type Brewer } from "@/domain/core/brewer";
import { decodeRecipe, type Recipe } from "@/domain/core/recipe";
import * as N from "./normalise";
import { readShared, sourcePath } from "./sources";
import {
  CATALOG_NAMES,
  type BeanVocabulary,
  type CatalogGrinder,
  type CatalogName,
  type CatalogOrigin,
  type CatalogProvider,
  type CatalogRoaster,
  type CatalogWater,
  type GlossaryTerm,
  type Principle,
  type ProcessTerm,
  type TermKind,
  type VarietalTerm,
} from "./types";

const EMPTY_VOCAB: BeanVocabulary = Object.freeze({
  species: [], processes: [], varietals: [], lotTypes: [], decafMethods: [], roastLevels: [], roastIntents: [],
});

interface State {
  grinders: readonly CatalogGrinder[];
  roasters: readonly CatalogRoaster[];
  origins: readonly CatalogOrigin[];
  beans: BeanVocabulary;
  brewers: readonly Brewer[];
  water: readonly CatalogWater[];
  providers: readonly CatalogProvider[];
  recipes: readonly Recipe[];
  principles: readonly Principle[];
  glossary: readonly GlossaryTerm[];
}

const state: State = {
  grinders: [], roasters: [], origins: [], beans: EMPTY_VOCAB, brewers: [], water: [], providers: [], recipes: [], principles: [], glossary: [],
};
const problemsByFile = new Map<CatalogName, readonly string[]>();
const loaded = new Set<CatalogName>();
/** Files whose last load attempt failed, with the reason. Cleared when a retry succeeds. */
const failedFiles = new Map<CatalogName, string>();
const inflight = new Map<CatalogName, Promise<void>>();
const maps = new Map<string, Map<string, unknown>>();
const listeners = new Set<() => void>();
let version = 0;

function notify() {
  version++;
  maps.clear();
  for (const l of listeners) l();
}

function lookup<T extends { id: string }>(key: string, list: readonly T[], id: string | null | undefined): T | null {
  if (!id) return null;
  let m = maps.get(key) as Map<string, T> | undefined;
  if (!m) {
    m = new Map(list.map((x) => [x.id, x]));
    maps.set(key, m);
  }
  return m.get(id) ?? null;
}

function toBrewers(items: readonly N.CatalogBrewerRaw[]): readonly Brewer[] {
  const list: Brewer[] = items.map((b) =>
    makeBrewer({ id: b.id, label: b.name, brand: b.brand, category: b.category, method: brewMethodOf(b.method), filter: b.filter, notes: b.notes }),
  );
  // 1.x data must always resolve, even if brewers.json loses a legacy id.
  for (const f of LegacyIds.FALLBACK_BREWERS) if (!list.some((b) => b.id === f.id)) list.push(f);
  return Object.freeze(list);
}

async function loadOne(name: CatalogName): Promise<void> {
  const file = sourcePath(name);
  const raw = await readShared(name);
  switch (name) {
    case "grinders": {
      const p = N.parseList(file, raw, "grinders", N.grinder);
      state.grinders = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "roasters": {
      const p = N.parseList(file, raw, "roasters", N.roaster);
      state.roasters = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "origins": {
      const p = N.parseList(file, raw, "origins", N.origin);
      state.origins = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "water": {
      const p = N.parseList(file, raw, "water", N.water);
      state.water = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "providers": {
      const p = N.parseList(file, raw, "providers", N.provider);
      state.providers = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "principles": {
      // Optional until Android WP-3 writes it: absence is not a problem.
      const p = raw === undefined ? { items: [], problems: [] } : N.parseList(file, raw, "principles", N.principle);
      state.principles = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "glossary": {
      const p = N.parseList(file, raw, "terms", N.glossaryTerm);
      state.glossary = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "beans": {
      const p = N.parseBeans(file, raw);
      state.beans = p.vocab;
      problemsByFile.set(name, p.problems);
      break;
    }
    case "brewers": {
      const p = N.parseList(file, raw, "brewers", N.brewer);
      state.brewers = toBrewers(p.items);
      problemsByFile.set(name, p.problems);
      break;
    }
    case "recipes": {
      await load(["brewers"]);
      // Recipes name their brewer by id: decoded without the list, every one would become "Other".
      if (!loaded.has("brewers")) throw new Error("brewers.json did not load");
      if (raw === undefined) {
        // Optional until Android WP-1 writes shared/recipes.json.
        state.recipes = [];
        problemsByFile.set(name, []);
        break;
      }
      const p = N.parseList(file, raw, "recipes", (o) => {
        const r = decodeRecipe(o, Brewers.resolve, true);
        if (!r.ok) throw new Error(r.error);
        return r.value;
      });
      state.recipes = p.items;
      problemsByFile.set(name, p.problems);
      break;
    }
  }
  loaded.add(name);
}

/**
 * Loads the named files (all when omitted). Safe to call repeatedly and concurrently. Never
 * rejects: a file that could not be fetched is left unloaded (see `catalog.failed`) and the next
 * call tries it again.
 */
export function load(names: readonly CatalogName[] = CATALOG_NAMES): Promise<void> {
  return Promise.all(
    names.map((n) => {
      if (loaded.has(n)) return Promise.resolve();
      let p = inflight.get(n);
      if (!p) {
        p = loadOne(n)
          .then(
            () => void failedFiles.delete(n),
            (e: unknown) => {
              const why = `${sourcePath(n)}: ${e instanceof Error ? e.message : String(e)}`;
              failedFiles.set(n, why);
              problemsByFile.set(n, [why]);
            },
          )
          .then(() => {
            // Not kept once settled: a failed file must be fetched again by the next call.
            inflight.delete(n);
            notify();
          });
        inflight.set(n, p);
      }
      return p;
    }),
  ).then(() => undefined);
}

export const catalog = {
  load,
  isLoaded: (name: CatalogName) => loaded.has(name),
  /** The last attempt to load this file failed and it has not loaded since. */
  failed: (name: CatalogName) => failedFiles.has(name),
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
  /** Changes whenever a file finishes loading; for useSyncExternalStore. */
  version: () => version,

  get grinders() { return state.grinders; },
  get roasters() { return state.roasters; },
  get origins() { return state.origins; },
  get beans() { return state.beans; },
  get brewers() { return state.brewers; },
  get water() { return state.water; },
  get providers() { return state.providers; },
  get recipes() { return state.recipes; },
  get principles() { return state.principles; },
  get glossary() { return state.glossary; },
  get problems(): readonly string[] {
    return CATALOG_NAMES.flatMap((n) => problemsByFile.get(n) ?? []);
  },

  grinder: (id: string | null | undefined) => lookup("grinders", state.grinders, id),
  roaster: (id: string | null | undefined) => lookup("roasters", state.roasters, id),
  origin: (id: string | null | undefined) => lookup("origins", state.origins, id),
  brewer: (id: string | null | undefined) => lookup("brewers", state.brewers, id),
  /** `water` is the list (as the file key), so the lookup has its own name. */
  waterById: (id: string | null | undefined) => lookup("water", state.water, id),
  provider: (id: string | null | undefined) => lookup("providers", state.providers, id),
  recipe: (id: string | null | undefined) => lookup("recipes", state.recipes, id),
  /** A glossary term by id (findTerm in domain/core/glossary also takes aliases). */
  term: (id: string | null | undefined) => lookup("glossary", state.glossary, id),
  process: (id: string | null | undefined): ProcessTerm | null => lookup("processes", state.beans.processes, id),
  varietal: (id: string | null | undefined): VarietalTerm | null => lookup("varietals", state.beans.varietals, id),

  /** Display label for a vocabulary id; null when unknown (callers show the raw id or "Unknown"). */
  label(kind: TermKind, id: string | null | undefined): string | null {
    if (!id) return null;
    const v = state.beans;
    const list =
      kind === "species" ? v.species
      : kind === "process" ? v.processes
      : kind === "varietal" ? v.varietals
      : kind === "lotType" ? v.lotTypes
      : kind === "decaf" ? v.decafMethods
      : kind === "roastLevel" ? v.roastLevels
      : v.roastIntents;
    return lookup(`term:${kind}`, list, id)?.label ?? null;
  },
};

/** Brewers bound to the loaded catalog (A§2.5 `Brewers`). */
export const Brewers = {
  get all(): readonly Brewer[] {
    return state.brewers;
  },
  byId: (id: string | null | undefined): Brewer | null => catalog.brewer(id),
  resolveOrNull: (raw: string | null | undefined): Brewer | null => resolveBrewerIn(state.brewers, raw),
  resolve: (raw: string): Brewer => resolveBrewerIn(state.brewers, raw) ?? unknownBrewer(raw),
};

/** Test hook: forget everything so a test can load again. */
export function resetCatalogForTests(): void {
  Object.assign(state, {
    grinders: [], roasters: [], origins: [], beans: EMPTY_VOCAB, brewers: [], water: [], providers: [], recipes: [], principles: [], glossary: [],
  });
  problemsByFile.clear();
  loaded.clear();
  failedFiles.clear();
  inflight.clear();
  notify();
}

// Back online: fetch whatever failed while the connection was down, so lists fill in by themselves.
if (typeof window !== "undefined") {
  window.addEventListener("online", () => {
    if (failedFiles.size > 0) void load([...failedFiles.keys()]);
  });
}
