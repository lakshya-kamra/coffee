/**
 * Brew again (v4 §4.10): the setup for /brew/:recipeId/again/:brewUid, prefilled from that brew's
 * coffee, grinder, grind, dose and water. When the recipe has been deleted, a one-off recipe is
 * built from the brew's own numbers (title "Brew again"; brews do not store steps, so it has one
 * "Brew" step for the original time). Pure.
 */
import type { BeanRow, BrewRow, GrinderRow } from "@/data/schema";
import type { Settings } from "@/data/settings";
import { brewStyle, type Brewer } from "@/domain/core/brewer";
import type { Recipe } from "@/domain/core/recipe";
import { initialSetup, isBlade, prefillGrind, setupRecipe, usesClicks, type Setup } from "./setup";

/** What the setup pane shows as its title for a one-off. */
export const AGAIN_TITLE = "Brew again";

/** A plain fallback length when the brew recorded no time. */
const FALLBACK_SEC = 240;

/**
 * The one-off recipe for a brew whose recipe is gone. It keeps the brew's recipe id and name so
 * the new brew sits with the old ones in the log.
 */
export function oneOffRecipe(brew: BrewRow, brewer: Brewer): Recipe {
  const sec = brew.totalTimeSec > 0 ? brew.totalTimeSec : FALLBACK_SEC;
  const immersion = brewStyle(brewer) !== "percolation";
  return {
    id: brew.recipeId.trim() || `again-${brew.uid}`,
    name: brew.recipeName.trim() || AGAIN_TITLE,
    author: brew.recipeAuthor,
    brewer,
    doseG: brew.doseG,
    waterG: brew.waterG,
    grind: brew.grindSetting.trim(),
    tempC: brew.tempC,
    blurb: "",
    sourceNote: "",
    prep: [],
    tips: [],
    steps: [
      {
        kind: immersion ? "Steep" : "Pour",
        title: "Brew",
        detail: "The same time as last time.",
        startSec: 0,
        endSec: sec,
        targetWaterG: brew.waterG > 0 ? brew.waterG : null,
      },
    ],
    accent: 0xff000000,
    isBuiltIn: false,
    fidelity: "custom",
    sources: [],
    basedOn: null,
    level: "",
    needs: [],
    prepNoScale: [],
  };
}

/**
 * The setup for brewing `brew` again with `recipe`: the brew's dose, water (only when it differs
 * from the recipe's ratio), temperature, coffee and grind. A coffee or grinder that has since been
 * archived or deleted falls back to the usual defaults.
 */
export function againSetup(
  recipe: Recipe,
  brew: BrewRow,
  beans: readonly BeanRow[],
  grinders: readonly GrinderRow[],
  settings: Settings,
  brews: readonly BrewRow[],
): Setup {
  const base = initialSetup(recipe, beans, grinders, settings, brews);
  const dose = brew.doseG > 0 ? brew.doseG : base.doseG;
  const ratioWater = setupRecipe(recipe, { doseG: dose, waterG: null }).waterG;
  const water = brew.waterG > 0 && Math.abs(brew.waterG - ratioWater) >= 1 ? brew.waterG : null;
  const beanId = beans.some((b) => b.id === brew.beanId) ? brew.beanId : base.beanId;
  const grinder = grinders.find((g) => g.id === brew.grinderId) ?? null;
  const preground = brew.grindKind === "preground" || (brew.grindKind === "" && brew.grinderId == null && brew.grindSetting.trim().toLowerCase() === "as bought");

  const s: Setup = {
    ...base,
    doseG: dose,
    waterG: water,
    tempC: brew.tempC > 0 ? brew.tempC : base.tempC,
    beanId,
    preground,
    grinderId: grinder?.id ?? base.grinderId,
    grindClicks: null,
    grindSetting: "",
    bladeSec: null,
  };
  if (!preground && grinder) {
    if (isBlade(grinder)) s.bladeSec = brew.grindValue != null && brew.grindValue > 0 ? brew.grindValue : null;
    else if (usesClicks(grinder)) s.grindClicks = brew.grindClicks != null && brew.grindClicks > 0 ? brew.grindClicks : null;
    else s.grindSetting = brew.grindValue != null ? String(brew.grindValue) : brew.grindSetting.trim();
  } else if (!preground && brew.grinderId == null) {
    // A brew with a typed setting and no grinder: keep the words.
    s.grindSetting = brew.grindSetting.trim();
  }
  // Anything the brew left empty is filled as a fresh setup would be.
  return prefillGrind(s, recipe, grinders, brews);
}
