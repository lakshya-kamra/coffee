/**
 * No-scale brewing (v4 §4.3, D10): spoons, ml and mugs rendered from the stored grams. Grams stay
 * the truth; nothing here is ever saved. Kotlin twin: domain/NoScale.kt, same results (the parity
 * cases are in shared/fixtures/domain-v4-cases.json).
 */
import type { BrewMethod } from "./brewer";
import { grams, halves, roundToInt } from "./format";
import { scaledToWater, type Recipe } from "./recipe";
import { renderTemps, type Units } from "./units";

/** Grams of ground coffee in one level tablespoon (shared/schema/units.md). */
export const GROUND_COFFEE_G_PER_LEVEL_TBSP = 5;
/** One mug, for "500 ml (2 mugs)" and the servings presets. */
export const ML_PER_MUG = 250;
/** Below this, no mug count is shown. */
const MUGS_FROM_ML = 200;

/** "" = not recorded (treated as whole), as BeanRow.form. */
export type BeanForm = "" | "whole" | "ground";

export interface NoScaleAmounts {
  /** Level tablespoons, in halves, at least 1; null for espresso and moka (the basket sets the dose). */
  readonly coffeeTbsp: number | null;
  /** "6 level tablespoons"; "" for espresso and moka. */
  readonly coffee: string;
  /** "6 level tablespoons of coffee" ("… of beans" before a blade grinder); the basket line for espresso and moka. */
  readonly coffeeLine: string;
  readonly waterMl: number;
  /** In halves, at least 1; null under 200 ml. */
  readonly mugs: number | null;
  /** "500 ml (2 mugs)" or "150 ml". */
  readonly water: string;
  /** Espresso and moka: how to fill the basket instead of spoons. */
  readonly basket: string | null;
}

const ESPRESSO_BASKET = "Fill the basket level, then tamp.";
const MOKA_BASKET = "Fill the basket level; don't press it down. Water to just below the valve.";

function toHalves(v: number): number {
  return roundToInt(v * 2) / 2;
}

/**
 * `form` "whole" means beans measured before grinding (a blade grinder at home), so the line
 * reads "of beans"; anything else is coffee already ground.
 */
export function noScaleAmounts(doseG: number, waterG: number, method: BrewMethod, form: BeanForm = ""): NoScaleAmounts {
  const waterMl = Math.max(0, roundToInt(waterG));
  const mugs = waterMl >= MUGS_FROM_ML ? Math.max(1, toHalves(waterMl / ML_PER_MUG)) : null;
  const water = mugs == null ? `${waterMl} ml` : `${waterMl} ml (${halves(mugs)} ${mugs === 1 ? "mug" : "mugs"})`;
  const basket = method === "espresso" ? ESPRESSO_BASKET : method === "moka" ? MOKA_BASKET : null;
  if (basket != null) return { coffeeTbsp: null, coffee: "", coffeeLine: basket, waterMl, mugs, water, basket };
  const tbsp = Math.max(1, toHalves(Math.max(0, doseG) / GROUND_COFFEE_G_PER_LEVEL_TBSP));
  const coffee = `${halves(tbsp)} level ${tbsp === 1 ? "tablespoon" : "tablespoons"}`;
  return { coffeeTbsp: tbsp, coffee, coffeeLine: `${coffee} ${form === "whole" ? "of beans" : "of coffee"}`, waterMl, mugs, water, basket: null };
}

/** A prep line that needs a scale: weighing, zeroing or taring. */
export const SCALE_LINE = /\b(scales?|zero(?:ed)?|tare|weigh)\b/i;
/** A prep line that gives an amount in grams ("Put 170 g of ice…"): it needs a scale too. */
export const GRAMS_LINE = /\d\s*(?:g|grams?)\b/i;

const GENERIC_NO_SCALE = ["Measure {water} of water in a jug first.", "Measure {coffee}."];

/**
 * The prep list for someone without a scale: the lines that need one (they mention a scale, or
 * give grams) are replaced, where the first of them stood, by the recipe's prepNoScale, else by
 * the basket line (espresso, moka), else by the two generic measuring lines. A recipe with no such
 * line gets the replacement first, so every no-scale prep says how to measure. prepNoScale is
 * written by hand where grams are not water (ice, a bypass, a dose per roast), because "170 ml of
 * ice" is not a measure anyone can pour. Every line has its temperatures in the user's unit.
 * Kotlin twin: NoScale.prep (parity cases "noScalePrep").
 */
export function noScalePrep(
  recipe: Pick<Recipe, "prep" | "prepNoScale" | "doseG" | "waterG" | "brewer">,
  units: Units,
  form: BeanForm = "",
): string[] {
  const a = noScaleAmounts(recipe.doseG, recipe.waterG, recipe.brewer.method, form);
  const fill = (s: string) => s.replaceAll("{coffee}", a.coffeeLine).replaceAll("{water}", a.water);
  const replacement = recipe.prepNoScale.length > 0 ? recipe.prepNoScale.map(fill) : a.basket != null ? [a.basket] : GENERIC_NO_SCALE.map(fill);
  const out: string[] = [];
  let replaced = false;
  for (const line of recipe.prep) {
    if (!SCALE_LINE.test(line) && !GRAMS_LINE.test(line)) {
      out.push(line);
    } else if (!replaced) {
      out.push(...replacement);
      replaced = true;
    }
  }
  if (!replaced) out.unshift(...replacement);
  const unit = units.fahrenheit ? "f" : "c";
  return out.map((l) => renderTemps(l, unit));
}

// "16 g of coffee", "2 g more coffee": coffee, not water. "Pour 100 g over the coffee" is water.
const PROSE_GRAMS = /(\d+(?:\.\d+)?)\s*(?:g|grams?)\b(\s+(?:of\s+|more\s+)?(?:ground\s+)?coffee\b)?/gi;

/**
 * Recipe prose for someone without a scale (v4 §4.3): every gram amount in a step title or detail
 * is said the way the rest of the brew says it. Water is read as ml (1 g of water is 1 ml, no
 * rounding, so "Pour all 500 g" says the same 500 as the "Pour all 500 ml" dial); coffee is read
 * in level tablespoons, in halves. Kotlin twin: NoScale.text (parity cases "noScaleText").
 */
export function noScaleText(text: string): string {
  return text.replace(PROSE_GRAMS, (_all, n: string, coffee: string | undefined) => {
    const g = Number(n);
    if (coffee) {
      const tbsp = Math.max(0.5, toHalves(g / GROUND_COFFEE_G_PER_LEVEL_TBSP));
      return `${halves(tbsp)} level ${tbsp <= 1 ? "tablespoon" : "tablespoons"}${coffee}`;
    }
    return `${roundToInt(g)} ml`;
  });
}

/** A water amount as the person reads it: "500 g" with a scale, "500 ml" without. */
export function waterAmount(g: number, hasScale: boolean): { value: string; unit: "g" | "ml" } {
  return hasScale ? { value: grams(g), unit: "g" } : { value: String(Math.max(0, roundToInt(g))), unit: "ml" };
}

/**
 * A route's coffee as the person reads it (A1): grams with a scale; without one, level
 * tablespoons in halves, except espresso and moka, whose dose is the basket's and keeps grams.
 * Kotlin twin: NoScale.coffee.
 */
export function coffeeAmount(doseG: number, method: BrewMethod, hasScale: boolean): { value: string; unit: "g" | "tbsp" } {
  const tbsp = hasScale ? null : noScaleAmounts(doseG, 0, method).coffeeTbsp;
  return tbsp != null ? { value: halves(tbsp), unit: "tbsp" } : { value: grams(doseG), unit: "g" };
}

/**
 * "30 g → 500 g" with a scale; "6 tbsp → 500 ml" (an espresso "18 g → 36 ml") without one: the
 * recipe cards', the list rows' and the palette's short route, the same numbers the brew reads.
 */
export function routeText(doseG: number, waterG: number, method: BrewMethod, hasScale: boolean): string {
  const c = coffeeAmount(doseG, method, hasScale);
  const w = waterAmount(waterG, hasScale);
  return `${c.value} ${c.unit} → ${w.value} ${w.unit}`;
}

/** "1 mug", "2 mugs", "3 mugs": 250 ml of water each, the dose from the recipe's ratio. */
export function servingsPreset(recipe: Recipe, mugs: number): Recipe {
  return scaledToWater(recipe, ML_PER_MUG * mugs);
}
