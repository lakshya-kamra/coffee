/**
 * Port of A§7.7 `RecipeValidation.check(recipe)`: pure, one issue per broken rule, in the order
 * the editor shows them. Every rule here blocks Save; the last-pour rule also offers a one-tap fix.
 */
import { RESERVED_RECIPE_IDS } from "@/app/routes";
import { groupedInt } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";

export type IssueCode =
  | "name"
  | "reserved-id"
  | "dose"
  | "water"
  | "temp"
  | "step-count"
  | "step-duration"
  | "total-time"
  | "pour-order"
  | "last-pour";

export type IssueField = "name" | "dose" | "water" | "temp" | "steps";

export interface Issue {
  readonly code: IssueCode;
  readonly field: IssueField;
  /** Index into recipe.steps for step rules. */
  readonly stepIndex?: number;
  readonly message: string;
  readonly blocking: boolean;
  /** The one-tap fix, if any: today only "Set the last pour to 500 g". */
  readonly fix?: { readonly label: string; readonly waterG: number };
}

export const LIMITS = Object.freeze({
  nameMax: 60,
  doseMin: 1,
  doseMax: 100,
  waterMin: 10,
  waterMax: 2000,
  tempMin: 0,
  tempMax: 100,
  stepsMin: 1,
  stepsMax: 30,
  totalMaxSec: 3600,
  coldBrewTotalMaxSec: 86_400,
});

function g(v: number): string {
  return `${groupedInt(v)} g`;
}

export function check(recipe: Recipe): Issue[] {
  const issues: Issue[] = [];
  const add = (i: Omit<Issue, "blocking">) => issues.push({ ...i, blocking: true });
  const name = recipe.name.trim();
  if (name.length < 1 || name.length > LIMITS.nameMax) add({ code: "name", field: "name", message: `Give it a name of 1 to ${LIMITS.nameMax} characters.` });
  if (RESERVED_RECIPE_IDS.has(recipe.id)) add({ code: "reserved-id", field: "name", message: `"${recipe.id}" can't be a recipe id.` });
  if (!(recipe.doseG >= LIMITS.doseMin && recipe.doseG <= LIMITS.doseMax)) add({ code: "dose", field: "dose", message: `Dose needs to be between ${LIMITS.doseMin} and ${LIMITS.doseMax} g.` });
  if (!(recipe.waterG >= LIMITS.waterMin && recipe.waterG <= LIMITS.waterMax))
    add({ code: "water", field: "water", message: `Water needs to be between ${LIMITS.waterMin} and ${groupedInt(LIMITS.waterMax)} g.` });
  if (!(recipe.tempC >= LIMITS.tempMin && recipe.tempC <= LIMITS.tempMax)) add({ code: "temp", field: "temp", message: "Temperature needs to be between 0 and 100 °C." });

  const steps = recipe.steps;
  if (steps.length < LIMITS.stepsMin || steps.length > LIMITS.stepsMax)
    add({ code: "step-count", field: "steps", message: steps.length === 0 ? "Add at least one step." : `Keep it to ${LIMITS.stepsMax} steps or fewer.` });
  steps.forEach((s, i) => {
    if (s.endSec - s.startSec < 1) add({ code: "step-duration", field: "steps", stepIndex: i, message: `Step ${i + 1} needs to last at least 1 second.` });
  });

  const coldBrew = recipe.brewer.method === "coldBrew";
  const max = coldBrew ? LIMITS.coldBrewTotalMaxSec : LIMITS.totalMaxSec;
  const total = steps.reduce((m, s) => Math.max(m, s.endSec), 0);
  if (total > max)
    add({
      code: "total-time",
      field: "steps",
      message: coldBrew ? "Cold brew can run up to 24 hours; this one is longer." : "The whole brew runs over an hour. Only cold brew can run longer.",
    });

  // Pour targets are cumulative grams on the scale, so they can only go up.
  let lastTarget: { i: number; g: number } | null = null;
  steps.forEach((s, i) => {
    if (s.targetWaterG == null) return;
    if (lastTarget && s.targetWaterG < lastTarget.g)
      add({ code: "pour-order", field: "steps", stepIndex: i, message: `Step ${i + 1} pours to ${g(s.targetWaterG)}, less than step ${lastTarget.i + 1} (${g(lastTarget.g)}). The scale only goes up.` });
    lastTarget = { i, g: s.targetWaterG };
  });
  const final = lastTarget as { i: number; g: number } | null;
  if (final && recipe.waterG > 0 && final.g !== recipe.waterG)
    add({
      code: "last-pour",
      field: "steps",
      stepIndex: final.i,
      message: `The last pour ends at ${g(final.g)}, but the recipe uses ${g(recipe.waterG)}.`,
      fix: { label: `Set the last pour to ${g(recipe.waterG)}`, waterG: recipe.waterG },
    });
  return issues;
}

export function hasBlocking(issues: readonly Issue[]): boolean {
  return issues.some((i) => i.blocking);
}
