/**
 * Log search and filters (v4 §4.11). Pure; Kotlin twin: domain/LogQuery.kt, same matches
 * (shared/fixtures/domain-v4-cases.json).
 */
import { plural } from "./format";

/** The fields of a brew the search looks at. */
export interface LogSearchable {
  readonly recipeName: string;
  readonly recipeAuthor: string;
  /** "Roaster — Name". */
  readonly beanLabel: string;
  readonly grinderLabel: string;
  /** The brewer's label snapshot. */
  readonly brewer: string;
  readonly notes: string;
  readonly nextTime: string;
  readonly descriptors: readonly string[];
}

/** Lower case with accents removed: "Café" and "cafe" are the same word. */
export function foldForSearch(text: string): string {
  return text.normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase();
}

/**
 * Every word of the query appears somewhere in the brew's recipe, coffee, grinder, brewer,
 * notes, next time or descriptors. An empty query matches everything.
 */
export function matchesLogQuery(brew: LogSearchable, query: string): boolean {
  const words = foldForSearch(query).split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const hay = foldForSearch(
    [brew.recipeName, brew.recipeAuthor, brew.beanLabel, brew.grinderLabel, brew.brewer, brew.notes, brew.nextTime, ...brew.descriptors].join("\n"),
  );
  return words.every((w) => hay.includes(w));
}

export type LogSort = "newest" | "best";

export interface LogFilter {
  readonly query: string;
  readonly favourites: boolean;
  readonly withPhotos: boolean;
  /** Overall 8 or more. */
  readonly rated8: boolean;
  /** A beanLabel, or null for any coffee. */
  readonly coffee: string | null;
  /** A recipe key (recipeKey), or null for any recipe. */
  readonly recipe: string | null;
  /** A brewer key (brewerKey), or null for any brewer. */
  readonly brewer: string | null;
  readonly sort: LogSort;
}

export const EMPTY_LOG_FILTER: LogFilter = Object.freeze({
  query: "", favourites: false, withPhotos: false, rated8: false, coffee: null, recipe: null, brewer: null, sort: "newest",
});

export interface LogFilterable extends LogSearchable {
  readonly id?: number;
  readonly startedAt: number;
  readonly overall: number;
  readonly isFavourite: boolean;
  readonly recipeId: string;
  readonly brewerId: string | null;
}

/** The recipe id, or its name for a brew that has none (a deleted one-off). */
export function recipeKey(b: Pick<LogFilterable, "recipeId" | "recipeName">): string {
  return b.recipeId.trim() || b.recipeName;
}

/** The brewer id, or its label for a brew from before brewer ids. */
export function brewerKey(b: Pick<LogFilterable, "brewerId" | "brewer">): string {
  return b.brewerId ?? b.brewer;
}

export function isFilterActive(f: LogFilter): boolean {
  return f.query.trim() !== "" || f.favourites || f.withPhotos || f.rated8 || f.coffee != null || f.recipe != null || f.brewer != null;
}

/** The brews that pass, sorted. `photoBrewIds` holds the ids of brews with at least one photo. */
export function filterLog<T extends LogFilterable>(brews: readonly T[], f: LogFilter, photoBrewIds: ReadonlySet<number> = new Set()): T[] {
  const out = brews.filter(
    (b) =>
      (!f.favourites || b.isFavourite) &&
      (!f.withPhotos || (b.id != null && photoBrewIds.has(b.id))) &&
      (!f.rated8 || b.overall >= 8) &&
      (f.coffee == null || b.beanLabel === f.coffee) &&
      (f.recipe == null || recipeKey(b) === f.recipe) &&
      (f.brewer == null || brewerKey(b) === f.brewer) &&
      matchesLogQuery(b, f.query),
  );
  return out.sort((a, b) => (f.sort === "best" ? b.overall - a.overall : 0) || b.startedAt - a.startedAt);
}

/** "12 brews", "1 brew". */
export function logCountLabel(n: number): string {
  return plural(n, "brew");
}
