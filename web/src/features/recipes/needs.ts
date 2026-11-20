/**
 * "What you'll need" (v4 §4.6, §3.3): the kit a recipe asks for, said in terms of what this person
 * has. A need they cannot meet is never listed as a requirement; it comes with the way around it
 * (spoons instead of a scale, a normal kettle instead of a gooseneck). Pure.
 */
import { brewerArt, brewerShortName, type Brewer } from "@/domain/core/brewer";
import { grams } from "@/domain/core/format";
import { noScaleAmounts } from "@/domain/core/noScale";
import type { Recipe } from "@/domain/core/recipe";
import { unmetNeeds, type FirstBrewSettings } from "@/domain/core/firstBrew";

export interface NeedItem {
  readonly id: "brewer" | "kettle" | "scale" | "grinder" | "thermometer" | "coffee" | "water" | "cup";
  /** Chip text: "French press", "6 level tablespoons of coffee". */
  readonly text: string;
  /** The way around a need they cannot meet, or a plain hint; null when nothing to add. */
  readonly note: string | null;
}

export type NeedsContext = Pick<FirstBrewSettings, "hasScale" | "buysGround" | "experience">;

/**
 * A brewer's name for a sentence or a chip: the short name when it fits whole ("V60", "French
 * press"), else the label without its bracketed note ("Cezve / ibrik", never "Cezve / ibrik…").
 */
export function brewerPlainName(b: Brewer): string {
  const short = brewerShortName(b);
  return short.endsWith("…") ? b.label.replace(/\s*\([^)]*\)\s*$/, "").trim() : short;
}

/** Methods whose machine or pot heats its own water: no kettle. */
function heatsItsOwnWater(r: Recipe): boolean {
  const m = r.brewer.method;
  return m === "espresso" || m === "turkish" || m === "coldBrew" || brewerArt(r.brewer) === "Drip";
}

export function needItems(recipe: Recipe, ctx: NeedsContext, grinderCount: number): NeedItem[] {
  const settings: FirstBrewSettings = { ownedBrewerIds: [], ...ctx };
  const unmet = new Set(unmetNeeds(recipe.needs, settings, grinderCount));
  const needs = new Set(recipe.needs);
  const method = recipe.brewer.method;
  const basket = method === "espresso" || method === "moka";
  const out: NeedItem[] = [{ id: "brewer", text: brewerPlainName(recipe.brewer), note: null }];

  if (!heatsItsOwnWater(recipe)) {
    if (needs.has("gooseneck") && !unmet.has("gooseneck")) out.push({ id: "kettle", text: "Gooseneck kettle", note: null });
    else if (needs.has("gooseneck")) out.push({ id: "kettle", text: "Kettle", note: "A gooseneck helps, but a normal kettle works if you pour gently." });
    else out.push({ id: "kettle", text: "Kettle", note: null });
  }
  if (needs.has("scale")) {
    out.push(unmet.has("scale") ? { id: "scale", text: "Spoons and a measuring jug", note: "No scale needed: amounts show in tablespoons and ml." } : { id: "scale", text: "Kitchen scale", note: null });
  }
  if (needs.has("grinder")) {
    if (!unmet.has("grinder")) out.push({ id: "grinder", text: "Grinder", note: null });
    else if (ctx.buysGround) out.push({ id: "grinder", text: "Ground coffee", note: "Coffee ground for this kind of brewer works; the grind can't change, so we'll adjust time instead." });
    else out.push({ id: "grinder", text: "Grinder or ground coffee", note: "No grinder? Buy coffee ground for this kind of brewer." });
  }
  if (needs.has("thermometer") && !unmet.has("thermometer")) out.push({ id: "thermometer", text: "Thermometer", note: null });
  else if (needs.has("thermometer")) out.push({ id: "thermometer", text: "Just-boiled water", note: "No thermometer? Boil, then wait about 30 seconds." });

  // The amounts, in the units this person measures with.
  if (ctx.hasScale) {
    out.push({ id: "coffee", text: `${grams(recipe.doseG)} g coffee`, note: null });
    if (method !== "espresso") out.push({ id: "water", text: `${grams(recipe.waterG)} g water`, note: null });
  } else {
    const a = noScaleAmounts(recipe.doseG, recipe.waterG, method);
    out.push({ id: "coffee", text: basket ? "Coffee to fill the basket" : a.coffeeLine, note: basket ? a.basket : null });
    if (method !== "espresso") out.push({ id: "water", text: `${a.waterMl} ml water`, note: null });
  }
  out.push({ id: "cup", text: method === "espresso" ? "An espresso cup" : "A mug", note: null });
  return out;
}
