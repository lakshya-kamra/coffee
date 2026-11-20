/**
 * The recipe library's filters (RecipesViewModel.kt + A§7.8): search over name, author, blurb and
 * brewer; "Mine" (your own and your edits); "My brewers" (matched by family, so a V60-01 owner
 * sees V60-02 recipes); one brewer at a time. All filters AND together. Pure.
 */
import type { BrewRow } from "@/data/schema";
import { brewerFamily, ownedFamilies as coreOwnedFamilies, type Brewer } from "@/domain/core/brewer";
import { foldForSearch } from "@/domain/core/logQuery";
import type { Recipe } from "@/domain/core/recipe";

export interface RecipeFilter {
  query: string;
  mine: boolean;
  myBrewers: boolean;
  brewerId: string | null;
}

export const NO_FILTER: RecipeFilter = { query: "", mine: false, myBrewers: false, brewerId: null };

/** Yours: saved recipes and your edits of built-ins (both are stored rows, isBuiltIn false). */
export const isMine = (r: Recipe): boolean => !r.isBuiltIn || r.fidelity === "custom";

/**
 * CORE's owned families (v4 §4.13): an owned Melodrip or "other" brewer matches nothing, so it
 * never turns "My brewers" into a V60 list.
 */
export function ownedFamilies(ownedBrewerIds: readonly string[], byId: (id: string) => Brewer | null): Set<string> {
  return coreOwnedFamilies(ownedBrewerIds, byId);
}

/**
 * "My brewers" is derived on every render (v4 §4.13), never copied into state at mount: only the
 * person's explicit choice is stored (null = not chosen). So brewers saved after Recipes first
 * mounted (onboarding, Gear) turn it on, and a person with none never sees an empty filter.
 */
export function myBrewersOn(hasOwned: boolean, choice: boolean | null): boolean {
  return hasOwned && (choice ?? true);
}

export function filterRecipes(all: readonly Recipe[], f: RecipeFilter, families: ReadonlySet<string>): Recipe[] {
  // Every word must appear somewhere, accents ignored ("cafe" finds "Café Bustelo").
  const words = foldForSearch(f.query).split(/\s+/).filter(Boolean);
  return all.filter((r) => {
    if (f.mine && !isMine(r)) return false;
    if (f.myBrewers && families.size > 0 && !families.has(brewerFamily(r.brewer))) return false;
    if (f.brewerId != null && r.brewer.id !== f.brewerId) return false;
    if (words.length === 0) return true;
    const hay = foldForSearch([r.name, r.author, r.blurb, r.brewer.label].join(" "));
    return words.every((w) => hay.includes(w));
  });
}

/** Brewers present in the list, in list order, once each. */
export function brewersIn(all: readonly Recipe[]): Brewer[] {
  const seen = new Set<string>();
  const out: Brewer[] = [];
  for (const r of all) {
    if (seen.has(r.brewer.id)) continue;
    seen.add(r.brewer.id);
    out.push(r.brewer);
  }
  return out;
}

/** "14 METHODS · 9 PEOPLE"; blank until recipes load so it never flashes "0 METHODS". */
export function headerEyebrow(all: readonly Recipe[]): string {
  if (all.length === 0) return " ";
  const people = new Set(all.map((r) => r.author)).size;
  const m = all.length === 1 ? "1 METHOD" : `${all.length} METHODS`;
  const p = people === 1 ? "1 PERSON" : `${people} PEOPLE`;
  return `${m} · ${p}`;
}

// ---------------------------------------------------------------- sort (v4 §3.2)

export type RecipeSort = "suggested" | "az" | "brewed" | "rated";
export const RECIPE_SORTS: readonly { id: RecipeSort; label: string }[] = [
  { id: "suggested", label: "Suggested" },
  { id: "az", label: "A-Z" },
  { id: "brewed", label: "Most brewed" },
  { id: "rated", label: "Best rated" },
];

export interface RecipeStats {
  readonly count: number;
  /** Best overall score, 0 when never rated. */
  readonly best: number;
}

const NO_STATS: RecipeStats = { count: 0, best: 0 };

export function recipeStats(brews: readonly Pick<BrewRow, "recipeId" | "overall">[]): Map<string, RecipeStats> {
  const out = new Map<string, RecipeStats>();
  for (const b of brews) {
    const s = out.get(b.recipeId) ?? NO_STATS;
    out.set(b.recipeId, { count: s.count + 1, best: Math.max(s.best, b.overall) });
  }
  return out;
}

/**
 * Suggested: the next brew first, then recipes for brewers you own, then the rest. A-Z by name.
 * Most brewed: brew count, then best score. Best rated: best score (unrated last), then count.
 * Ties keep the library's order, so the list never shuffles between renders.
 */
export function sortRecipes(list: readonly Recipe[], sort: RecipeSort, stats: ReadonlyMap<string, RecipeStats>, families: ReadonlySet<string>, nextId: string | null): Recipe[] {
  const st = (r: Recipe) => stats.get(r.id) ?? NO_STATS;
  const indexed = list.map((r, i) => ({ r, i }));
  const cmp = (a: { r: Recipe; i: number }, b: { r: Recipe; i: number }): number => {
    switch (sort) {
      case "suggested": {
        const rank = (r: Recipe) => (r.id === nextId ? 0 : families.has(brewerFamily(r.brewer)) ? 1 : 2);
        return rank(a.r) - rank(b.r);
      }
      case "az":
        return a.r.name.localeCompare(b.r.name, undefined, { sensitivity: "base" }) || a.r.author.localeCompare(b.r.author, undefined, { sensitivity: "base" });
      case "brewed":
        return st(b.r).count - st(a.r).count || st(b.r).best - st(a.r).best;
      case "rated":
        return st(b.r).best - st(a.r).best || st(b.r).count - st(a.r).count;
    }
  };
  return indexed.sort((a, b) => cmp(a, b) || a.i - b.i).map((x) => x.r);
}
