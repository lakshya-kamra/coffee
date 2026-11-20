/**
 * The recipe builder's draft (A§7.7): what the editor holds while you type. Numbers you type stay
 * text until save, so "1." or an empty field is never forced into a number mid-keystroke. Steps
 * carry a duration and (for pours) the cumulative grams on the scale; start/end seconds are
 * derived cumulatively on save, so reordering or resizing a step can never leave a gap.
 */
import type { Brewer } from "@/domain/core/brewer";
import { grams } from "@/domain/core/format";
import { STEP_KINDS, type Fidelity, type Recipe, type RecipeLevel, type RecipeNeed, type RecipeSource, type RecipeStep, type StepKind } from "@/domain/core/recipe";

export type EditorMode = "new" | "edit" | "duplicate";

export interface DraftStep {
  /** Stable React key; never saved. */
  readonly key: string;
  readonly kind: StepKind;
  readonly title: string;
  readonly detail: string;
  readonly durationSec: number;
  /** Cumulative grams on the scale when this step ends; "" = no target. */
  readonly pourTo: string;
}

export interface RecipeDraft {
  readonly id: string;
  readonly name: string;
  readonly author: string;
  readonly brewerId: string;
  readonly dose: string;
  readonly water: string;
  /** Always °C (the stepper moves 1 °C; °F is display only, A§7.8). */
  readonly tempC: number;
  readonly grind: string;
  readonly blurb: string;
  readonly prep: readonly string[];
  readonly tips: readonly string[];
  readonly steps: readonly DraftStep[];
  readonly accent: number;
  readonly fidelity: Fidelity;
  readonly sources: readonly RecipeSource[];
  readonly basedOn: string | null;
  readonly sourceNote: string;
  /** Carried through unedited, so saving a recipe never drops them (v4 §6.4). */
  readonly level: RecipeLevel;
  readonly needs: readonly RecipeNeed[];
  readonly prepNoScale: readonly string[];
}

/** Steps that put water on the scale: the editor always offers "pour to" for these. */
export const POUR_KINDS: ReadonlySet<StepKind> = new Set<StepKind>(["Bloom", "Pour"]);

export { STEP_KINDS };

/** The accent a brand-new recipe starts with (the app's crema-adjacent amber from recipes.json). */
export const NEW_RECIPE_ACCENT = 0xffe9a855;

let keySeq = 0;
export function stepKey(): string {
  keySeq += 1;
  return `s${keySeq}`;
}

/** "custom-" + 8 lowercase hex (A§7.7). */
export function newCustomId(rand: () => number = Math.random): string {
  let hex = "";
  for (let i = 0; i < 8; i++) hex += Math.floor(rand() * 16).toString(16);
  return `custom-${hex}`;
}

/** "18", "18.5", "1,5" → 18.5; "" or junk → null. */
export function parseAmount(text: string): number | null {
  const t = text.trim().replace(",", ".");
  if (!/^\d+(\.\d*)?$|^\.\d+$/.test(t)) return null;
  const v = Number(t);
  return Number.isFinite(v) ? v : null;
}

function amountText(v: number | null | undefined): string {
  return v == null ? "" : grams(v);
}

export function draftStepFrom(s: RecipeStep): DraftStep {
  return { key: stepKey(), kind: s.kind, title: s.title, detail: s.detail, durationSec: Math.max(0, s.endSec - s.startSec), pourTo: amountText(s.targetWaterG) };
}

export function draftFromRecipe(r: Recipe): RecipeDraft {
  return {
    id: r.id,
    name: r.name,
    author: r.author,
    brewerId: r.brewer.id,
    dose: amountText(r.doseG),
    water: amountText(r.waterG),
    tempC: r.tempC,
    grind: r.grind,
    blurb: r.blurb,
    prep: [...r.prep],
    tips: [...r.tips],
    steps: r.steps.map(draftStepFrom),
    accent: r.accent,
    fidelity: r.fidelity,
    sources: r.sources,
    basedOn: r.basedOn,
    sourceNote: r.sourceNote,
    level: r.level,
    needs: r.needs,
    prepNoScale: r.prepNoScale,
  };
}

/** The blank V60-style template: one Bloom step, numbers left for you to fill in. */
export function blankDraft(author: string, rand?: () => number): RecipeDraft {
  return {
    id: newCustomId(rand),
    name: "",
    author: author.trim() || "You",
    brewerId: "hario-v60-02",
    dose: "",
    water: "",
    // The stepper needs a starting point; you set the real number (not attributed to anyone).
    tempC: 93,
    grind: "",
    blurb: "",
    prep: [],
    tips: [],
    steps: [{ key: stepKey(), kind: "Bloom", title: "Bloom", detail: "", durationSec: 45, pourTo: "" }],
    accent: NEW_RECIPE_ACCENT,
    fidelity: "custom",
    sources: [],
    basedOn: null,
    sourceNote: "",
    level: "",
    needs: [],
    prepNoScale: [],
  };
}

/** Duplicate (A§7.7): a new custom id, "(mine)", your name, and a note saying where it came from. */
export function duplicateDraft(original: Recipe, author: string, rand?: () => number): RecipeDraft {
  const d = draftFromRecipe(original);
  const who = original.author.trim();
  return {
    ...d,
    id: newCustomId(rand),
    name: `${original.name} (mine)`,
    author: author.trim() || "You",
    fidelity: "custom",
    basedOn: original.id,
    sources: original.sources,
    sourceNote: who ? `Based on ${who}'s ${original.name}.` : `Based on ${original.name}.`,
  };
}

/**
 * Editing a built-in saves an override under the same id. Its numbers are now yours, so the
 * chip says "Yours" and the note says how to get the published version back.
 */
export function overrideNote(original: Recipe): string {
  const who = original.author.trim();
  return `Your version of ${who ? `${who}'s ` : ""}${original.name}. Reset to original for the published numbers.`;
}

/** Draft → Recipe. Unparseable numbers become 0, which validation then reports. */
export function recipeFromDraft(d: RecipeDraft, resolveBrewer: (id: string) => Brewer): Recipe {
  let t = 0;
  const steps: RecipeStep[] = d.steps.map((s) => {
    const startSec = t;
    const endSec = t + Math.max(0, Math.round(s.durationSec));
    t = endSec;
    const target = parseAmount(s.pourTo);
    return { kind: s.kind, title: s.title.trim() || s.kind, detail: s.detail.trim(), startSec, endSec, targetWaterG: target == null ? null : Math.round(target) };
  });
  return Object.freeze({
    id: d.id,
    name: d.name.trim(),
    author: d.author.trim(),
    brewer: resolveBrewer(d.brewerId),
    doseG: parseAmount(d.dose) ?? 0,
    waterG: parseAmount(d.water) ?? 0,
    grind: d.grind.trim(),
    tempC: Math.round(d.tempC),
    blurb: d.blurb.trim(),
    sourceNote: d.sourceNote,
    prep: d.prep.map((p) => p.trim()).filter(Boolean),
    tips: d.tips.map((p) => p.trim()).filter(Boolean),
    steps,
    accent: d.accent,
    isBuiltIn: false,
    fidelity: d.fidelity,
    sources: d.sources,
    basedOn: d.basedOn,
    level: d.level,
    needs: d.needs,
    prepNoScale: d.prepNoScale.map((p) => p.trim()).filter(Boolean),
  });
}

// ---------------------------------------------------------------- step operations (pure)

export function totalDurationSec(d: RecipeDraft): number {
  return d.steps.reduce((t, s) => t + Math.max(0, s.durationSec), 0);
}

/** "+N g this pour": grams added since the previous target, or null when this step has none. */
export function pourDelta(d: RecipeDraft, index: number): number | null {
  const here = parseAmount(d.steps[index]?.pourTo ?? "");
  if (here == null) return null;
  let prev = 0;
  for (let i = index - 1; i >= 0; i--) {
    const p = parseAmount(d.steps[i]!.pourTo);
    if (p != null) {
      prev = p;
      break;
    }
  }
  return Math.round(here - prev);
}

export function updateStep(d: RecipeDraft, index: number, patch: Partial<Omit<DraftStep, "key">>): RecipeDraft {
  return { ...d, steps: d.steps.map((s, i) => (i === index ? { ...s, ...patch } : s)) };
}

export function moveStep(d: RecipeDraft, index: number, by: -1 | 1): RecipeDraft {
  const to = index + by;
  if (to < 0 || to >= d.steps.length) return d;
  const steps = [...d.steps];
  [steps[index], steps[to]] = [steps[to]!, steps[index]!];
  return { ...d, steps };
}

export function removeStep(d: RecipeDraft, index: number): RecipeDraft {
  return { ...d, steps: d.steps.filter((_, i) => i !== index) };
}

/** A new Pour after the last step, 30 s long; its target is left for you. */
export function addStep(d: RecipeDraft, kind: StepKind = "Pour"): RecipeDraft {
  return { ...d, steps: [...d.steps, { key: stepKey(), kind, title: kind, detail: "", durationSec: 30, pourTo: "" }] };
}

/** The one-tap fix: the last step with a target pours to the recipe's water. */
export function setLastPour(d: RecipeDraft, waterG: number): RecipeDraft {
  for (let i = d.steps.length - 1; i >= 0; i--) {
    if (parseAmount(d.steps[i]!.pourTo) != null) return updateStep(d, i, { pourTo: grams(waterG) });
  }
  return d;
}

/** Duration stepper: ±5 s on a tap, ±1 s while held; never below 1 s. */
export function nudgeDuration(d: RecipeDraft, index: number, by: number): RecipeDraft {
  const s = d.steps[index];
  if (!s) return d;
  return updateStep(d, index, { durationSec: Math.max(1, s.durationSec + by) });
}
