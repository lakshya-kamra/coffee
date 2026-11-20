/**
 * The "YOUR FIRST BREW" card (v4 §4.6): which recipe, what to have ready and the three things to
 * do, for someone with no brews yet. The pick is CORE's firstBrew (owned family first, never a
 * borrowed recipe); this turns it into the card's words. Pure.
 */
import { brewerArt, type Brewer } from "@/domain/core/brewer";
import { firstBrew, type FirstBrewSettings } from "@/domain/core/firstBrew";
import type { Recipe } from "@/domain/core/recipe";
import { brewerPlainName, needItems } from "@/features/recipes/needs";

export type FirstBrewCard =
  | {
      readonly kind: "recipe";
      readonly recipe: Recipe;
      /** "You'll need" chips. */
      readonly needs: readonly string[];
      /** The three-line checklist. */
      readonly steps: readonly [string, string, string];
      /** Owned brewers with no tested recipe (said quietly under the card). */
      readonly missing: readonly string[];
    }
  | {
      /** Every owned brewer lacks a recipe: "We don't have a tested recipe for your Cezve yet." */
      readonly kind: "none";
      readonly missing: readonly string[];
    };

/** The first line, for brewers where boiling a kettle is wrong. */
function heatLine(r: Recipe): string {
  if (brewerArt(r.brewer) === "Drip") return "Fill the machine's tank with fresh cold water.";
  switch (r.brewer.method) {
    case "espresso":
      return "Turn the machine on and let it heat up.";
    case "turkish":
      return "Use fresh cold water: the cezve heats it.";
    case "coldBrew":
      return "Use cold or room-temperature water.";
    default:
      return "Boil the kettle, then let it sit for 30 seconds.";
  }
}

function startLine(r: Recipe): string {
  const machine = r.brewer.method === "espresso" || brewerArt(r.brewer) === "Drip";
  return machine ? "Press Start as you switch it on. We'll tell you each step." : "Press Start as you pour. We'll tell you each step.";
}

/** "We don't have a tested recipe for your Cezve yet." with the names joined plainly. */
export function noRecipeLine(names: readonly string[]): string {
  if (names.length === 0) return "";
  const list = names.length === 1 ? names[0]! : `${names.slice(0, -1).join(", ")} or ${names[names.length - 1]!}`;
  return `We don't have a tested recipe for your ${list} yet.`;
}

export function firstBrewCard(
  recipes: readonly Recipe[],
  settings: FirstBrewSettings,
  grinders: readonly { readonly isArchived: boolean }[],
  brewerById: (id: string) => Brewer | null,
): FirstBrewCard | null {
  if (recipes.length === 0) return null;
  const pick = firstBrew(recipes, settings, grinders, brewerById);
  const missing = pick.noRecipeFor.flatMap((id) => {
    const b = brewerById(id);
    return b ? [brewerPlainName(b)] : [];
  });
  if (!pick.recipe) return { kind: "none", missing };
  const r = pick.recipe;
  const grinderCount = grinders.filter((g) => !g.isArchived).length;
  return {
    kind: "recipe",
    recipe: r,
    needs: needItems(r, settings, grinderCount).map((n) => n.text),
    steps: [heatLine(r), "Measure the coffee and the water.", startLine(r)],
    missing,
  };
}
