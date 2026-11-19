/**
 * The recipe library's filters (RecipesViewModel.kt + A§7.8): search over name, author, blurb and
 * brewer; "Mine" (your own and your edits); "My brewers" (matched by family, so a V60-01 owner
 * sees V60-02 recipes); one brewer at a time. All filters AND together. Pure.
 */
import { brewerFamily, type Brewer } from "@/domain/core/brewer";
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

export function ownedFamilies(ownedBrewerIds: readonly string[], byId: (id: string) => Brewer | null): Set<string> {
  const out = new Set<string>();
  for (const id of ownedBrewerIds) {
    const b = byId(id);
    if (b) out.add(brewerFamily(b));
  }
  return out;
}

export function filterRecipes(all: readonly Recipe[], f: RecipeFilter, families: ReadonlySet<string>): Recipe[] {
  const q = f.query.trim().toLowerCase();
  return all.filter(
    (r) =>
      (!f.mine || isMine(r)) &&
      (!f.myBrewers || families.size === 0 || families.has(brewerFamily(r.brewer))) &&
      (f.brewerId == null || r.brewer.id === f.brewerId) &&
      (!q || [r.name, r.author, r.blurb, r.brewer.label].some((s) => s.toLowerCase().includes(q))),
  );
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
