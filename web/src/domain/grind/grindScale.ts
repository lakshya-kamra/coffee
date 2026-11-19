/**
 * Port of A§3.2–3.3 `GrindScale`: everything the app says about a grinder's numbers. The row's
 * own numbers (what the user saw and accepted) win for cpr / microns / min / max / step /
 * direction; the catalog supplies ranges, sources, confidence, dial-reading help and entry hint.
 *
 * K-Ultra (lead decision 2): style "clicks", stored value = total clicks from zero, 100 clicks
 * per rotation, 20 µm per click; dial "7.5" = 75. Never "10 clicks = one turn" unless the row
 * itself says cpr 10 (a 1.x row whose scale notice is still pending, rule 4 below).
 */
import type { CatalogGrinder, GrindRange, SourceRef } from "@/catalog/types";
import type { BrewMethod } from "@/domain/core/brewer";
import { isBrewMethod } from "@/domain/core/brewer";
import { groupedInt } from "@/domain/core/format";
import type { GrinderRow, SettingStyle } from "@/data/schema";
import { decodeDialFormat, DialFormats, PLAIN, type DialFormat } from "./dialFormat";
import { moveSizes, type MoveSizes } from "./moveSizes";
import { NUMBERED_DIAL_IDS } from "./numberedDials";

export interface DialReading {
  readonly turns?: number;
  readonly number?: number;
  readonly tick?: number;
  /** Plain dials: the number itself. */
  readonly value?: number | null;
}

export interface GrindScale {
  /** Legacy "microns" rows are treated as numbers with unit "µm" (A§3.1). */
  readonly style: Exclude<SettingStyle, "microns">;
  readonly unit: string;
  readonly min: number;
  readonly max: number;
  readonly step: number;
  readonly lowerIsFiner: boolean;
  readonly clicksPerRotation: number | null;
  readonly micronsPerStep: number | null;
  readonly dial: DialFormat;
  readonly ranges: Readonly<Partial<Record<BrewMethod, GrindRange>>>;
  readonly rangeSources: readonly SourceRef[];
  readonly confidence: string | null;
  readonly dialReadingHelp: string;
  readonly entryHint: string;
}

/** The fields GrindScale reads from a grinder row. */
export type GrinderScaleInput = Pick<
  GrinderRow,
  "settingStyle" | "clicksPerRotation" | "micronsPerClick" | "lowerIsFiner" | "minSetting" | "maxSetting" | "step" | "catalogId" | "dialFormat"
>;

function positive(n: number | null | undefined): number | null {
  return n != null && n > 0 ? n : null;
}

/**
 * A row whose turn size disagrees with its catalog entry has a scale notice pending: nothing
 * numeric from the catalog applies until the user accepts its numbers (as Kotlin `agrees`).
 */
function agreesWithCatalog(g: GrinderScaleInput, catalog: CatalogGrinder | null): catalog is CatalogGrinder {
  if (catalog == null) return false;
  const catCpr = catalog.setting.clicksPerRotation;
  return catCpr == null || catCpr === g.clicksPerRotation;
}

/** First match wins (A§3.2 resolution, rules 1–6). Non-click dials always read plain. */
export function resolveDialFormat(g: GrinderScaleInput, catalog: CatalogGrinder | null): DialFormat {
  const clicks = g.settingStyle === "clicks";
  const f = resolveRaw(g, catalog);
  return !clicks && f.kind !== "plain" ? PLAIN : f;
}

function resolveRaw(g: GrinderScaleInput, catalog: CatalogGrinder | null): DialFormat {
  const clicks = g.settingStyle === "clicks";
  const cpr = g.clicksPerRotation > 0 ? g.clicksPerRotation : null;
  const catCpr = catalog?.setting.clicksPerRotation ?? null;
  // 1. The user chose a format in the editor.
  const own = decodeDialFormat(g.dialFormat, cpr);
  if (own) return own;
  // 2. The catalog's dialFormat (lead decision 1), only while the row agrees with the catalog.
  if (agreesWithCatalog(g, catalog)) {
    const f = decodeDialFormat(catalog.setting.dialFormat, cpr);
    if (f) return f;
  }
  // 3. A known numbered dial whose row carries the catalog's clicks per rotation.
  if (catalog && NUMBERED_DIAL_IDS.has(catalog.id) && catCpr != null && catCpr === g.clicksPerRotation) return DialFormats.numberTick(catCpr, 10);
  // 4. Legacy 1.x behaviour, kept until the user accepts the catalog's numbers: 75 → "7 turns + 5 · dial 7.5".
  if (clicks && g.clicksPerRotation === 10 && (catalog == null || catCpr !== 10)) return DialFormats.turnsDotClicks(10);
  // 5. Any other click grinder with a known rotation.
  if (clicks && cpr != null) return DialFormats.turnsAndClicks(cpr);
  // 6.
  return PLAIN;
}

export function grindScaleOf(g: GrinderScaleInput, catalog: CatalogGrinder | null = null): GrindScale {
  const agrees = agreesWithCatalog(g, catalog);
  const style = g.settingStyle === "microns" ? "numbers" : g.settingStyle;
  const catUnit = agrees && catalog.setting.unit !== "clicks" ? catalog.setting.unit : "";
  const unit = g.settingStyle === "microns" ? "µm" : g.settingStyle === "clicks" ? "clicks" : catUnit;
  const ranges: Partial<Record<BrewMethod, GrindRange>> = {};
  if (agrees) for (const [k, r] of Object.entries(catalog.ranges)) if (isBrewMethod(k) && r) ranges[k] = r;
  return Object.freeze({
    style,
    unit,
    min: g.minSetting,
    max: g.maxSetting,
    step: g.step > 0 ? g.step : 1,
    lowerIsFiner: g.lowerIsFiner,
    clicksPerRotation: positive(g.clicksPerRotation),
    micronsPerStep: positive(g.micronsPerClick),
    dial: resolveDialFormat(g, catalog),
    ranges: Object.freeze(ranges),
    rangeSources: agrees ? catalog.rangeSources : [],
    confidence: agrees ? catalog.confidence : null,
    dialReadingHelp: catalog?.setting.dialReading ?? "",
    entryHint: catalog?.setting.entryHint ?? "",
  });
}

export function usesClicks(s: GrindScale): boolean {
  return s.style === "clicks";
}

/** "FEWER clicks is finer" | "MORE clicks is finer" | "LOWER settings are finer" | "HIGHER settings are finer". */
export function finerDirection(s: GrindScale): string {
  if (usesClicks(s)) return s.lowerIsFiner ? "FEWER clicks is finer" : "MORE clicks is finer";
  return s.lowerIsFiner ? "LOWER settings are finer" : "HIGHER settings are finer";
}

export function finerWords(s: GrindScale): string {
  return finerDirection(s).toLowerCase();
}

function turnsWord(n: number): string {
  return n === 1 ? "turn" : "turns";
}

/** Dial words for a stored value: null for plain dials. */
export function reading(s: GrindScale, value: number): string | null {
  const d = s.dial;
  if (d.kind === "plain" || !Number.isFinite(value)) return null;
  const v = Math.round(value);
  if (v < 0) return null;
  switch (d.kind) {
    case "turns": {
      const turns = Math.floor(v / d.clicksPerTurn);
      return `${turns} ${turnsWord(turns)} + ${v % d.clicksPerTurn}`;
    }
    case "turns-dot":
      return `${Math.floor(v / d.clicksPerTurn)}.${v % d.clicksPerTurn}`;
    case "number-tick": {
      const turns = d.clicksPerRotation > 0 ? Math.floor(v / d.clicksPerRotation) : 0;
      const within = v - turns * d.clicksPerRotation;
      const nt = `${Math.floor(within / d.ticksPerNumber)}.${within % d.ticksPerNumber}`;
      return turns === 0 ? nt : `${turns} ${turnsWord(turns)} + ${nt}`;
    }
  }
}

/**
 * The caption under the big grind number (A§3.2 vectors), e.g. "dial 7.5 · ≈ 1,500 µm".
 * "" for no value: the brew screen shows "Set your grind" itself, as Android's call site does.
 */
export function describe(s: GrindScale, value: number | null | undefined): string {
  if (value == null) return "";
  const parts: string[] = [];
  const d = s.dial;
  const r = reading(s, value);
  if (d.kind === "turns-dot" && r != null) {
    const v = Math.round(value);
    const turns = Math.floor(v / d.clicksPerTurn);
    parts.push(`${turns} ${turnsWord(turns)} + ${v % d.clicksPerTurn}`, `dial ${r}`);
  } else if (d.kind === "number-tick" && r != null) {
    parts.push(`dial ${r}`);
  } else if (d.kind === "turns" && r != null) {
    parts.push(r);
  }
  if (usesClicks(s) && s.micronsPerStep != null) parts.push(`≈ ${groupedInt(s.micronsPerStep * value)} µm`);
  if (parts.length === 0) return usesClicks(s) ? "clicks from zero" : "";
  return parts.join(" · ");
}

/** Inverse of reading(); null when the reading cannot exist on this dial. */
export function fromReading(s: GrindScale, r: DialReading): number | null {
  const d = s.dial;
  const turns = r.turns ?? 0;
  const tick = r.tick ?? 0;
  if (turns < 0 || tick < 0) return null;
  switch (d.kind) {
    case "plain":
      return r.value ?? null;
    case "number-tick": {
      const number = r.number ?? 0;
      if (number < 0 || tick >= d.ticksPerNumber) return null;
      if (d.clicksPerRotation > 0 && number * d.ticksPerNumber >= d.clicksPerRotation) return null;
      if (d.clicksPerRotation <= 0 && turns > 0) return null;
      return turns * d.clicksPerRotation + number * d.ticksPerNumber + tick;
    }
    case "turns":
    case "turns-dot":
      if (tick >= d.clicksPerTurn) return null;
      return turns * d.clicksPerTurn + tick;
  }
}

const NUMBER_TICK_RE = /^\s*(?:(\d+)\s*\+\s*)?(\d+)(?:[.,](\d))?\s*$/;
const TURNS_RE = /^\s*(\d+)\s*[+.,]\s*(\d+)\s*$/;

/** "7.5", "1+2.5", "3 + 2" per format → stored value. */
export function parseReading(s: GrindScale, text: string): number | null {
  const d = s.dial;
  switch (d.kind) {
    case "number-tick": {
      const m = NUMBER_TICK_RE.exec(text);
      if (!m) return null;
      return fromReading(s, { turns: m[1] ? Number(m[1]) : 0, number: Number(m[2]), tick: m[3] ? Number(m[3]) : 0 });
    }
    case "turns":
    case "turns-dot": {
      const m = TURNS_RE.exec(text);
      if (!m) return null;
      return fromReading(s, { turns: Number(m[1]), tick: Number(m[2]) });
    }
    case "plain": {
      const t = text.trim().replace(",", ".");
      if (!/^-?\d+(\.\d+)?$/.test(t)) return null;
      return Number(t);
    }
  }
}

/** "75" for clicks, "14.5" for numbers. */
export function format(s: GrindScale, value: number): string {
  if (usesClicks(s)) return String(Math.round(value));
  // Two decimals at most (Vario "5.12"), with float dust removed.
  return String(Math.round(value * 100) / 100);
}

export function rangeFor(s: GrindScale, method: BrewMethod | null | undefined): GrindRange | null {
  return method ? (s.ranges[method] ?? null) : null;
}

/** ranges.espresso.from: anything finer is a dial reading typed into a filter brew. */
export function espressoFloor(s: GrindScale): number | null {
  return s.ranges.espresso?.from ?? null;
}

export function moves(s: GrindScale, method: BrewMethod | null | undefined): MoveSizes {
  return moveSizes(s, method);
}
