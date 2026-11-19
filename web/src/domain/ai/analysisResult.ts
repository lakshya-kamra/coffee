/**
 * Port of domain/ai/AnalysisResult.kt (+ A§8.5 grindValueFrom/To and GrindValueMove): the JSON
 * contract, the grind moves re-derived in the app, and the forgiving reply parser.
 */
import type { BrewMethod } from "@/domain/core/brewer";
import { decodeDialFormat, DialFormats, encodeDialFormat, format as formatGrind, moves, reading, scaleFor, showsDialNumber, talksInTurns, finerDirection, type DialFormat, type GrindScale } from "@/domain/grind";
import { grinderDisplay, type GrinderRow } from "@/data/schema";

// --------------------------------------------------------------- the JSON contract
// Every field has a default so a partial reply still decodes.

export interface Change {
  variable: string;
  instruction: string;
  from: string;
  to: string;
  grindClicksFrom: number | null;
  grindClicksTo: number | null;
  grindValueFrom: number | null;
  grindValueTo: number | null;
}

export interface BrewAnalysis {
  verdict: string;
  headline: string;
  diagnosis: string;
  confidence: string;
  evidence: string[];
  change: Change;
  expectIfRight: string;
  ifWrong: string;
  keep: string;
  dataWarnings: string[];
}

export interface Pattern {
  title: string;
  detail: string;
  brewIds: number[];
  strength: string;
  tone: string;
}

export interface ExperimentBrew {
  label: string;
  settings: string;
  grindClicks: number | null;
  expect: string;
}

export interface Experiment {
  goal: string;
  brews: ExperimentBrew[];
  decide: string;
}

export interface CoffeeNote {
  bean: string;
  note: string;
}

export interface TrendAnalysis {
  trajectory: string;
  headline: string;
  summary: string;
  confidence: string;
  patterns: Pattern[];
  changedOften: string[];
  neverChanged: string[];
  experiment: Experiment;
  perCoffee: CoffeeNote[];
  dataWarnings: string[];
}

export const emptyChange = (): Change => ({ variable: "", instruction: "", from: "", to: "", grindClicksFrom: null, grindClicksTo: null, grindValueFrom: null, grindValueTo: null });

// ------------------------------------------------------------------- grind move

/**
 * The one click change, re-derived in the app. `from` comes from the log, never from the model,
 * and the direction comes from the grinder, so the hero number can't point the wrong way.
 * `dialFormat` is the grinder's encoded DialFormat when the move was made; "" on 1.x results.
 */
export interface GrindMove {
  readonly from: number | null;
  readonly to: number;
  readonly clicksPerTurn: number;
  readonly lowerIsFiner: boolean;
  readonly dialFormat: string;
}

export const GrindMoves = {
  delta: (m: GrindMove): number | null => (m.from != null ? m.to - m.from : null),
  finer(m: GrindMove): boolean | null {
    const d = GrindMoves.delta(m);
    return d == null ? null : m.lowerIsFiner ? d < 0 : d > 0;
  },
  /** "77 → 67", or "67" when there is no trusted starting point. */
  numbers: (m: GrindMove): string => (m.from != null ? `${m.from} → ${m.to}` : `${m.to}`),
  format: (m: GrindMove): DialFormat | null => decodeDialFormat(m.dialFormat, m.clicksPerTurn),

  /** "6 clicks finer · dial 7.5 → 6.9"; turn words appear only where a turn is a real unit. */
  caption(m: GrindMove): string {
    const d = GrindMoves.delta(m);
    if (d == null) return GrindMoves.dial(m) ?? "";
    if (d === 0) return "";
    const n = Math.abs(d);
    const dir = GrindMoves.finer(m) ? "finer" : "coarser";
    const cpt = m.clicksPerTurn;
    const f = GrindMoves.format(m);
    const turnWords = f ? talksInTurns(f) : cpt > 0;
    let move: string;
    if (turnWords && cpt > 0 && n % cpt === 0) {
      const turns = n / cpt;
      move = `${clicksWord(n)} ${dir} · ` + (turns === 1 ? "one full turn" : `${turns} full turns`);
    } else if (turnWords && cpt > 0 && n > cpt) {
      const turns = Math.floor(n / cpt);
      move = `${clicksWord(n)} ${dir} · ${turns} ${turns === 1 ? "turn" : "turns"} + ${n % cpt}`;
    } else {
      move = `${clicksWord(n)} ${dir}`;
    }
    const dial = GrindMoves.dial(m);
    return dial ? `${move} · ${dial}` : move;
  },

  /** "dial 7.7 → 6.7" (or "dial 6.7" with no start) on dials that show a number, else null. */
  dial(m: GrindMove): string | null {
    if (m.to < 0) return null;
    const f = GrindMoves.format(m) ?? (m.clicksPerTurn === 10 ? DialFormats.turnsDotClicks(10) : null);
    if (!f || !showsDialNumber(f)) return null;
    const s = readOn(f, m.to);
    if (s == null) return null;
    return m.from != null && m.from >= 0 ? `dial ${readOn(f, m.from)} → ${s}` : `dial ${s}`;
  },
};

function clicksWord(n: number): string {
  return n === 1 ? "1 click" : `${n} clicks`;
}

/** Reads a click count on a bare DialFormat (no grinder row needed). */
function readOn(f: DialFormat, clicks: number): string | null {
  const s: GrindScale = {
    style: "clicks", unit: "clicks", min: 0, max: 0, step: 1, lowerIsFiner: true, clicksPerRotation: null, micronsPerStep: null,
    dial: f, ranges: {}, rangeSources: [], confidence: null, dialReadingHelp: "", entryHint: "",
  };
  return reading(s, clicks);
}

/**
 * Builds the click move from the model's change. `trustedFrom` is the log's own click count, or
 * the last trusted one; the model's grindClicksFrom is never used as the start.
 */
export function resolveGrindMove(change: Change, grinder: GrinderRow | null, trustedFrom: number | null, method: BrewMethod | null = null): { move: GrindMove | null; warnings: string[] } {
  if (!grinder || grinder.settingStyle !== "clicks") return { move: null, warnings: [] };
  if (change.variable.trim().toLowerCase() !== "grind") return { move: null, warnings: [] };
  const to = change.grindClicksTo;
  if (to == null) return { move: null, warnings: [] };
  if (to <= 0) return { move: null, warnings: ["The suggested setting was zero or below, so it is not shown."] };
  const from = trustedFrom != null && trustedFrom > 0 ? trustedFrom : null;
  if (to === from) return { move: null, warnings: [] };

  const scale = scaleFor(grinder);
  const move: GrindMove = { from, to, clicksPerTurn: Math.max(0, grinder.clicksPerRotation), lowerIsFiner: grinder.lowerIsFiner, dialFormat: encodeDialFormat(scale.dial) };
  const warnings: string[] = [];
  const modelFrom = change.grindClicksFrom;
  if (from != null && modelFrom != null && modelFrom > 0 && modelFrom !== from) {
    warnings.push(`The model started from ${modelFrom} clicks, but your log says ${from}, so the move is shown from ${from}.`);
  }
  const finer = GrindMoves.finer(move);
  const delta = GrindMoves.delta(move);
  if (finer != null && delta != null) {
    const words = change.instruction.toLowerCase();
    if ((words.includes("finer") && !finer) || (words.includes("coarser") && finer)) {
      warnings.push(`The model's words and numbers disagree: ${from} → ${to} is ${finer ? "finer" : "coarser"} on this grinder (${finerDirection(scale)}). Check before turning the dial.`);
    }
    const mv = moves(scale, method);
    if (Math.abs(delta) > mv.maxAtOnce) {
      warnings.push(
        mv.basis === "legacy-turn"
          ? "That is more than two full turns in one go. Consider going halfway first."
          : `That is more than ${formatGrind(scale, mv.maxAtOnce)} clicks in one go. Consider going halfway first.`,
      );
    }
  }
  return { move, warnings };
}

// ------------------------------------------------------------------ value move (A§8.5)

/** A numbered or stepless dial's change: "14 → 13 · finer". Same rules as GrindMove. */
export interface GrindValueMove {
  readonly from: number | null;
  readonly to: number;
  readonly lowerIsFiner: boolean;
  readonly unit: string;
  readonly dialFormat: string;
}

/** Two decimals at most, float dust removed (Vario "5.12"). */
export function fmtValue(v: number): string {
  return String(Math.round(v * 100) / 100);
}

export const GrindValueMoves = {
  delta: (m: GrindValueMove): number | null => (m.from != null ? Math.round((m.to - m.from) * 100) / 100 : null),
  finer(m: GrindValueMove): boolean | null {
    const d = GrindValueMoves.delta(m);
    return d == null ? null : m.lowerIsFiner ? d < 0 : d > 0;
  },
  numbers: (m: GrindValueMove): string => (m.from != null ? `${fmtValue(m.from)} → ${fmtValue(m.to)}` : fmtValue(m.to)),
  /** "finer by 1", "coarser by 0.5 µm"; "" with no start. */
  caption(m: GrindValueMove): string {
    const d = GrindValueMoves.delta(m);
    if (d == null || d === 0) return "";
    return `${GrindValueMoves.finer(m) ? "finer" : "coarser"} by ${fmtValue(Math.abs(d))}${m.unit ? ` ${m.unit}` : ""}`;
  },
};

export function resolveGrindValueMove(change: Change, grinder: GrinderRow | null, trustedFrom: number | null, method: BrewMethod | null = null): { move: GrindValueMove | null; warnings: string[] } {
  if (!grinder || grinder.settingStyle === "clicks") return { move: null, warnings: [] };
  if (change.variable.trim().toLowerCase() !== "grind") return { move: null, warnings: [] };
  const to = change.grindValueTo;
  if (to == null || !Number.isFinite(to)) return { move: null, warnings: [] };
  if (to < 0) return { move: null, warnings: ["The suggested setting was below zero, so it is not shown."] };
  const from = trustedFrom != null && trustedFrom > 0 ? trustedFrom : null;
  if (from != null && Math.abs(to - from) < 1e-9) return { move: null, warnings: [] };
  const scale = scaleFor(grinder);
  const move: GrindValueMove = { from, to, lowerIsFiner: grinder.lowerIsFiner, unit: scale.unit, dialFormat: encodeDialFormat(scale.dial) };
  const warnings: string[] = [];
  const modelFrom = change.grindValueFrom;
  if (from != null && modelFrom != null && modelFrom > 0 && Math.abs(modelFrom - from) > 1e-9) {
    warnings.push(`The model started from ${fmtValue(modelFrom)}, but your log says ${fmtValue(from)}, so the move is shown from ${fmtValue(from)}.`);
  }
  const finer = GrindValueMoves.finer(move);
  const delta = GrindValueMoves.delta(move);
  if (finer != null && delta != null) {
    const words = change.instruction.toLowerCase();
    if ((words.includes("finer") && !finer) || (words.includes("coarser") && finer)) {
      warnings.push(`The model's words and numbers disagree: ${fmtValue(from!)} → ${fmtValue(to)} is ${finer ? "finer" : "coarser"} on this grinder (${finerDirection(scale)}). Check before turning the dial.`);
    }
    const mv = moves(scale, method);
    if (Math.abs(delta) > mv.maxAtOnce) {
      warnings.push(`That is more than ${formatGrind(scale, mv.maxAtOnce)} in one go. Consider going halfway first.`);
    }
  }
  return { move, warnings };
}

// ------------------------------------------------------------------ value move (hero)

export interface ValueMove {
  readonly from: string;
  readonly to: string;
  readonly unit: string;
}

const VALUE = /^\s*([0-9]+(?:[.:][0-9]+)?)\s*(.*)$/;
const unitKey = (u: string) => u.replace(" °", "°").toLowerCase();

/** "95 °C" → "100 °C" becomes ("95", "100", "°C"), for a hero row like the grind one. */
export function valueMove(change: Change): ValueMove | null {
  const v = change.variable.trim().toLowerCase();
  if (!v || v === "grind" || v === "none") return null;
  if (!change.from.trim() || !change.to.trim()) return null;
  const a = VALUE.exec(change.from.trim());
  const b = VALUE.exec(change.to.trim());
  if (!a || !b) return null;
  const ua = a[2]!.trim();
  const ub = b[2]!.trim();
  if (unitKey(ua) !== unitKey(ub)) return null;
  return { from: a[1]!, to: b[1]!, unit: ua };
}

// ------------------------------------------------------------- stored envelope

/** What AnalysisRow.result holds (StoredResult). */
export interface StoredResult {
  brew?: BrewAnalysis | null;
  trend?: TrendAnalysis | null;
  grindMove?: GrindMove | null;
  valueMove?: GrindValueMove | null;
  localWarnings?: string[];
  /**
   * Trend only: the uid of each brew the patterns name, keyed by the number the model was shown
   * (see brewUidsFor). Missing on a result stored before uids were kept. Android writes the same
   * key, so a result reads the same on both.
   */
  brewUids?: Record<string, string> | null;
}

// Which brews a stored trend points at. The model names brews by the row ids it was shown, and
// row ids are this install's own: after a backup is restored the same brews have other ids. So
// each stored result also keeps the uid behind every number, and the numbers are followed
// through the uids.

/** The table to store with a trend: every brew number its patterns name that `uidOf` knows. */
export function brewUidsFor(trend: TrendAnalysis, uidOf: (n: number) => string | null | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const n of new Set(trend.patterns.flatMap((p) => p.brewIds))) {
    const uid = uidOf(n);
    if (uid) out[String(n)] = uid;
  }
  return out;
}

/**
 * The table to read a stored result with. A result with none was stored before uids were kept.
 * Made here, its numbers are this browser's row ids (null). Made by another app and brought in,
 * they are that install's row ids and are never followed (an empty table). A result brought in
 * from another browser running this same app can't be told apart from one made here; every
 * import since uids were kept marks it with an empty table instead.
 */
export function storedBrewUids(brewUids: Readonly<Record<string, string>> | null | undefined, madeHere: boolean): Readonly<Record<string, string>> | null {
  return brewUids ?? (madeHere ? null : {});
}

/**
 * Brew number to the row id of that brew in this browser now. Null when the result has no table
 * (it was stored before uids were kept): its numbers are then read as row ids, which is right as
 * long as the rows are the ones it was made from.
 */
export function brewRowIds(brewUids: Readonly<Record<string, string>> | null | undefined, idOfUid: (uid: string) => number | null | undefined): Map<number, number> | null {
  if (brewUids == null) return null;
  const out = new Map<number, number>();
  for (const [number, uid] of Object.entries(brewUids)) {
    const n = Number(number);
    const id = idOfUid(uid);
    if (Number.isInteger(n) && id != null) out.set(n, id);
  }
  return out;
}

/** The row ids behind a pattern's numbers, each once; a brew that is gone is left out. */
export function followBrews(numbers: readonly number[], rowIds: ReadonlyMap<number, number> | null): number[] {
  if (rowIds == null) return [...new Set(numbers)];
  return [...new Set(numbers.map((n) => rowIds.get(n)).filter((id): id is number => id != null))];
}

export type Parsed<T> = { ok: true; value: T } | { ok: false; raw: string; reason: string };

// ---------------------------------------------------------------------- parser

const BREW_ANCHORS = ["verdict", "headline", "diagnosis"];
const TREND_ANCHORS = ["trajectory", "headline", "patterns"];
const THINK_BLOCK = /<think>[\s\S]*?<\/think>/gi;
const FENCE = /```[A-Za-z]*/g;
const INT = /-?\d+/;
const NUM = /-?\d+(?:\.\d+)?/;
const VERDICTS = new Set(["dialled_in", "close", "needs_change", "fault"]);
const TRAJECTORIES = new Set(["improving", "steady", "stuck", "erratic"]);
const CONFIDENCES = new Set(["low", "medium", "high"]);
const STRENGTHS = new Set(["strong", "weak", "too_little_data"]);
const TONES = new Set(["good", "attention", "fault", "neutral"]);
const MAX_TRIES = 20;

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => v != null && typeof v === "object" && !Array.isArray(v);

/** JSON.parse, forgiving trailing commas (the Kotlin reader allows them). */
function parseObject(text: string): Obj | null {
  for (const t of [text, text.replace(/,(\s*[}\]])/g, "$1")]) {
    try {
      const v: unknown = JSON.parse(t);
      return isObj(v) ? v : null;
    } catch {
      /* try the next form */
    }
  }
  return null;
}

/** Index of the brace closing the one at `start`, skipping braces inside strings; -1 if none. */
function matchingBrace(s: string, start: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < s.length; i++) {
    const c = s[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') inString = true;
    else if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

export function extractJsonObject(raw: string, anchors: readonly string[]): string | null {
  const first = raw.indexOf("{");
  const last = raw.lastIndexOf("}");
  if (first < 0 || last <= first) return null;
  const whole = raw.slice(first, last + 1);
  if (parseObject(whole) != null) return whole;
  // Prose with stray braces around the answer: try each opening brace in turn.
  let start = first;
  let tries = 0;
  while (start >= 0 && start < last && tries < MAX_TRIES) {
    const end = matchingBrace(raw, start);
    const candidates = [...new Set([end > start ? raw.slice(start, end + 1) : null, raw.slice(start, last + 1)].filter((c): c is string => c != null))];
    for (const c of candidates) {
      const obj = parseObject(c);
      if (obj && anchors.some((a) => a in obj)) return c;
    }
    start = raw.indexOf("{", start + 1);
    tries++;
  }
  return null;
}

/** Drops thinking blocks and code fences. */
function clean(raw: string): string {
  let s = raw.replace(THINK_BLOCK, "");
  const open = s.toLowerCase().indexOf("<think>");
  if (open >= 0) {
    // An unclosed think tag: its text runs up to where the answer starts.
    const brace = s.indexOf("{", open);
    s = brace >= 0 ? s.slice(0, open) + s.slice(brace) : s.slice(0, open);
  }
  return s.replace(FENCE, "").trim();
}

/** {"analysis": {...}} becomes the inner object, only when the outer has no anchor and one key. */
function unwrap(obj: Obj, anchors: readonly string[]): Obj {
  const keys = Object.keys(obj);
  if (anchors.some((a) => a in obj) || keys.length !== 1) return obj;
  const inner = obj[keys[0]!];
  return isObj(inner) ? inner : obj;
}

function locate(raw: string, anchors: readonly string[]): Obj | null {
  if (!raw.trim()) return null;
  const json = extractJsonObject(clean(raw), anchors);
  if (json == null) return null;
  const obj = parseObject(json);
  return obj ? unwrap(obj, anchors) : null;
}

// ---- coercion helpers (Kotlin's coerceInputValues + the normalisers)

/** A string field: arrays are joined, objects become their JSON, null → fallback. */
function text(v: unknown, fallback = ""): string {
  if (v == null) return fallback;
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  if (Array.isArray(v)) return v.map(itemText).filter((s): s is string => s != null).join(" ");
  return JSON.stringify(v);
}

function itemText(v: unknown): string | null {
  if (v == null) return null;
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return JSON.stringify(v);
}

function asList(v: unknown): unknown[] {
  if (Array.isArray(v)) return v;
  if (v == null) return [];
  return [v];
}

/** A list-of-strings field given as one string becomes a one-element list. */
function stringList(v: unknown): string[] {
  return asList(v)
    .map(itemText)
    .filter((s): s is string => s != null && s.trim() !== "");
}

function plain(v: unknown): string | null {
  return typeof v === "string" ? v : typeof v === "number" || typeof v === "boolean" ? String(v) : null;
}

function oneOf(v: unknown, allowed: Set<string>, fallback: string): string {
  const s = (plain(v) ?? "").trim().toLowerCase().replace(/ /g, "_").replace(/-/g, "_");
  return allowed.has(s) ? s : fallback;
}

function verdict(v: unknown): string {
  let s = (plain(v) ?? "").trim().toLowerCase().replace(/ /g, "_").replace(/-/g, "_");
  if (s === "dialed_in") s = "dialled_in";
  return VERDICTS.has(s) ? s : "";
}

/** "67", "67 clicks" and 67.0 all become 67; anything else becomes null. */
function intOrNull(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) && Math.abs(v) < 2 ** 31 ? Math.round(v) : null;
  const s = plain(v)?.trim();
  if (!s) return null;
  const n = Number(s);
  if (s !== "" && Number.isFinite(n) && Math.abs(n) < 2 ** 31) return Math.round(n);
  const m = INT.exec(s);
  return m ? Number(m[0]) : null;
}

/** "14.5", "14.5 on the dial" and 14.5 become 14.5. */
function numOrNull(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  const s = plain(v)?.trim();
  if (!s) return null;
  const n = Number(s.replace(",", "."));
  if (Number.isFinite(n)) return n;
  const m = NUM.exec(s);
  return m ? Number(m[0]) : null;
}

/** "#3", "3" and 3 become 3; "3, 6" becomes 3 and 6; anything unreadable is dropped. */
function brewIds(v: unknown): number[] {
  const out: number[] = [];
  for (const item of asList(v)) {
    const s = plain(item);
    if (s == null) continue;
    for (const m of s.matchAll(/-?\d+/g)) {
      const n = Number(m[0]);
      if (n > 0) out.push(n);
    }
  }
  return out;
}

function decodeChange(v: unknown): Change {
  const c = emptyChange();
  if (v == null) return c;
  if (!isObj(v)) {
    if (typeof v === "string" || typeof v === "number") c.instruction = text(v);
    return c;
  }
  c.variable = (plain(v.variable) ?? "").trim().toLowerCase();
  c.instruction = text(v.instruction);
  c.from = text(v.from);
  c.to = text(v.to);
  c.grindClicksFrom = intOrNull(v.grindClicksFrom);
  c.grindClicksTo = intOrNull(v.grindClicksTo);
  c.grindValueFrom = numOrNull(v.grindValueFrom);
  c.grindValueTo = numOrNull(v.grindValueTo);
  return c;
}

export function decodeBrewAnalysis(o: Obj): BrewAnalysis {
  return {
    verdict: verdict(o.verdict),
    headline: text(o.headline),
    diagnosis: text(o.diagnosis),
    confidence: oneOf(o.confidence, CONFIDENCES, "low"),
    evidence: stringList(o.evidence),
    change: decodeChange(o.change),
    expectIfRight: text(o.expectIfRight),
    ifWrong: text(o.ifWrong),
    keep: text(o.keep),
    dataWarnings: stringList(o.dataWarnings),
  };
}

function decodePattern(v: unknown): Pattern | null {
  if (isObj(v)) {
    return { title: text(v.title), detail: text(v.detail), brewIds: brewIds(v.brewIds), strength: oneOf(v.strength, STRENGTHS, "weak"), tone: oneOf(v.tone, TONES, "neutral") };
  }
  const s = plain(v);
  return s && s.trim() ? { title: s, detail: "", brewIds: [], strength: "weak", tone: "neutral" } : null;
}

function decodeExperimentBrew(v: unknown): ExperimentBrew | null {
  if (isObj(v)) return { label: text(v.label), settings: text(v.settings), grindClicks: intOrNull(v.grindClicks), expect: text(v.expect) };
  const s = plain(v);
  return s && s.trim() ? { label: "", settings: s, grindClicks: null, expect: "" } : null;
}

function decodePerCoffee(v: unknown): CoffeeNote[] {
  // {"Onyx — Tropical Weather": "one sentence"} is read as bean → note.
  if (isObj(v)) return Object.entries(v).map(([bean, note]) => ({ bean, note: text(note) }));
  return asList(v)
    .map((item): CoffeeNote | null => {
      if (isObj(item)) return { bean: text(item.bean), note: text(item.note) };
      const s = plain(item);
      return s && s.trim() ? { bean: "", note: s } : null;
    })
    .filter((x): x is CoffeeNote => x != null);
}

export function decodeTrendAnalysis(o: Obj): TrendAnalysis {
  const ex = isObj(o.experiment) ? o.experiment : {};
  return {
    trajectory: oneOf(o.trajectory, TRAJECTORIES, ""),
    headline: text(o.headline),
    summary: text(o.summary),
    confidence: oneOf(o.confidence, CONFIDENCES, "low"),
    patterns: asList(o.patterns).map(decodePattern).filter((p): p is Pattern => p != null),
    changedOften: stringList(o.changedOften),
    neverChanged: stringList(o.neverChanged),
    experiment: {
      goal: text(ex.goal),
      brews: asList(ex.brews).map(decodeExperimentBrew).filter((b): b is ExperimentBrew => b != null),
      decide: text(ex.decide),
    },
    perCoffee: decodePerCoffee(o.perCoffee),
    dataWarnings: stringList(o.dataWarnings),
  };
}

export const AnalysisParser = {
  extractJsonObject,

  parseBrew(raw: string): Parsed<BrewAnalysis> {
    const obj = locate(raw, BREW_ANCHORS);
    if (!obj) return { ok: false, raw, reason: "No JSON object in the reply." };
    const value = decodeBrewAnalysis(obj);
    if (!value.headline.trim() && !value.diagnosis.trim()) return { ok: false, raw, reason: "The reply had no headline or diagnosis." };
    return { ok: true, value };
  },

  parseTrend(raw: string): Parsed<TrendAnalysis> {
    const obj = locate(raw, TREND_ANCHORS);
    if (!obj) return { ok: false, raw, reason: "No JSON object in the reply." };
    const value = decodeTrendAnalysis(obj);
    if (!value.headline.trim() && value.patterns.length === 0) return { ok: false, raw, reason: "The reply had no headline or patterns." };
    return { ok: true, value };
  },
};

/** Reads a stored result back (rows from either platform), tolerating any shape. */
export function readStoredResult(v: unknown): StoredResult | null {
  if (!isObj(v)) return null;
  const out: StoredResult = {};
  if (isObj(v.brew)) out.brew = decodeBrewAnalysis(v.brew);
  if (isObj(v.trend)) out.trend = decodeTrendAnalysis(v.trend);
  if (isObj(v.grindMove) && typeof v.grindMove.to === "number") {
    const g = v.grindMove;
    out.grindMove = {
      from: typeof g.from === "number" ? g.from : null,
      to: g.to as number,
      clicksPerTurn: typeof g.clicksPerTurn === "number" ? g.clicksPerTurn : 0,
      lowerIsFiner: g.lowerIsFiner !== false,
      dialFormat: typeof g.dialFormat === "string" ? g.dialFormat : "",
    };
  }
  if (isObj(v.valueMove) && typeof v.valueMove.to === "number") {
    const g = v.valueMove;
    out.valueMove = {
      from: typeof g.from === "number" ? g.from : null,
      to: g.to as number,
      lowerIsFiner: g.lowerIsFiner !== false,
      unit: typeof g.unit === "string" ? g.unit : "",
      dialFormat: typeof g.dialFormat === "string" ? g.dialFormat : "",
    };
  }
  out.localWarnings = stringList(v.localWarnings);
  if (isObj(v.brewUids)) out.brewUids = Object.fromEntries(Object.entries(v.brewUids).filter((e): e is [string, string] => typeof e[1] === "string"));
  return out;
}

/** "1Zpresso K-Ultra" for prompts and labels. */
export function grinderName(g: Pick<GrinderRow, "brand" | "model">): string {
  return grinderDisplay(g);
}
