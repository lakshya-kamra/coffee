/**
 * Port of domain/Recipe.kt (+ waterAtF / stepBoundaries from BrewViewModel.kt) and the A§2.6
 * serialization: brewer serialized as its id (legacy enum names resolve), accent written as
 * "#RRGGBB" ("#AARRGGBB" when not opaque) and read as a hex string OR a 1.x JSON number.
 */
import type { Brewer } from "./brewer";
import { oneDp, roundToInt } from "./format";
import { decodeRecipeVideo, type RecipeVideo } from "./recipeVideo";

export const STEP_KINDS = ["Prep", "Bloom", "Pour", "Swirl", "Stir", "Steep", "Press", "Drawdown", "Serve"] as const;
export type StepKind = (typeof STEP_KINDS)[number];

export type Fidelity = "published" | "adapted" | "community" | "custom" | "";

/** How much a recipe asks of you (v4 §6.4); "" = not said (user recipes). */
export type RecipeLevel = "" | "easy" | "medium" | "advanced";
export const RECIPE_LEVELS: readonly RecipeLevel[] = ["", "easy", "medium", "advanced"];

/** Kit a recipe really depends on. */
export type RecipeNeed = "scale" | "gooseneck" | "grinder" | "thermometer";
export const RECIPE_NEEDS: readonly RecipeNeed[] = ["scale", "gooseneck", "grinder", "thermometer"];

export interface RecipeSource {
  readonly who: string;
  readonly url: string;
  readonly note: string;
}

/** One instruction. startSec/endSec are offsets from the timer start; targetWaterG is CUMULATIVE grams on the scale. */
export interface RecipeStep {
  readonly kind: StepKind;
  readonly title: string;
  readonly detail: string;
  readonly startSec: number;
  readonly endSec: number;
  readonly targetWaterG: number | null;
}

export interface Recipe {
  readonly id: string;
  readonly name: string;
  readonly author: string;
  readonly brewer: Brewer;
  readonly doseG: number;
  readonly waterG: number;
  readonly grind: string;
  readonly tempC: number;
  readonly blurb: string;
  /** Where these numbers come from, and how much to trust them. Shown in the UI. */
  readonly sourceNote: string;
  readonly prep: readonly string[];
  readonly tips: readonly string[];
  readonly steps: readonly RecipeStep[];
  /** 0xAARRGGBB as an unsigned number, like Kotlin's Long. */
  readonly accent: number;
  readonly isBuiltIn: boolean;
  readonly fidelity: Fidelity;
  readonly sources: readonly RecipeSource[];
  readonly basedOn: string | null;
  readonly level: RecipeLevel;
  readonly needs: readonly RecipeNeed[];
  /**
   * Prep lines for someone without a scale; they replace the lines that mention one. "{coffee}"
   * and "{water}" are filled in by noScalePrep.
   */
  readonly prepNoScale: readonly string[];
  /**
   * Built-ins only: the expert's own video of this recipe (shared/recipes.json). Absent when there
   * is none. Not written by encodeRecipe: saved recipes and backups never carry it.
   */
  readonly video?: RecipeVideo;
}

/** The JSON shape in shared/recipes.json and in saved recipes (schema/recipes.md). */
export interface RecipeJson {
  id: string;
  name: string;
  author: string;
  brewer: string;
  doseG: number;
  waterG: number;
  grind: string;
  tempC: number;
  blurb: string;
  sourceNote: string;
  prep: string[];
  tips: string[];
  steps: { kind: StepKind; title: string; detail: string; startSec: number; endSec: number; targetWaterG?: number | null }[];
  accent: string;
  isBuiltIn?: boolean;
  fidelity?: string;
  sources?: { who: string; url?: string; note?: string }[];
  basedOn?: string | null;
  level?: RecipeLevel;
  needs?: RecipeNeed[];
  prepNoScale?: string[];
  video?: { youtubeId: string; title?: string; channel?: string };
}

export function stepDurationSec(s: RecipeStep): number {
  return Math.max(0, s.endSec - s.startSec);
}

export function recipeRatio(r: Recipe): number {
  return r.doseG > 0 ? r.waterG / r.doseG : 0;
}

export function recipeRatioLabel(r: Recipe): string {
  return "1:" + oneDp(recipeRatio(r));
}

export function totalSec(r: Recipe): number {
  let max = 0;
  for (const s of r.steps) if (s.endSec > max) max = s.endSec;
  return max;
}

/** Proportionally rescale the whole recipe to a new dose, keeping timings intact. */
export function scaledToDose(r: Recipe, newDose: number): Recipe {
  if (newDose <= 0 || r.doseG <= 0) return r;
  const k = newDose / r.doseG;
  return {
    ...r,
    doseG: roundToInt(newDose * 10) / 10,
    waterG: roundToInt(r.waterG * k),
    steps: r.steps.map((s) => ({ ...s, targetWaterG: s.targetWaterG == null ? null : roundToInt(s.targetWaterG * k) })),
  };
}

/** Proportionally rescale to a target water weight. */
export function scaledToWater(r: Recipe, newWater: number): Recipe {
  return r.waterG <= 0 ? r : scaledToDose(r, r.doseG * (newWater / r.waterG));
}

/** Unrounded cumulative water target at elapsedMs, interpolated inside the current pour. */
export function waterAtF(r: Recipe, elapsedMs: number): number {
  const e = elapsedMs / 1000;
  let previousTarget = 0;
  for (const s of r.steps) {
    const target = s.targetWaterG;
    if (target == null) continue;
    if (e >= s.endSec) {
      previousTarget = target;
    } else if (e > s.startSec) {
      const f = Math.min(1, Math.max(0, (e - s.startSec) / Math.max(1, s.endSec - s.startSec)));
      return previousTarget + (target - previousTarget) * f;
    } else {
      return previousTarget;
    }
  }
  return previousTarget;
}

export function waterAt(r: Recipe, elapsedMs: number): number {
  return roundToInt(waterAtF(r, elapsedMs));
}

/** Fractional positions of every step boundary, for the ring ticks. */
export function stepBoundaries(r: Recipe): number[] {
  const total = Math.max(1, totalSec(r));
  return r.steps.slice(0, -1).map((s) => s.endSec / total);
}

// ---------------------------------------------------------------- accent colours

const OPAQUE = 0xff000000;

/** "#RRGGBB" / "#AARRGGBB" / a JSON number (1.x saved recipes) → unsigned ARGB, or null. */
export function parseAccent(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) {
    // 1.x wrote Kotlin Long values (4293503061); ToUint32 also maps an Int-style negative to the same bits.
    return Math.trunc(v) >>> 0;
  }
  if (typeof v !== "string") return null;
  const m = /^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.exec(v.trim());
  if (!m || !m[1]) return null;
  const hex = m[1];
  return hex.length === 6 ? (OPAQUE | parseInt(hex, 16)) >>> 0 : parseInt(hex, 16) >>> 0;
}

function hex2(n: number): string {
  return n.toString(16).toUpperCase().padStart(2, "0");
}

/** "#E9A855", or "#80E9A855" when not opaque. */
export function accentHex(argb: number): string {
  const a = (argb >>> 24) & 0xff;
  const rgb = hex2((argb >>> 16) & 0xff) + hex2((argb >>> 8) & 0xff) + hex2(argb & 0xff);
  return a === 0xff ? "#" + rgb : "#" + hex2(a) + rgb;
}

/** A CSS colour for an ARGB accent. */
export function accentCss(argb: number): string {
  const a = (argb >>> 24) & 0xff;
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  return a === 0xff ? accentHex(argb) : `rgb(${r} ${g} ${b} / ${Number((a / 255).toFixed(3))})`;
}

// ---------------------------------------------------------------- (de)serialization

const FIDELITIES: readonly Fidelity[] = ["published", "adapted", "community", "custom", ""];

function str(v: unknown, fallback = ""): string {
  return typeof v === "string" ? v : fallback;
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function strList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
}

export type DecodeResult<T> = { ok: true; value: T } | { ok: false; error: string };

/**
 * Lenient decode of one recipe JSON object. `resolveBrewer` maps the stored string (an id, or a
 * 1.x enum name / label) to a Brewer and never throws. Missing optional fields get defaults.
 */
export function decodeRecipe(raw: unknown, resolveBrewer: (s: string) => Brewer, isBuiltInDefault = true): DecodeResult<Recipe> {
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) return { ok: false, error: "not an object" };
  const o = raw as Record<string, unknown>;
  const id = str(o.id).trim();
  if (!id) return { ok: false, error: "missing id" };
  const name = str(o.name).trim();
  if (!name) return { ok: false, error: "missing name" };
  const doseG = num(o.doseG);
  const waterG = num(o.waterG);
  const tempC = num(o.tempC);
  if (doseG == null || waterG == null) return { ok: false, error: "missing doseG/waterG" };
  if (tempC == null) return { ok: false, error: "missing tempC" };
  if (!Array.isArray(o.steps)) return { ok: false, error: "missing steps" };
  const steps: RecipeStep[] = [];
  for (const [i, s] of o.steps.entries()) {
    if (s == null || typeof s !== "object") return { ok: false, error: `steps[${i}] not an object` };
    const st = s as Record<string, unknown>;
    const kind = str(st.kind) as StepKind;
    if (!STEP_KINDS.includes(kind)) return { ok: false, error: `steps[${i}] unknown kind ${String(st.kind)}` };
    const startSec = num(st.startSec);
    const endSec = num(st.endSec);
    if (startSec == null || endSec == null) return { ok: false, error: `steps[${i}] missing startSec/endSec` };
    const target = num(st.targetWaterG);
    steps.push({
      kind,
      title: str(st.title),
      detail: str(st.detail),
      startSec: Math.trunc(startSec),
      endSec: Math.trunc(endSec),
      targetWaterG: target == null ? null : Math.trunc(target),
    });
  }
  const accent = parseAccent(o.accent);
  if (accent == null) return { ok: false, error: "bad accent" };
  const fidelityRaw = str(o.fidelity);
  const fidelity: Fidelity = (FIDELITIES as readonly string[]).includes(fidelityRaw) ? (fidelityRaw as Fidelity) : "";
  const sources: RecipeSource[] = Array.isArray(o.sources)
    ? o.sources
        .filter((x): x is Record<string, unknown> => x != null && typeof x === "object")
        .map((x) => ({ who: str(x.who), url: str(x.url), note: str(x.note) }))
        .filter((x) => x.who !== "")
    : [];
  const video = decodeRecipeVideo(o.video);
  return {
    ok: true,
    value: Object.freeze({
      id,
      name,
      author: str(o.author),
      brewer: resolveBrewer(str(o.brewer)),
      doseG,
      waterG,
      grind: str(o.grind),
      tempC: Math.trunc(tempC),
      blurb: str(o.blurb),
      sourceNote: str(o.sourceNote),
      prep: strList(o.prep),
      tips: strList(o.tips),
      steps,
      accent,
      isBuiltIn: typeof o.isBuiltIn === "boolean" ? o.isBuiltIn : isBuiltInDefault,
      fidelity,
      sources,
      basedOn: typeof o.basedOn === "string" && o.basedOn ? o.basedOn : null,
      level: (RECIPE_LEVELS as readonly string[]).includes(str(o.level)) ? (str(o.level) as RecipeLevel) : "",
      needs: RECIPE_NEEDS.filter((n) => strList(o.needs).includes(n)),
      prepNoScale: strList(o.prepNoScale),
      ...(video ? { video } : {}),
    }),
  };
}

/** Encode for saved recipes and backups: brewer as id, accent as hex. */
export function encodeRecipe(r: Recipe): RecipeJson {
  return {
    id: r.id,
    name: r.name,
    author: r.author,
    brewer: r.brewer.id,
    doseG: r.doseG,
    waterG: r.waterG,
    grind: r.grind,
    tempC: r.tempC,
    blurb: r.blurb,
    sourceNote: r.sourceNote,
    prep: [...r.prep],
    tips: [...r.tips],
    steps: r.steps.map((s) => ({
      kind: s.kind,
      title: s.title,
      detail: s.detail,
      startSec: s.startSec,
      endSec: s.endSec,
      targetWaterG: s.targetWaterG,
    })),
    accent: accentHex(r.accent),
    isBuiltIn: r.isBuiltIn,
    fidelity: r.fidelity,
    sources: r.sources.map((s) => ({ who: s.who, url: s.url, note: s.note })),
    basedOn: r.basedOn,
    level: r.level,
    needs: [...r.needs],
    prepNoScale: [...r.prepNoScale],
  };
}

/** Words for the fidelity chip on the recipe detail (web-spec §11). */
export function fidelityWord(f: Fidelity, isBuiltIn: boolean): string {
  switch (f) {
    case "published": return "Published numbers";
    case "adapted": return "Adapted";
    case "community": return "Community";
    case "custom": return "Yours";
    default: return isBuiltIn ? "Adapted" : "Yours";
  }
}
