/**
 * Port of domain/ai/AnalysisPrompt.kt at VERSION 2 (A§8.5): brewer, water, photos, units,
 * principles, the grinder's own scale (no grindSetting text for click grinders) and the richer
 * coffee block. Deterministic on purpose: absolute dates in UTC, fixed key order, nothing derived
 * from "now", so the same data always gives the same inputHash. Every field DataQuality distrusts
 * is sent as null together with every value worked out from it.
 *
 * Parity with Android is semantic, not byte-for-byte (web-spec §10): hashes are only compared
 * with prompts built on the same platform. The hash names records by uid, never by row id, so it
 * is the same before and after a restore.
 */
import { catalog } from "@/catalog/catalog";
import type { CatalogWater, Principle } from "@/catalog/types";
import { brewMethodLabel, type Brewer, type BrewMethod } from "@/domain/core/brewer";
import { clock, oneDp } from "@/domain/core/format";
import { bedShapeLabel, channelingLabel, flowFeelLabel, sludgeLabel, type TasteProfile } from "@/domain/core/evaluation";
import { recipeRatioLabel, totalSec, type Recipe } from "@/domain/core/recipe";
import { beanDisplay, grinderDisplay, type BeanRow, type BrewRow, type GrinderRow, type PhotoKind } from "@/data/schema";
import { encodeDialFormat, finerDirection, format as fmtGrind, moves, rangeFor, reading, scaleFor, showsDialNumber, talksInTurns, usesClicks, type GrindScale, type MoveSizes } from "@/domain/grind";
import { brewerOf, checkBrew, distrustedFields, Fields, fieldLabel, groupFlags, lastPourEndSec, lastTrusted, promptMessage, retainedPerGram, type DataFlag } from "./dataQuality";
import { sha256Hex16 } from "./sha256";

export const VERSION = 2;
export const HISTORY_FOR_BREW = 8;
export const TREND_MAX_BREWS = 60;
export const MIN_RATED_FOR_TREND = 3;
export const MAX_PRINCIPLES = 10;
export const MAX_PHOTOS = 3;

/**
 * Starts every inputHash made from uids. A hash without it was made from row ids, which change
 * when a backup is restored, so it can no longer be compared.
 */
export const HASH_SCHEME = "s2:";

/** False for a hash an earlier build stored: it can't say whether the brew was edited. */
export const isCurrentHash = (hash: string): boolean => hash.startsWith(HASH_SCHEME);

// completion_tokens includes reasoning tokens on these models, so the caps leave room for
// thinking; a cut-off reply is billed and shows nothing.
export const BREW_MAX_TOKENS = 6000;
export const TREND_MAX_TOKENS = 8000;
// What a reply usually costs, for the estimate shown before the user taps.
export const BREW_EXPECTED_OUT = 1500;
export const TREND_EXPECTED_OUT = 2000;
export const TEMPERATURE = 0.3;

/** One attached photo, as the prompt names it. Uid and size make a changed photo change the hash. */
export interface PhotoMeta {
  readonly uid: string;
  readonly kind: PhotoKind;
  readonly takenAt: number;
  readonly bytes: number;
}

export interface PromptContext {
  readonly recipes: ReadonlyMap<string, Recipe>;
  readonly beans: ReadonlyMap<number, BeanRow>;
  readonly grinders: ReadonlyMap<number, GrinderRow>;
  /** "c" | "f". */
  readonly units: string;
  readonly principles: readonly Principle[];
}

export interface BrewPromptInput extends PromptContext {
  readonly log: BrewRow;
  readonly recipe: Recipe | null;
  readonly bean: BeanRow | null;
  readonly grinder: GrinderRow | null;
  /** Older brews, newest first, at most HISTORY_FOR_BREW. */
  readonly history: readonly BrewRow[];
  /** The photos that will be attached (already filtered: kind, ticked, vision model, max 3). */
  readonly photos: readonly PhotoMeta[];
  /**
   * The photos the brew has that could be attached, whatever the settings say. The hash is made
   * from these: whether photos are sent is this install's setting, and a restore does not bring
   * it. Missing = the same as `photos`.
   */
  readonly brewPhotos?: readonly PhotoMeta[];
}

export interface TrendPromptInput extends PromptContext {
  /** Newest first, already capped at TREND_MAX_BREWS. */
  readonly logs: readonly BrewRow[];
  readonly totalLogged: number;
}

export interface BuiltPrompt {
  readonly system: string;
  readonly user: string;
  readonly flags: readonly DataFlag[];
  readonly inputHash: string;
  readonly brewCount: number;
  readonly ratedCount: number;
  readonly newestBrewAt: number;
  readonly approxPromptTokens: number;
  readonly maxTokens: number;
  readonly expectedCompletionTokens: number;
  /** The one starting grind in clicks the prompt tells the model to start from. Null on a trend. */
  readonly grindAnchor: number | null;
  /** The same for numbered / stepless dials. */
  readonly valueAnchor: number | null;
  readonly photoCount: number;
}

export const SYSTEM_BREW = `You are an experienced specialty-coffee coach reading one brew from a home brewer's log. The log comes from a coffee app, and the app has already checked it for impossible values.

How to read the data:
- DATA QUALITY lists fields the app has proven unreliable. Those values are sent as null. Never reason from them, never guess what they really were, and mention each one briefly in dataWarnings.
- Taste axes are 0-10. High bitterness is a fault. High acidity is not a fault on a light roast unless the brewer called it sour. overall 0 means not rated.
- Compare what happened with what was planned: totalTimeSec against recipe.targetTotalSec, drawdownSec against recipe.expectedDrawdownSec (the time the recipe leaves after the last pour ends at recipe.lastPourEndsAtSec), and temperature and ratio against the recipe.
- Bed shape, faults and timing are strong evidence. One slider is weak evidence. Earlier brews are context: use them to see what has already been tried, not to diagnose them.
- Grind numbers belong to one grinder and one recipe. Never compare grind numbers across grinders or across recipes.
- PRINCIPLES are from the people whose methods these are. Use them to reason and name the person when you lean on one. Never quote a principle that isn't listed.
- Photos, when attached, are evidence of the bed and the cup. Mention what you see only if it changes the diagnosis; never read a grind setting off a photo.

What to give back:
- A verdict: dialled_in when the cup was good and nothing or almost nothing should change, close when one small nudge is enough, needs_change when it was a clear miss that one setting fixes, fault when something went wrong that a setting alone will not fix (channelling, a stalled bed, a log that cannot be trusted).
- Exactly ONE change for the next brew. Not a list.
- If the change is grind: give exact numbers in this grinder's own units — grindClicksFrom/To for click grinders, grindValueFrom/To for numbered or stepless dials. Follow grinder.scale.finerDirection exactly; grinder.example shows which way is finer. Size the move with grinder.moveSizes: nudge for a small change, clearMiss for a clear miss, never more than maxAtOnce at once. Phrase the instruction the way this person reads the dial (grinder.currentOnDial, grinder.scale.howTheDialReads), e.g. '75 -> 69 clicks (dial 7.5 -> 6.9)'. grinder.startingRange is a sanity check, not a target. If the current grind is unknown, give the change relative to the last brew.
- If the brew was good, the change can be 'none'; then say what to keep.
- Say how the next cup should taste and run if you are right, and how it would taste if you are wrong.
- If the log cannot support a confident answer, say so and set confidence to 'low'. Do not invent measurements.

Style: plain, direct English in the second person, with units (g, s, °C, clicks). No markdown, no emojis, no preamble.
Output: one JSON object exactly matching REPLY SHAPE at the end of the message. No code fences, nothing before or after it.`;

export const SYSTEM_TREND = `You are an experienced specialty-coffee coach reviewing a home brewer's whole log to find what goes wrong repeatedly. The log comes from a coffee app, and the app has already checked it for impossible values.

How to read the data:
- DATA QUALITY lists fields the app has proven unreliable. They are sent as null. Never reason from them; mention them briefly in dataWarnings.
- Taste axes are 0-10. overall 0 means not rated: leave those brews out of any score comparison.
- Look across brews, not at one: which variables change between brews and which never move; what tracks with low and high overall scores (grind, temperature, ratio, total time against the recipe target, drawdown, days off roast, bed shape, coffee, recipe, brewer, water); whether one coffee or recipe keeps failing.
- Count your evidence and name the brew ids behind each pattern. A pattern seen in fewer than 3 brews is 'weak'; if the log cannot show it at all, use 'too_little_data'. 'Only 4 brews' is better than a confident story built on noise.
- Grind numbers belong to one grinder. Obey each grinder's finerDirection, size any grind move with that grinder's moveSizes, and never compare grind numbers across grinders.
- PRINCIPLES are from the people whose methods these are. Use them to reason and name the person when you lean on one. Never quote a principle that isn't listed.

What to give back:
- A trajectory for the log as a whole: improving, steady, stuck or erratic.
- Up to 4 patterns, strongest first, each with a tone: good for something that is working, attention for a recurring problem, fault for something broken, neutral otherwise.
- One three-brew experiment that settles the biggest open question. Change one variable across the three brews, give exact settings for each (the grind in the named grinder's own units where grind is involved), what to taste for, and how to read the result.
- One sentence per coffee.

Style: plain, direct English in the second person, with units. No markdown, no emojis, no preamble.
Output: one JSON object exactly matching REPLY SHAPE at the end of the message. No code fences, nothing before or after it.`;

const BREW_REPLY_SHAPE = `REPLY SHAPE
{
  "verdict": "dialled_in | close | needs_change | fault",
  "headline": "one sentence, at most 90 characters: what this cup most likely was",
  "diagnosis": "2-4 sentences: the most likely explanation, quoting the logged numbers",
  "confidence": "low | medium | high",
  "evidence": ["2-4 short facts from the log behind the diagnosis"],
  "change": {
    "variable": "grind | temperature | ratio | dose | pour | agitation | bloom | beans | none",
    "instruction": "the single change, exact, e.g. 75 -> 69 clicks (dial 7.5 -> 6.9)",
    "from": "current value with unit, or empty",
    "to": "new value with unit, or empty",
    "grindClicksFrom": null,
    "grindClicksTo": null,
    "grindValueFrom": null,
    "grindValueTo": null
  },
  "expectIfRight": "at most 120 characters: how the next cup should taste and run if you are right",
  "ifWrong": "at most 120 characters: how it will taste if you are wrong, and what that would tell you",
  "keep": "what to keep exactly the same",
  "dataWarnings": ["one short plain sentence per unreliable field; empty list if none"]
}
grindClicksFrom and grindClicksTo are integers, required when change.variable is grind, grinder.scale.adjustsBy is clicks and the current grind is known; otherwise null.
grindValueFrom and grindValueTo are numbers, required when change.variable is grind on a numbered or stepless dial and the current setting is known; otherwise null.
Reply with that JSON object only.`;

const TREND_REPLY_SHAPE = `REPLY SHAPE
{
  "trajectory": "improving | steady | stuck | erratic",
  "headline": "one sentence, at most 90 characters: the main recurring problem or strength",
  "summary": "2-4 sentences",
  "confidence": "low | medium | high",
  "patterns": [
    {"title": "short", "detail": "1-3 sentences with numbers", "brewIds": [3, 6], "strength": "strong | weak | too_little_data", "tone": "good | attention | fault | neutral"}
  ],
  "changedOften": ["variables you keep moving"],
  "neverChanged": ["variables you have never moved"],
  "experiment": {
    "goal": "the open question this settles",
    "brews": [{"label": "Brew 1", "settings": "exact settings", "grindClicks": null, "expect": "what to taste for"}],
    "decide": "how to read the three results"
  },
  "perCoffee": [{"bean": "name", "note": "one sentence"}],
  "dataWarnings": ["one short sentence per unreliable field; empty list if none"]
}
patterns: 1 to 4, strongest first. experiment.brews: exactly 3. grindClicks is an integer on the named grinder or null.
Reply with that JSON object only.`;

const DATA_QUALITY_TITLE = "DATA QUALITY (computed by the app; this overrides the raw log)";
const FAHRENHEIT_LINE = "USER UNITS\nWrite temperatures in °F for this person; the data is in °C.";

// ---------------------------------------------------------------- redaction

const K_DOSE = "doseG";
const K_WATER = "waterG";
const K_RATIO = "ratio";
const K_TEMP = "tempC";
const K_GRIND_CLICKS = "grindClicks";
const K_GRIND_SETTING = "grindSetting";
const K_GRIND_VALUE = "grindValue";
const K_CURRENT_CLICKS = "currentClicks";
const K_CURRENT_SETTING = "currentSetting";
const K_CURRENT_DIAL = "currentOnDial";
const K_TOTAL = "totalTimeSec";
const K_VS_RECIPE = "vsRecipeSec";
const K_DRAWDOWN = "drawdownSec";
const K_BEVERAGE = "beverageG";
const K_RETAINED = "retainedGPerGCoffee";
const K_TDS = "tdsPercent";
const K_EY = "extractionYieldPct";
const K_TASTE = "taste";
const K_OVERALL = "overall";
const K_DAYS_OFF_ROAST = "daysOffRoast";

/** Every prompt key worked out from each checked field. A distrusted field hides all of them. */
const DERIVED: Record<string, readonly string[]> = {
  [Fields.GRIND]: [K_GRIND_CLICKS, K_GRIND_SETTING, K_GRIND_VALUE, K_CURRENT_CLICKS, K_CURRENT_SETTING, K_CURRENT_DIAL],
  [Fields.BEVERAGE]: [K_BEVERAGE, K_RETAINED, K_EY, K_TDS],
  [Fields.EY]: [K_EY, K_TDS],
  [Fields.DRAWDOWN]: [K_DRAWDOWN],
  [Fields.TASTE]: [K_TASTE],
  [Fields.OVERALL]: [K_OVERALL],
  [Fields.ROAST]: [K_DAYS_OFF_ROAST],
  [Fields.TIME]: [K_TOTAL, K_VS_RECIPE],
  [Fields.TEMP]: [K_TEMP],
  [Fields.RATIO]: [K_DOSE, K_WATER, K_RATIO, K_RETAINED, K_EY],
};

/** The prompt keys to send as null for a brew whose distrusted fields are known. */
export function redactedKeys(distrusted: ReadonlySet<string>): Set<string> {
  const out = new Set<string>();
  for (const f of distrusted) for (const k of DERIVED[f] ?? []) out.add(k);
  return out;
}

type Json = string | number | boolean | null | Json[] | { [k: string]: Json };
type JsonObj = { [k: string]: Json };

/** Distrusted keys go out as null; everything else as given. */
function field(o: JsonObj, key: string, value: Json, hidden: ReadonlySet<string>) {
  o[key] = hidden.has(key) ? null : value;
}

/** Whole numbers as integers, everything else rounded to `places` decimals. */
function dbl(v: number | null | undefined, places = 1): number | null {
  if (v == null || !Number.isFinite(v)) return null;
  const scale = places === 2 ? 100 : 10;
  return Math.round(v * scale) / scale;
}

const str = (v: string | null | undefined): string | null => (v == null ? null : v);
const blankNull = (v: string | null | undefined): string | null => (v == null || v.trim() === "" ? null : v);

/** One JSON value per line, so long lists stay readable and the whole block stays valid JSON. */
function jsonRows(items: readonly JsonObj[]): string {
  return items.length === 0 ? "[]" : `[\n${items.map((i) => JSON.stringify(i)).join(",\n")}\n]`;
}

function pad2(n: number) {
  return n < 10 ? `0${n}` : String(n);
}
/** "yyyy-MM-dd HH:mm'Z'" in UTC, so a time-zone change never changes the hash. */
export function utcDateTime(ms: number): string {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())} ${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}Z`;
}
export function utcDate(ms: number): string {
  return utcDateTime(ms).slice(0, 10);
}

function ratioOf(log: BrewRow): string | null {
  return log.doseG > 0 && log.waterG > 0 ? "1:" + oneDp(log.waterG / log.doseG) : null;
}

function vsRecipe(log: BrewRow, recipe: Recipe | null): number | null {
  const target = recipe ? totalSec(recipe) : 0;
  if (target <= 0) return null;
  return log.totalTimeSec > 0 ? log.totalTimeSec - target : null;
}

const notRecorded = (label: string) => (label === "Not recorded" ? "not recorded" : label);

function tasteObject(t: TasteProfile): JsonObj {
  return { acidity: t.acidity, sweetness: t.sweetness, bitterness: t.bitterness, body: t.body, balance: t.balance, aftertaste: t.aftertaste, clarity: t.clarity };
}

function tasteLine(t: TasteProfile): string {
  return `acidity ${t.acidity}, sweetness ${t.sweetness}, bitterness ${t.bitterness}, body ${t.body}, balance ${t.balance}, aftertaste ${t.aftertaste}, clarity ${t.clarity}`;
}

const BURR_LABELS: Record<string, string> = { conical: "Conical", flat: "Flat", ghost: "Ghost", blade: "Blade" };

function burrs(g: GrinderRow): string | null {
  const size = g.burrSizeMm.trim().replace(/mm$/i, "").trim();
  const parts = [g.burrType ? BURR_LABELS[g.burrType] : null, size ? `${size} mm` : null].filter((p): p is string => p != null);
  return parts.length ? parts.join(", ") : null;
}

function adjustsBy(g: GrinderRow): string {
  switch (g.settingStyle) {
    case "clicks": return "clicks";
    case "stepless": return "stepless";
    default: return "numbered dial";
  }
}

function moveBasis(scale: GrindScale, mv: MoveSizes, method: BrewMethod | null): string {
  switch (mv.basis) {
    case "microns": return `${dbl(scale.micronsPerStep)} µm per ${usesClicks(scale) ? "click" : "step"} (app rule of thumb)`;
    case "range": return `a share of this grinder's ${method ? brewMethodLabel(method).toLowerCase() : ""} starting range (app rule of thumb)`.replace("  ", " ");
    case "legacy-turn": return `a full turn of ${scale.clicksPerRotation} clicks is a clear miss (app rule of thumb)`;
    case "generic": return `steps of ${fmtGrind(scale, scale.step)} (app rule of thumb)`;
  }
}

function moveSizesObj(scale: GrindScale, method: BrewMethod | null): JsonObj {
  const mv = moves(scale, method);
  return { nudge: dbl(mv.nudge, 2), clearMiss: dbl(mv.clearMiss, 2), maxAtOnce: dbl(mv.maxAtOnce, 2), basis: moveBasis(scale, mv, method) };
}

function scaleObj(g: GrinderRow, scale: GrindScale): JsonObj {
  const clicks = usesClicks(scale);
  return {
    adjustsBy: adjustsBy(g),
    unit: scale.unit || null,
    finerDirection: finerDirection(scale),
    clicksPerTurn: clicks ? scale.clicksPerRotation : null,
    micronsPerStep: dbl(scale.micronsPerStep, 2),
    min: dbl(scale.min, 2),
    max: dbl(scale.max, 2),
    howTheDialReads: blankNull(scale.dialReadingHelp),
    whatTheyType: blankNull(scale.entryHint),
    dialFormat: encodeDialFormat(scale.dial),
  };
}

function startingRange(scale: GrindScale, method: BrewMethod | null): JsonObj | null {
  const r = rangeFor(scale, method);
  if (!r || !method) return null;
  return { method, from: dbl(r.from, 2), to: dbl(r.to, 2), confidence: scale.confidence, from_who: [...new Set(scale.rangeSources.map((s) => s.who).filter(Boolean))] };
}

/**
 * A worked example on this grinder from a trusted setting, so the direction of "finer" and the
 * size of a clear miss are shown with real numbers rather than left to the model.
 */
function clickExample(from: number, scale: GrindScale, clearMiss: number): string | null {
  const finerBy = (step: number) => (scale.lowerIsFiner ? from - step : from + step);
  const full = Math.max(1, Math.round(clearMiss));
  let step: number;
  if (finerBy(full) > 0) step = full;
  else if (finerBy(2) > 0) step = 2;
  else return null;
  const to = finerBy(step);
  const words = talksInTurns(scale.dial) && step === scale.clicksPerRotation ? "one full turn finer" : `${step} clicks finer`;
  const rf = reading(scale, from);
  const rt = reading(scale, to);
  if (showsDialNumber(scale.dial) && rf != null && rt != null) return `${words} from ${from} (dial ${rf}) is ${to} (dial ${rt})`;
  return `${words} from ${from} is ${to} clicks`;
}

function valueExample(from: number, scale: GrindScale, clearMiss: number): string | null {
  const to = scale.lowerIsFiner ? from - clearMiss : from + clearMiss;
  if (to < scale.min || (scale.max > scale.min && to > scale.max)) return null;
  const unit = scale.unit ? ` ${scale.unit}` : "";
  return `${fmtGrind(scale, clearMiss)}${unit} finer from ${fmtGrind(scale, from)} is ${fmtGrind(scale, to)}`;
}

// -------------------------------------------------------------------- blocks

/**
 * What the text calls a brew, a coffee and a grinder. The model reads short row ids (ROW_IDS)
 * and answers with them. The input hash reads the same text with uids instead, because row ids
 * are this install's own and change when a backup is restored.
 */
interface Names {
  /** A brew after a '#'. */
  brewTag(id: number | undefined): string;
  brew(id: number | undefined): Json;
  bean(id: number | null): Json;
  grinder(id: number | null): Json;
  beanOrder(ids: readonly number[]): number[];
  grinderOrder(ids: readonly number[]): number[];
}

const byNumber = (ids: readonly number[]) => [...ids].sort((a, b) => a - b);

const ROW_IDS: Names = {
  brewTag: (id) => String(id ?? 0),
  brew: (id) => id ?? 0,
  bean: (id) => id,
  grinder: (id) => id,
  beanOrder: byNumber,
  grinderOrder: byNumber,
};

function uidNames(brews: readonly BrewRow[], beans: ReadonlyMap<number, BeanRow>, grinders: ReadonlyMap<number, GrinderRow>): Names {
  const brewUid = new Map(brews.map((b) => [b.id ?? 0, b.uid]));
  // By uid, the order is the same on every install. A coffee or grinder that was deleted has no
  // uid and no row in a backup, so the hash leaves it out here as a restore would.
  const byUid = (rows: ReadonlyMap<number, { uid: string }>) => (ids: readonly number[]) =>
    ids.filter((id) => rows.has(id)).sort((a, b) => (rows.get(a)!.uid < rows.get(b)!.uid ? -1 : rows.get(a)!.uid > rows.get(b)!.uid ? 1 : 0));
  return {
    brewTag: (id) => brewUid.get(id ?? 0) ?? "",
    brew: (id) => brewUid.get(id ?? 0) ?? null,
    bean: (id) => (id != null ? (beans.get(id)?.uid ?? null) : null),
    grinder: (id) => (id != null ? (grinders.get(id)?.uid ?? null) : null),
    beanOrder: byUid(beans),
    grinderOrder: byUid(grinders),
  };
}

function grinderBlock(log: BrewRow, grinder: GrinderRow | null, method: BrewMethod | null, hidden: ReadonlySet<string>, anchor: BrewRow | null, current: number | null, names: Names): string {
  if (!grinder) {
    return log.grinderLabel.trim()
      ? `GRINDER\nno longer saved in the app (logged as "${log.grinderLabel}"), so which way is finer is unknown`
      : "GRINDER\nnone recorded";
  }
  const scale = scaleFor(grinder);
  const mv = moves(scale, method);
  const o: JsonObj = { name: grinderDisplay(grinder), catalogId: grinder.catalogId, burrs: burrs(grinder), scale: scaleObj(grinder, scale) };
  const from = current ?? (usesClicks(scale) ? anchor?.grindClicks : anchor?.grindValue) ?? null;
  if (usesClicks(scale)) {
    field(o, K_CURRENT_CLICKS, log.grindClicks, hidden);
    field(o, K_CURRENT_DIAL, log.grindClicks != null ? reading(scale, log.grindClicks) : null, hidden);
    o.lastTrustedClicks = anchor?.grindClicks ?? null;
  } else {
    // grindValue, else the text the person typed.
    field(o, K_CURRENT_SETTING, log.grindValue != null ? dbl(log.grindValue, 2) : blankNull(log.grindSetting), hidden);
    o.lastTrustedSetting = anchor?.grindValue != null ? dbl(anchor.grindValue, 2) : null;
  }
  o.lastTrustedFrom = anchor ? `brew #${names.brewTag(anchor.id)}, ${utcDate(anchor.startedAt)}, ${anchor.recipeName}` : null;
  o.moveSizes = moveSizesObj(scale, method);
  o.startingRange = startingRange(scale, method);
  o.example = from != null ? (usesClicks(scale) ? clickExample(from, scale, mv.clearMiss) : valueExample(from, scale, mv.clearMiss)) : null;
  return `GRINDER\n${JSON.stringify(o)}`;
}

function recipeBlock(log: BrewRow, recipe: Recipe | null): string {
  if (!recipe) {
    return log.recipeName.trim() ? `RECIPE\nnone, brewed as "${log.recipeName}" without a saved recipe` : "RECIPE\nnone, brewed without a saved recipe";
  }
  const lastPour = lastPourEndSec(recipe);
  const total = totalSec(recipe);
  const target = total > 0 ? total : null;
  const o: JsonObj = {
    name: recipe.name,
    author: recipe.author,
    brewer: recipe.brewer.label,
    doseG: dbl(recipe.doseG > 0 ? recipe.doseG : null),
    waterG: dbl(recipe.waterG > 0 ? recipe.waterG : null),
    ratio: recipe.doseG > 0 && recipe.waterG > 0 ? recipeRatioLabel(recipe) : null,
    tempC: recipe.tempC,
    grindAdvice: recipe.grind,
    lastPourEndsAtSec: lastPour,
    targetTotalSec: target,
    expectedDrawdownSec: lastPour != null && target != null && target - lastPour >= 0 ? target - lastPour : null,
    // Titles as 2.0 wrote them ("70 C"): 2.1 added the degree sign to three built-in titles, and
    // this block is hashed, so analyses saved under 2.0 would read "edited since" for no edit.
    steps: recipe.steps.map((s) => `${clock(s.startSec)}-${clock(s.endSec)} ${s.title.replace(/(\d+) °C/g, "$1 C")}`),
  };
  return `RECIPE (what was planned)\n${JSON.stringify(o)}`;
}

function brewerBlock(brewer: Brewer | null, log: BrewRow): string {
  if (!brewer) return log.brewer.trim() ? `BREWER\n${JSON.stringify({ name: log.brewer, category: null, method: null, filter: null })}` : "BREWER\nnot recorded";
  return `BREWER\n${JSON.stringify({ name: brewer.label, category: brewer.category, method: brewer.method, filter: brewer.filter })}`;
}

function waterBlock(water: CatalogWater | null): string {
  if (!water) return "WATER\nnot recorded";
  return `WATER\n${JSON.stringify({ label: water.label, kind: water.kind, ghPpm: water.ghPpm, khPpm: water.khPpm })}`;
}

function varietals(b: BeanRow): string[] | null {
  const labels = b.varietalIds.map((id) => catalog.label("varietal", id) ?? id);
  const custom = b.varietal.split(",").map((s) => s.trim()).filter(Boolean);
  const all = [...labels, ...custom.filter((c) => !labels.some((l) => l.toLowerCase() === c.toLowerCase()))];
  return all.length ? all : null;
}

function coffeeBlock(log: BrewRow, bean: BeanRow | null, hidden: ReadonlySet<string>): string {
  if (!bean && !log.beanLabel.trim()) return "COFFEE\nnone recorded";
  const roaster = bean ? catalog.roaster(bean.roasterId) : null;
  const origin = bean ? catalog.origin(bean.originId) : null;
  const process = bean ? catalog.process(bean.processId) : null;
  const o: JsonObj = {
    name: bean ? beanDisplay(bean) : log.beanLabel,
    roaster: blankNull(bean?.roaster) ?? roaster?.name ?? null,
    roasterCountry: blankNull(roaster?.country),
    origin: blankNull(bean?.origin) ?? origin?.country ?? null,
    growingRegion: blankNull(bean?.region),
    farm: blankNull(bean?.farm),
    producer: blankNull(bean?.producer),
    washingStation: blankNull(bean?.washingStation),
    lotType: bean ? catalog.label("lotType", bean.lotTypeId) : null,
    species: bean ? catalog.label("species", bean.speciesId) : null,
    varietals: bean ? varietals(bean) : null,
    process: bean ? (process?.label ?? (bean.processId ? bean.processId : "Unknown")) : null,
    processGroup: blankNull(process?.group),
    decaf: bean?.isDecaf ? (catalog.label("decaf", bean.decafMethodId) ?? "yes, method not recorded") : null,
    roastLevel: bean ? (catalog.label("roastLevel", bean.roastLevelId) ?? "Unknown") : null,
    roastIntent: bean ? catalog.label("roastIntent", bean.roastIntentId) : null,
    altitudeM: blankNull(bean?.altitudeMasl),
    harvest: blankNull(bean?.harvest),
  };
  // The log's snapshot from brew day. Today's age would change the hash every day.
  field(o, K_DAYS_OFF_ROAST, log.daysOffRoast, hidden);
  o.roastDateKnown = log.daysOffRoast != null;
  o.restDaysSuggested = bean?.restDays ?? null;
  o.roasterNotes = blankNull(bean?.tastingNotes);
  o.bagNotes = blankNull(bean?.notes);
  return `COFFEE\n${JSON.stringify(o)}`;
}

function sendsGrindText(log: BrewRow, grinder: GrinderRow | null): boolean {
  // A§8.5 / fact 4: click grinders send clicks only; the free text is often a stale dial reading.
  if (grinder) return grinder.settingStyle !== "clicks";
  return log.grindClicks == null;
}

function thisBrew(log: BrewRow, recipe: Recipe | null, grinder: GrinderRow | null, distrusted: ReadonlySet<string>, hidden: ReadonlySet<string>, names: Names): JsonObj {
  const o: JsonObj = { id: names.brew(log.id), date: utcDateTime(log.startedAt) };
  field(o, K_DOSE, dbl(log.doseG > 0 ? log.doseG : null), hidden);
  field(o, K_WATER, dbl(log.waterG > 0 ? log.waterG : null), hidden);
  field(o, K_RATIO, ratioOf(log), hidden);
  field(o, K_TEMP, log.tempC, hidden);
  field(o, K_GRIND_CLICKS, log.grindClicks, hidden);
  if (sendsGrindText(log, grinder)) {
    field(o, K_GRIND_VALUE, dbl(log.grindValue, 2), hidden);
    field(o, K_GRIND_SETTING, blankNull(log.grindSetting), hidden);
  }
  field(o, K_TOTAL, log.totalTimeSec > 0 ? log.totalTimeSec : null, hidden);
  field(o, K_VS_RECIPE, vsRecipe(log, recipe), hidden);
  field(o, K_DRAWDOWN, log.drawdownSec, hidden);
  field(o, K_BEVERAGE, dbl(log.beverageG), hidden);
  field(o, K_RETAINED, dbl(retainedPerGram(log)), hidden);
  // TDS lives in the second decimal, so it keeps two.
  field(o, K_TDS, dbl(log.tdsPercent, 2), hidden);
  field(o, K_EY, dbl(log.extractionYield), hidden);
  o.flow = notRecorded(flowFeelLabel(log.flowFeel));
  o.bed = { shape: notRecorded(bedShapeLabel(log.bedShape)), surface: notRecorded(sludgeLabel(log.sludge)), channelling: notRecorded(channelingLabel(log.channeling)), notes: log.bedNotes };
  field(o, K_TASTE, tasteObject(log.taste), hidden);
  o[K_OVERALL] = log.overall;
  o.tastedOf = [...log.descriptors];
  o.faults = [...log.faults];
  o.notes = log.notes;
  o.planForNextTime = log.nextTime;
  o.distrusted = [...distrusted].sort();
  return o;
}

/** One compact line per earlier brew (brew prompt) or per brew (trend prompt). */
function row(h: BrewRow, ctx: PromptContext, flags: readonly DataFlag[], trend: boolean, names: Names): JsonObj {
  const distrusted = distrustedFields(flags, h.id);
  const hidden = redactedKeys(distrusted);
  const recipe = ctx.recipes.get(h.recipeId) ?? null;
  const bean = h.beanId != null ? (ctx.beans.get(h.beanId) ?? null) : null;
  const grinder = h.grinderId != null ? (ctx.grinders.get(h.grinderId) ?? null) : null;
  const o: JsonObj = { id: names.brew(h.id), date: utcDate(h.startedAt) };
  if (trend) o.recipeId = blankNull(h.recipeId);
  o.recipe = blankNull(h.recipeName);
  if (trend) o.coffeeId = names.bean(h.beanId);
  o.coffee = bean ? beanDisplay(bean) : blankNull(h.beanLabel);
  if (trend) o.grinderId = names.grinder(h.grinderId);
  o.grinder = grinder ? grinderDisplay(grinder) : blankNull(h.grinderLabel);
  if (trend) o.brewer = blankNull(brewerOf(h, recipe)?.label ?? h.brewer);
  field(o, K_GRIND_CLICKS, h.grindClicks, hidden);
  if (sendsGrindText(h, grinder)) {
    field(o, K_GRIND_VALUE, dbl(h.grindValue, 2), hidden);
    field(o, K_GRIND_SETTING, blankNull(h.grindSetting), hidden);
  }
  field(o, K_TEMP, h.tempC, hidden);
  field(o, K_RATIO, ratioOf(h), hidden);
  field(o, K_TOTAL, h.totalTimeSec > 0 ? h.totalTimeSec : null, hidden);
  field(o, K_VS_RECIPE, vsRecipe(h, recipe), hidden);
  field(o, K_DRAWDOWN, h.drawdownSec, hidden);
  field(o, K_RETAINED, dbl(retainedPerGram(h)), hidden);
  if (trend) {
    field(o, K_DAYS_OFF_ROAST, h.daysOffRoast, hidden);
    o.water = h.waterId ? (catalog.waterById(h.waterId)?.label ?? h.waterId) : null;
  }
  o.flow = notRecorded(flowFeelLabel(h.flowFeel));
  o.bed = notRecorded(bedShapeLabel(h.bedShape));
  o[K_OVERALL] = h.overall;
  o.faults = [...h.faults];
  o.tastedOf = [...h.descriptors];
  field(o, K_TASTE, tasteLine(h.taste), hidden);
  o.distrusted = [...distrusted].sort();
  return o;
}

function dataQualityBlock(flags: readonly DataFlag[], names: Names): string {
  const distrust = flags.filter((f) => f.severity === "Distrust");
  const notes = flags.filter((f) => f.severity === "Note");
  const lines = (list: readonly DataFlag[]) => (list.length === 0 ? "No problems found." : list.map((f) => `- #${names.brewTag(f.brewId)} ${fieldLabel(f.field)}: ${promptMessage(f)}`).join("\n"));
  return `${DATA_QUALITY_TITLE}\nDistrusted, sent as null, do not use:\n${lines(distrust)}\nContext notes:\n${lines(notes)}`;
}

/** Principles whose appliesTo is empty or includes one of the methods, at most MAX_PRINCIPLES. */
export function principlesFor(all: readonly Principle[], methods: ReadonlySet<string>): Principle[] {
  return all.filter((p) => p.appliesTo.length === 0 || p.appliesTo.some((m) => methods.has(m))).slice(0, MAX_PRINCIPLES);
}

function principlesBlock(list: readonly Principle[]): string | null {
  if (list.length === 0) return null;
  return `PRINCIPLES (from the people whose methods these are)\n${jsonRows(list.map((p) => ({ who: p.who, topic: p.topic, text: p.text })))}`;
}

function photosBlock(photos: readonly PhotoMeta[]): string {
  if (photos.length === 0) return "PHOTOS\nnone";
  const parts = photos.map((p, i) => `#${i + 1} ${p.kind} (${utcDateTime(p.takenAt)}, ${p.uid.slice(0, 8)}, ${Math.max(1, Math.round(p.bytes / 1024))} KB)`);
  return `PHOTOS\n${photos.length} attached, in this order: ${parts.join(", ")}`;
}

/** `hashed` is the data blocks with each row id written as the record's uid: what the hash is made from. */
function built(system: string, user: string, hashed: readonly string[], p: Omit<BuiltPrompt, "system" | "user" | "inputHash" | "approxPromptTokens">): BuiltPrompt {
  return {
    system,
    user,
    // The data only, as Android: VERSION already stands for the wording, and a new principle or
    // a switch to °F must not make an untouched brew read as "edited since". Uids, not row ids:
    // the same brew restored from a backup under new row ids is still untouched.
    inputHash: HASH_SCHEME + sha256Hex16(`${VERSION}\n${hashed.join("\n\n")}`),
    approxPromptTokens: Math.floor((system.length + user.length) / 3.5),
    ...p,
  };
}

/**
 * A deleted coffee or grinder leaves its id on the brews that used it, and a restore brings those
 * brews back with no id at all. The checks group brews by these ids, so the prompt reads such a
 * brew as a restore would: with none.
 */
function savedOnly(log: BrewRow, hasBean: (id: number) => boolean, hasGrinder: (id: number) => boolean): BrewRow {
  const beanId = log.beanId != null && hasBean(log.beanId) ? log.beanId : null;
  const grinderId = log.grinderId != null && hasGrinder(log.grinderId) ? log.grinderId : null;
  return beanId === (log.beanId ?? null) && grinderId === (log.grinderId ?? null) ? log : { ...log, beanId, grinderId };
}

// ---------------------------------------------------------------------- brew

export function buildBrewPrompt(input: BrewPromptInput): BuiltPrompt {
  // The target's own coffee and grinder count as saved even when the maps leave them out.
  const hasBean = (id: number) => input.beans.has(id) || input.bean?.id === id;
  const hasGrinder = (id: number) => input.grinders.has(id) || input.grinder?.id === id;
  const log = savedOnly(input.log, hasBean, hasGrinder);
  const history = input.history.filter((h) => h.id !== log.id).map((h) => savedOnly(h, hasBean, hasGrinder));

  // The target is checked against its history; each context brew against the ones before it.
  const targetFlags = checkBrew(log, input.recipe, input.grinder, input.bean, history);
  const contextFlags = history.flatMap((h, i) =>
    checkBrew(
      h,
      input.recipes.get(h.recipeId) ?? null,
      h.grinderId != null ? (input.grinders.get(h.grinderId) ?? null) : null,
      h.beanId != null ? (input.beans.get(h.beanId) ?? null) : null,
      history.slice(i + 1),
    ),
  );
  const flags = [...targetFlags, ...contextFlags];
  const flagsById = groupFlags(flags);
  const distrusted = distrustedFields(flags, log.id);
  const hidden = redactedKeys(distrusted);

  const brewer = brewerOf(log, input.recipe);
  const method = brewer?.method ?? null;
  const clicksGrinder = input.grinder ? input.grinder.settingStyle === "clicks" : true;
  // A distrusted grind is anchored on the last trusted brew of the same recipe instead.
  const currentClicks = clicksGrinder && log.grindClicks != null && log.grindClicks > 0 && !hidden.has(K_CURRENT_CLICKS) ? log.grindClicks : null;
  const currentValue = !clicksGrinder && log.grindValue != null && log.grindValue > 0 && !hidden.has(K_CURRENT_SETTING) ? log.grindValue : null;
  const current = clicksGrinder ? currentClicks : currentValue;
  const anchor = current == null ? lastTrusted(log, history, flagsById, !clicksGrinder) : null;

  const earlier = (names: Names) => (history.length === 0 ? "none" : jsonRows(history.map((h) => row(h, input, flagsById.get(h.id ?? 0) ?? [], false, names))));
  const water = log.waterId ? catalog.waterById(log.waterId) : null;
  const principles = principlesBlock(principlesFor(input.principles, new Set(method ? [method] : [])));

  // What the analysis reads. The hash covers exactly this, so only a change to the brew, its
  // coffee, its gear or its photos marks a stored analysis as edited.
  const data = (names: Names, photos: readonly PhotoMeta[]) => ({
    first: [
      `TASK: Diagnose brew #${names.brewTag(log.id)} and give one change for the next brew.`,
      grinderBlock(log, input.grinder, method, hidden, anchor, current, names),
      recipeBlock(log, input.recipe),
      brewerBlock(brewer, log),
      waterBlock(water),
      coffeeBlock(log, input.bean, hidden),
      "THIS BREW (what happened)\n" + JSON.stringify(thisBrew(log, input.recipe, input.grinder, distrusted, hidden, names)),
      photosBlock(photos),
      dataQualityBlock(flags, names),
    ],
    earlier: "EARLIER BREWS (newest first, context only)\n" + earlier(names),
  });
  const shown = data(ROW_IDS, input.photos);
  const stable = data(uidNames([log, ...history], input.beans, input.grinders), (input.brewPhotos ?? input.photos).slice(0, MAX_PHOTOS));

  const user = [...shown.first, principles, shown.earlier, input.units === "f" ? FAHRENHEIT_LINE : null, BREW_REPLY_SHAPE]
    .filter((b): b is string => b != null)
    .join("\n\n");

  return built(SYSTEM_BREW, user, [...stable.first, stable.earlier], {
    flags,
    brewCount: 1 + history.length,
    ratedCount: [log, ...history].filter((l) => l.overall > 0).length,
    newestBrewAt: log.startedAt,
    maxTokens: BREW_MAX_TOKENS,
    expectedCompletionTokens: BREW_EXPECTED_OUT,
    grindAnchor: clicksGrinder ? (currentClicks ?? anchor?.grindClicks ?? null) : null,
    valueAnchor: clicksGrinder ? null : (currentValue ?? anchor?.grindValue ?? null),
    photoCount: input.photos.length,
  });
}

// --------------------------------------------------------------------- trend

export function buildTrendPrompt(input: TrendPromptInput): BuiltPrompt {
  const logs = input.logs;
  // Only the checks: the model is still told which brews shared a coffee since deleted.
  const checked = logs.map((l) => savedOnly(l, (id) => input.beans.has(id), (id) => input.grinders.has(id)));
  const flags = checked.flatMap((l, i) =>
    checkBrew(
      l,
      input.recipes.get(l.recipeId) ?? null,
      l.grinderId != null ? (input.grinders.get(l.grinderId) ?? null) : null,
      l.beanId != null ? (input.beans.get(l.beanId) ?? null) : null,
      checked.slice(i + 1),
    ),
  );
  const flagsById = groupFlags(flags);
  const rated = logs.filter((l) => l.overall > 0).length;

  let task = `TASK: Find the patterns across these ${logs.length} brews (${rated} rated), newest first.`;
  if (input.totalLogged > logs.length) task += ` These are the latest ${logs.length} of ${input.totalLogged} brews.`;

  const distinct = <T,>(xs: readonly T[]) => [...new Set(xs)];
  const grinders = (names: Names) =>
    names.grinderOrder(distinct(logs.map((l) => l.grinderId).filter((x): x is number => x != null))).map((id): JsonObj => {
      const g = input.grinders.get(id);
      if (!g) return { id: names.grinder(id), name: str(logs.find((l) => l.grinderId === id)?.grinderLabel), adjustsBy: null, finerDirection: null, clicksPerTurn: null, moveSizes: null };
      const scale = scaleFor(g);
      return { id: names.grinder(id), name: grinderDisplay(g), ...scaleObj(g, scale), moveSizes: moveSizesObj(scale, null) };
    });
  const coffees = (names: Names) =>
    names.beanOrder(distinct(logs.map((l) => l.beanId).filter((x): x is number => x != null))).map((id): JsonObj => {
      const b = input.beans.get(id);
      return {
        id: names.bean(id),
        name: str(b ? beanDisplay(b) : logs.find((l) => l.beanId === id)?.beanLabel),
        origin: b ? (blankNull(b.origin) ?? catalog.origin(b.originId)?.country ?? null) : null,
        process: b ? (catalog.process(b.processId)?.label ?? "Unknown") : null,
        roastLevel: b ? (catalog.label("roastLevel", b.roastLevelId) ?? "Unknown") : null,
        decaf: b ? b.isDecaf : null,
        roastDateKnown: b ? b.roastedOnEpochDay != null : null,
      };
    });
  const recipes = distinct(logs.map((l) => l.recipeId).filter((x) => x.trim() !== ""))
    .sort()
    .map((id): JsonObj => {
      const r = input.recipes.get(id);
      const t = r ? totalSec(r) : 0;
      return {
        id,
        name: str(r?.name ?? logs.find((l) => l.recipeId === id)?.recipeName),
        brewer: str(r?.brewer.label),
        doseG: dbl(r && r.doseG > 0 ? r.doseG : null),
        waterG: dbl(r && r.waterG > 0 ? r.waterG : null),
        ratio: r && r.doseG > 0 && r.waterG > 0 ? recipeRatioLabel(r) : null,
        tempC: r?.tempC ?? null,
        lastPourEndsAtSec: lastPourEndSec(r),
        targetTotalSec: t > 0 ? t : null,
      };
    });
  const rows = (names: Names) => logs.map((l) => row(l, input, flagsById.get(l.id ?? 0) ?? [], true, names));
  const methods = new Set(logs.map((l) => brewerOf(l, input.recipes.get(l.recipeId) ?? null)?.method).filter((m): m is BrewMethod => m != null));

  const recipesBlock = "RECIPES\n" + jsonRows(recipes);
  const data = (names: Names): string[] => [
    task,
    "GRINDERS\n" + jsonRows(grinders(names)),
    "COFFEES\n" + jsonRows(coffees(names)),
    recipesBlock,
    "BREWS\n" + jsonRows(rows(names)),
    dataQualityBlock(flags, names),
  ];

  const user = [...data(ROW_IDS), principlesBlock(principlesFor(input.principles, methods)), input.units === "f" ? FAHRENHEIT_LINE : null, TREND_REPLY_SHAPE]
    .filter((b): b is string => b != null)
    .join("\n\n");

  return built(SYSTEM_TREND, user, data(uidNames(logs, input.beans, input.grinders)), {
    flags,
    brewCount: logs.length,
    ratedCount: rated,
    newestBrewAt: logs[0]?.startedAt ?? 0,
    maxTokens: TREND_MAX_TOKENS,
    expectedCompletionTokens: TREND_EXPECTED_OUT,
    grindAnchor: null,
    valueAnchor: null,
    photoCount: 0,
  });
}
