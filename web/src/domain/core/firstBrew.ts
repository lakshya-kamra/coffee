/**
 * A guided first brew (v4 §4.6): the one recipe to suggest to someone with no brews yet. Kotlin
 * twin: domain/FirstBrew.kt, same picks (shared/fixtures/domain-v4-cases.json).
 */
import { brewerFamily, NO_MATCH_FAMILIES, ownedFamilies, type Brewer } from "./brewer";
import type { Recipe, RecipeLevel, RecipeNeed } from "./recipe";

export interface FirstBrewSettings {
  readonly ownedBrewerIds: readonly string[];
  readonly hasScale: boolean;
  readonly buysGround: boolean;
  /** "" | "new" | "recipes" | "tinker" */
  readonly experience: string;
}

export interface FirstBrewPick {
  /** Null when no owned brewer has a recipe (or there are no recipes). */
  readonly recipe: Recipe | null;
  /** Owned brewer ids whose family has no recipe: "We don't have a tested recipe for your Cezve yet." */
  readonly noRecipeFor: readonly string[];
}

const LEVEL_RANK: Readonly<Record<RecipeLevel, number>> = { easy: 0, medium: 1, "": 1, advanced: 2 };

/** The needs this person cannot meet today. */
export function unmetNeeds(needs: readonly RecipeNeed[], s: FirstBrewSettings, grinderCount: number): RecipeNeed[] {
  return needs.filter((n) => {
    switch (n) {
      case "scale":
        return !s.hasScale;
      case "grinder":
        return s.buysGround || grinderCount === 0;
      case "gooseneck":
      case "thermometer":
        // Not asked in onboarding (§9 item 10): assumed missing for someone new to this.
        return s.experience === "new";
    }
  });
}

/**
 * Owned family first (never another family's recipe), then fewest unmet needs, then level (easy,
 * medium, advanced; none counts as medium), then catalog order. With no owned brewer that has
 * a family (none, or only a Melodrip) every recipe is a candidate. `grinders` are the person's grinders; archived ones do not count.
 */
export function firstBrew(
  recipes: readonly Recipe[],
  settings: FirstBrewSettings,
  grinders: readonly { readonly isArchived: boolean }[],
  brewerById: (id: string) => Brewer | null,
): FirstBrewPick {
  const families = ownedFamilies(settings.ownedBrewerIds, brewerById);
  const grinderCount = grinders.filter((g) => !g.isArchived).length;
  // Owning only an accessory (or nothing) is the same as not saying: every recipe is a candidate.
  const owns = families.size > 0;
  const candidates = owns ? recipes.filter((r) => families.has(brewerFamily(r.brewer))) : [...recipes];
  const noRecipeFor = owns
    ? settings.ownedBrewerIds.filter((id) => {
        const b = brewerById(id);
        if (!b) return false;
        const f = brewerFamily(b);
        // An accessory or "other" never had recipes of its own to miss.
        return !NO_MATCH_FAMILIES.has(f) && !recipes.some((r) => brewerFamily(r.brewer) === f);
      })
    : [];
  const scored = candidates.map((r, i) => ({ r, i, unmet: unmetNeeds(r.needs, settings, grinderCount).length, level: LEVEL_RANK[r.level] }));
  scored.sort((a, b) => a.unmet - b.unmet || a.level - b.level || a.i - b.i);
  return { recipe: scored[0]?.r ?? null, noRecipeFor };
}
