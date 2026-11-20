/**
 * Port of domain/ai/DataQuality.kt: free, offline checks for values no real brew can have.
 * Distrust flags make the prompt send the field (and everything worked out from it) as null;
 * Note flags are context only. The same flags drive the Log check card and Review's warnings.
 */
import { Brewers } from "@/catalog/catalog";
import type { Brewer, BrewMethod } from "@/domain/core/brewer";
import { isFilterStyle as methodIsFilterStyle } from "@/domain/core/brewer";
import { DEFAULT_TASTE, type TasteProfile } from "@/domain/core/evaluation";
import { clock, grams, oneDp } from "@/domain/core/format";
import { totalSec, type Recipe } from "@/domain/core/recipe";
import { beanDisplay, type BeanRow, type BrewRow, type GrinderRow } from "@/data/schema";
import { espressoFloor, finerThanEspresso, format, moves, rangeFor, scaleFor, underOneLegacyTurn, type GrindScale } from "@/domain/grind";

export type FlagSeverity = "Distrust" | "Note";

export type FlagCode =
  | "GRIND_ZERO"
  /** 1.x rows at up to 40 clicks a turn: under one turn on a filter brew. Name kept: it is stored. */
  | "GRIND_UNDER_ONE_TURN"
  /** Finer than the catalog's espresso range on a filter brew. */
  | "GRIND_TOO_FINE_FOR_METHOD"
  | "GRIND_JUMP"
  | "GRIND_MISSING"
  | "BEVERAGE_OVER_WATER"
  | "RETENTION_LOW"
  | "RETENTION_HIGH"
  | "EY_IMPLAUSIBLE"
  | "DRAWDOWN_GE_TOTAL"
  | "DRAWDOWN_AFTER_END"
  | "DRAWDOWN_ZERO"
  | "TASTE_UNTOUCHED"
  | "TASTE_FAULT_CONFLICT"
  | "OVERALL_UNRATED"
  | "ROAST_DATE_MISSING"
  | "BEAN_MISSING"
  | "TIME_IMPLAUSIBLE"
  | "TEMP_IMPLAUSIBLE"
  | "RATIO_IMPLAUSIBLE";

/**
 * One finding about one brew. `message` is the user-facing copy and may quote the suspect value;
 * `modelMessage`, when set, is the same finding without it: the model is told a distrusted value
 * is unusable, never what it was or might have been.
 */
export interface DataFlag {
  readonly brewId: number;
  readonly code: FlagCode;
  readonly field: string;
  readonly severity: FlagSeverity;
  readonly message: string;
  readonly modelMessage?: string | null;
}

export const Fields = {
  GRIND: "grindClicks",
  BEVERAGE: "beverageG",
  EY: "extractionYield",
  DRAWDOWN: "drawdownSec",
  TASTE: "taste",
  OVERALL: "overall",
  ROAST: "daysOffRoast",
  BEAN: "bean",
  TIME: "totalTimeSec",
  TEMP: "tempC",
  RATIO: "doseWater",
} as const;

const FIELD_LABELS: Record<string, string> = {
  [Fields.GRIND]: "Grind",
  [Fields.BEVERAGE]: "In the cup",
  [Fields.EY]: "Extraction",
  [Fields.DRAWDOWN]: "Drawdown",
  [Fields.TASTE]: "Taste sliders",
  [Fields.OVERALL]: "Overall",
  [Fields.ROAST]: "Roast date",
  [Fields.BEAN]: "Coffee",
  [Fields.TIME]: "Total time",
  [Fields.TEMP]: "Temperature",
  [Fields.RATIO]: "Dose / water",
};

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field;
}

/** The text the prompt sends. */
export function promptMessage(f: DataFlag): string {
  return f.modelMessage ?? f.message;
}

export const RETENTION_LOW_G_PER_G = 1.0;
export const RETENTION_HIGH_G_PER_G = 3.3;
export const EY_MIN = 14.0;
export const EY_MAX = 26.0;
export const DRAWDOWN_SLACK_SEC = 20;
export const FILTER_RATIO_MIN = 8.0;
export const FILTER_RATIO_MAX = 25.0;

/** beans.json roast level ids where the first weeks off roast change a lot. */
const LIGHT_ROASTS = new Set(["extra-light", "light", "medium-light"]);

export function brewerOf(log: Pick<BrewRow, "brewerId" | "brewer">, recipe: Recipe | null): Brewer | null {
  return recipe?.brewer ?? Brewers.byId(log.brewerId) ?? Brewers.resolveOrNull(log.brewer);
}

/** Anything that runs water through a bed. An unknown brewer is treated as filter. */
export function isFilterStyleBrewer(brewer: Brewer | null): boolean {
  return brewer == null || methodIsFilterStyle(brewer.method);
}

/** When the last pour ends: the same rule the brew timer uses to time the drawdown. */
export function lastPourEndSec(recipe: Recipe | null | undefined): number | null {
  if (!recipe) return null;
  let max: number | null = null;
  for (const s of recipe.steps) if (s.targetWaterG != null && (max == null || s.endSec > max)) max = s.endSec;
  return max;
}

/** Grams of water left in each gram of spent coffee. About 2 is normal. */
export function retainedPerGram(log: Pick<BrewRow, "beverageG" | "doseG" | "waterG">): number | null {
  if (log.beverageG == null || log.doseG <= 0) return null;
  return (log.waterG - log.beverageG) / log.doseG;
}

function sameTaste(t: TasteProfile): boolean {
  return (Object.keys(DEFAULT_TASTE) as (keyof TasteProfile)[]).every((k) => t[k] === DEFAULT_TASTE[k]);
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}

export function checkBrew(
  log: BrewRow,
  recipe: Recipe | null,
  grinder: GrinderRow | null,
  bean: BeanRow | null,
  peers: readonly BrewRow[] = [],
  scale: GrindScale | null = grinder ? scaleFor(grinder) : null,
): DataFlag[] {
  const flags: DataFlag[] = [];
  const id = log.id ?? 0;
  const add = (code: FlagCode, field: string, severity: FlagSeverity, message: string, modelMessage?: string) =>
    flags.push({ brewId: id, code, field, severity, message, modelMessage: modelMessage ?? null });

  const brewer = brewerOf(log, recipe);
  const method: BrewMethod | null = brewer?.method ?? null;
  const filter = isFilterStyleBrewer(brewer);
  const cpr = grinder?.clicksPerRotation ?? 0;
  const mv = scale ? moves(scale, method) : null;
  const total = log.totalTimeSec;
  const recipeTotal = recipe ? totalSec(recipe) : 0;

  // ---- grind
  const clicks = log.grindClicks;
  let grindTrusted = true;
  if (clicks === 0) {
    grindTrusted = false;
    const runFor = total > 0 ? ` for ${clock(total)}` : "";
    add(
      "GRIND_ZERO", Fields.GRIND, "Distrust",
      `Grind logged as 0 clicks, which is burrs touching. Nothing brews${runFor} at 0, so the grind was not recorded.`,
      "Grind was logged as zero, which is burrs touching and cannot brew anything, so the grind was not recorded.",
    );
  } else if (clicks != null && filter && underOneLegacyTurn(clicks, scale, method)) {
    grindTrusted = false;
    add(
      "GRIND_UNDER_ONE_TURN", Fields.GRIND, "Distrust",
      `${clicks} clicks is less than one full turn, finer than espresso. For a filter brew that is almost certainly the dial reading rather than total clicks; ${clicks} on the dial would be ${clicks * cpr} clicks.`,
      // No corrected number: the model must not be handed a guess to reason from.
      "The grind is logged as less than one full turn, finer than espresso, which can't be right for a filter brew. It was probably the dial reading rather than total clicks.",
    );
  } else if (clicks != null && filter && scale != null && finerThanEspresso(clicks, scale)) {
    grindTrusted = false;
    const range = rangeFor(scale, "espresso");
    const who = scale.rangeSources[0]?.who ? `, ${scale.rangeSources[0].who}` : "";
    const stored = scale.dial.kind === "number-tick" ? " On this grinder the dial reading 7.5 is stored as 75." : "";
    const from = range ? format(scale, range.from) : format(scale, espressoFloor(scale) ?? 0);
    const to = range ? format(scale, range.to) : from;
    add(
      "GRIND_TOO_FINE_FOR_METHOD", Fields.GRIND, "Distrust",
      `${clicks} is finer than this grinder's espresso starting range (${from}–${to}${who}). For a filter brew that is almost certainly the dial reading typed the wrong way.${stored}`,
      "The grind is logged finer than this grinder's espresso starting range, which can't be right for a filter brew. It was probably the dial reading rather than total clicks.",
    );
  }
  if (clicks != null && clicks > 0 && grindTrusted) {
    const usual = peers
      .filter((p) => p.id !== log.id && p.recipeId === log.recipeId && p.beanId === log.beanId && p.grinderId === log.grinderId && (p.grindClicks ?? 0) > 0)
      .map((p) => p.grindClicks!);
    if (usual.length > 0) {
      const med = median(usual);
      const away = Math.abs(clicks - med);
      const limit = mv?.jumpLimit ?? (cpr > 0 ? 2 * cpr : 20);
      if (away >= limit) {
        add("GRIND_JUMP", Fields.GRIND, "Note", `${clicks} clicks is ${grams(away)} away from your usual ${grams(med)} for this coffee and recipe. If that wasn't deliberate, it was probably mistyped.`);
      }
    }
  }
  // Pre-ground coffee has no grind to record (v4 §4.2); a blade grinder records seconds in grindValue.
  if (log.grindKind !== "preground" && clicks == null && log.grindValue == null && !log.grindSetting.trim()) {
    add("GRIND_MISSING", Fields.GRIND, "Note", "No grind setting recorded.");
  }

  // ---- dose and water
  const dose = log.doseG;
  const water = log.waterG;
  let ratioTrusted = true;
  if (dose <= 0 || water <= 0) {
    ratioTrusted = false;
    add("RATIO_IMPLAUSIBLE", Fields.RATIO, "Distrust", "Dose or water is missing.");
  } else if (filter) {
    const r = water / dose;
    if (r < FILTER_RATIO_MIN || r > FILTER_RATIO_MAX) {
      ratioTrusted = false;
      add(
        "RATIO_IMPLAUSIBLE", Fields.RATIO, "Distrust",
        `${grams(dose)} g to ${grams(water)} g is 1:${oneDp(r)}, far outside any filter recipe; dose or water is probably mistyped.`,
        "Dose and water give a ratio far outside any filter recipe; one of them is probably mistyped.",
      );
    }
  }

  // ---- temperature
  // Cold brew and a cezve both start from cold water.
  if (method !== "coldBrew" && method !== "turkish" && (log.tempC < 60 || log.tempC > 100)) {
    add("TEMP_IMPLAUSIBLE", Fields.TEMP, "Distrust", `${log.tempC} °C can't be right for a hot brew.`, "The logged water temperature can't be right for a hot brew.");
  }

  // ---- total time (checked before drawdown, which leans on it)
  let timeTrusted = true;
  if (total <= 0) {
    timeTrusted = false;
    add("TIME_IMPLAUSIBLE", Fields.TIME, "Distrust", "No total time recorded.");
  } else if (recipe != null && recipeTotal > 0 && (total < 0.4 * recipeTotal || total > 2.5 * recipeTotal)) {
    timeTrusted = false;
    add(
      "TIME_IMPLAUSIBLE", Fields.TIME, "Distrust",
      `Total time ${clock(total)} against the recipe's ${clock(recipeTotal)}: the timer was probably stopped early or left running.`,
      `The total time is too far from the recipe's ${clock(recipeTotal)} to be real: the timer was probably stopped early or left running.`,
    );
  }

  // ---- drawdown
  const drawdown = log.drawdownSec;
  const lastPourEnd = lastPourEndSec(recipe);
  if (drawdown != null) {
    if (total > 0 && drawdown >= total) {
      add(
        "DRAWDOWN_GE_TOTAL", Fields.DRAWDOWN, "Distrust",
        `Drawdown ${drawdown} s is as long as the whole brew (${clock(total)}). The total time was probably typed into the drawdown box.`,
        "Drawdown is as long as the whole brew, so the total time was probably typed into the drawdown box.",
      );
    } else if (lastPourEnd != null && timeTrusted && drawdown > total - lastPourEnd + DRAWDOWN_SLACK_SEC) {
      const max = Math.max(0, total - lastPourEnd);
      add(
        "DRAWDOWN_AFTER_END", Fields.DRAWDOWN, "Distrust",
        `Drawdown ${drawdown} s doesn't fit: the last pour ends at ${clock(lastPourEnd)} and the brew stopped at ${clock(total)}, which leaves at most ${max} s.`,
        `Drawdown doesn't fit: the last pour ends at ${clock(lastPourEnd)} and the brew stopped at ${clock(total)}, which leaves at most ${max} s.`,
      );
    } else if (drawdown === 0) {
      add(
        "DRAWDOWN_ZERO", Fields.DRAWDOWN, "Distrust",
        "Drawdown 0 s: the bed-exposed button was tapped before the last pour finished.",
        "Drawdown was logged as zero: the bed-exposed button was tapped before the last pour finished.",
      );
    }
  }

  // ---- cup weight and extraction
  const bev = log.beverageG != null && log.beverageG > 0 ? log.beverageG : null;
  let beverageTrusted = true;
  if (bev != null && filter && ratioTrusted) {
    const retained = retainedPerGram(log);
    if (bev >= water) {
      beverageTrusted = false;
      add(
        "BEVERAGE_OVER_WATER", Fields.BEVERAGE, "Distrust",
        `${grams(bev)} g in the cup from ${grams(water)} g of water is more than went in; the carafe or brewer was probably still on the scale.`,
        "The cup weight is more than the water that went in; the carafe or brewer was probably still on the scale.",
      );
    } else if (retained != null && retained < RETENTION_LOW_G_PER_G) {
      beverageTrusted = false;
      add(
        "RETENTION_LOW", Fields.BEVERAGE, "Distrust",
        `${grams(bev)} g in the cup leaves only ${grams(water - bev)} g in ${grams(dose)} g of coffee (${oneDp(retained)} g per g). Wet grounds hold about 2 g per g, so the cup weight is wrong.`,
        "The cup weight leaves far less water in the grounds than wet coffee holds (about 2 g per g), so the cup weight is wrong.",
      );
    } else if (retained != null && retained > RETENTION_HIGH_G_PER_G) {
      beverageTrusted = false;
      add(
        "RETENTION_HIGH", Fields.BEVERAGE, "Distrust",
        `${grams(bev)} g in the cup means ${grams(water - bev)} g stayed in ${grams(dose)} g of coffee (${oneDp(retained)} g per g). Wet grounds hold about 2 g per g, so the cup was probably weighed early or some was poured off.`,
        "The cup weight means far more water stayed in the grounds than wet coffee holds (about 2 g per g), so the cup was probably weighed early or some was poured off.",
      );
    }
  }
  const ey = log.extractionYield;
  if (ey != null) {
    if (!beverageTrusted) {
      add("EY_IMPLAUSIBLE", Fields.EY, "Distrust", `Extraction yield ${oneDp(ey)}% was worked out from a cup weight that looks wrong.`, "The extraction yield was worked out from a cup weight that looks wrong.");
    } else if (ey < EY_MIN || ey > EY_MAX) {
      const kind = filter ? "any filter brew" : "any brew";
      add("EY_IMPLAUSIBLE", Fields.EY, "Distrust", `Extraction yield ${oneDp(ey)}% is outside the 14-26% ${kind} reaches.`, `The extraction yield is outside the 14-26% ${kind} reaches.`);
    }
  }

  // ---- taste and rating
  const taste = log.taste;
  if (sameTaste(taste)) {
    add("TASTE_UNTOUCHED", Fields.TASTE, "Distrust", "All seven taste sliders are still on the default 5, so they were never moved. The faults, overall score and notes are the real taste record.");
  } else {
    if (log.faults.includes("Sour") && taste.acidity <= 3) {
      add("TASTE_FAULT_CONFLICT", Fields.TASTE, "Note", `Marked Sour but acidity is only ${taste.acidity}/10; the chip and the slider disagree.`);
    }
    if (log.faults.includes("Bitter") && taste.bitterness <= 2) {
      add("TASTE_FAULT_CONFLICT", Fields.TASTE, "Note", `Marked Bitter but bitterness is only ${taste.bitterness}/10; the chip and the slider disagree.`);
    }
  }
  if (log.overall === 0) {
    add("OVERALL_UNRATED", Fields.OVERALL, "Note", "No overall score, so this brew is left out of score comparisons.");
  }

  // ---- coffee
  const beanRecorded = log.beanId != null || log.beanLabel.trim() !== "";
  if (beanRecorded && log.daysOffRoast == null) {
    const name = bean ? beanDisplay(bean) : log.beanLabel.trim() || "this coffee";
    const light = bean && LIGHT_ROASTS.has(bean.roastLevelId) ? ", and on a light roast the first three weeks change a lot" : "";
    add("ROAST_DATE_MISSING", Fields.ROAST, "Note", `No roast date for ${name}, so freshness can't be judged${light}.`);
  }
  if (!beanRecorded) add("BEAN_MISSING", Fields.BEAN, "Note", "No coffee recorded for this brew.");

  return flags;
}

export function distrustedFields(flags: readonly DataFlag[], brewId: number | undefined): Set<string> {
  const out = new Set<string>();
  for (const f of flags) if (f.brewId === (brewId ?? 0) && f.severity === "Distrust") out.add(f.field);
  return out;
}

function sameRecipe(a: BrewRow, b: BrewRow): boolean {
  return b.recipeId.trim() ? a.recipeId === b.recipeId : !a.recipeId.trim() && a.recipeName === b.recipeName;
}

/**
 * The newest earlier brew whose grind can anchor a change: same grinder AND same recipe (the
 * same coffee preferred), with a grind that is not itself distrusted. `value` picks numbered /
 * stepless dials (grindValue) instead of clicks.
 */
export function lastTrusted(target: BrewRow, history: readonly BrewRow[], flagsById: ReadonlyMap<number, readonly DataFlag[]>, value = false): BrewRow | null {
  const grinderId = target.grinderId;
  if (grinderId == null) return null;
  const candidates = history.filter(
    (h) =>
      h.id !== target.id &&
      h.grinderId === grinderId &&
      sameRecipe(h, target) &&
      (value ? h.grindValue != null && h.grindValue > 0 : (h.grindClicks ?? 0) > 0) &&
      !distrustedFields(flagsById.get(h.id ?? 0) ?? [], h.id).has(Fields.GRIND),
  );
  return candidates.find((c) => c.beanId === target.beanId) ?? candidates[0] ?? null;
}

export function groupFlags(flags: readonly DataFlag[]): Map<number, DataFlag[]> {
  const m = new Map<number, DataFlag[]>();
  for (const f of flags) {
    const list = m.get(f.brewId);
    if (list) list.push(f);
    else m.set(f.brewId, [f]);
  }
  return m;
}
