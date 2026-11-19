/**
 * Review's field logic, ported from ReviewViewModel.kt: the typed-time parser, digit cleaning and
 * the grind warnings Review shows while you type (the three grind rules of DataQuality.kt, word for
 * word, so an impossible number is caught before it is saved). Pure.
 */
import type { BrewRow, GrinderRow } from "@/data/schema";
import { isFilterStyle, type BrewMethod } from "@/domain/core/brewer";
import { clock } from "@/domain/core/format";
import { DEFAULT_TASTE, type TasteProfile } from "@/domain/core/evaluation";
import { finerThanEspresso, format, rangeFor, underOneLegacyTurn, type GrindScale } from "@/domain/grind";

/** '.' and ',' count as ':' because number keyboards rarely have a colon. */
const SEPARATORS = new Set([":", ".", ","]);

/**
 * Reads a typed brew time. "3:28", "3.28" and "328" are all 3 min 28 s; one or two bare digits
 * are seconds. Null for anything else, including 60 or more seconds after a separator.
 */
export function parseClock(text: string): number | null {
  const t = text.trim();
  if (!t) return null;
  const sep = [...t].findIndex((c) => SEPARATORS.has(c));
  let minutes: number;
  let seconds: number;
  const int = (s: string) => (/^\d+$/.test(s) ? Number(s) : null);
  if (sep >= 0) {
    const secText = t.slice(sep + 1);
    if (secText.length > 2 || [...secText].some((c) => SEPARATORS.has(c))) return null;
    const mm = int(t.slice(0, sep) || "0");
    // "3:" and "3:4" are half-typed "3:45"; read them as they stand.
    const ss = int(secText || "0");
    if (mm == null || ss == null || ss >= 60) return null;
    minutes = mm;
    seconds = ss;
  } else if (t.length <= 2) {
    const ss = int(t);
    if (ss == null) return null;
    minutes = 0;
    seconds = ss;
  } else {
    const mm = int(t.slice(0, -2));
    const ss = int(t.slice(-2));
    if (mm == null || ss == null || ss >= 60) return null;
    minutes = mm;
    seconds = ss;
  }
  return minutes * 60 + seconds;
}

/** Keeps digits and the clock separators, at most six characters (setTotalTimeText). */
export function cleanClockText(v: string): string {
  return [...v].filter((c) => /\d/.test(c) || SEPARATORS.has(c)).join("").slice(0, 6);
}

export function digitsOnly(v: string, max: number): string {
  return v.replace(/\D/g, "").slice(0, max);
}

/** "18.5" / "18,5" → 18.5; anything else → null (Kotlin toDoubleOrNull). */
export function parseDecimal(v: string): number | null {
  const t = v.trim().replace(",", ".");
  return /^-?\d+(\.\d+)?$|^-?\d*\.\d+$/.test(t) ? Number(t) : null;
}

export function toggleIn(list: readonly string[], v: string): string[] {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
}

/** The click count's reading on this grinder ("dial 7.5"), or null when the dial adds nothing. */
export function dialHelper(text: string): string | null {
  return text.trim() && text !== "clicks from zero" ? text : null;
}

/**
 * The reason the analysis would distrust this brew's grind, or null (DataQuality.kt's three grind
 * rules, user-facing wording): zero clicks, under one legacy turn on a filter brew, or finer than
 * the grinder's espresso range on a filter brew.
 */
export function grindWarning(log: Pick<BrewRow, "grindClicks" | "totalTimeSec">, grinder: GrinderRow | null, scale: GrindScale | null, method: BrewMethod | null): string | null {
  const clicks = log.grindClicks;
  if (clicks == null) return null;
  const filter = method == null || isFilterStyle(method);
  if (clicks === 0) {
    const runFor = log.totalTimeSec > 0 ? ` for ${clock(log.totalTimeSec)}` : "";
    return `Grind logged as 0 clicks, which is burrs touching. Nothing brews${runFor} at 0, so the grind was not recorded.`;
  }
  if (filter && underOneLegacyTurn(clicks, scale, method)) {
    const cpr = grinder?.clicksPerRotation ?? 0;
    return `${clicks} clicks is less than one full turn, finer than espresso. For a filter brew that is almost certainly the dial reading rather than total clicks; ${clicks} on the dial would be ${clicks * cpr} clicks.`;
  }
  if (filter && scale && finerThanEspresso(clicks, scale)) {
    const range = rangeFor(scale, "espresso");
    if (!range) return null;
    const who = scale.rangeSources[0]?.who ? `, ${scale.rangeSources[0].who}` : "";
    const stored = scale.dial.kind === "number-tick" ? " On this grinder the dial reading 7.5 is stored as 75." : "";
    return `${clicks} is finer than this grinder's espresso starting range (${format(scale, range.from)}–${format(scale, range.to)}${who}). For a filter brew that is almost certainly the dial reading typed the wrong way.${stored}`;
  }
  return null;
}

/** Every slider still at its default: the analysis treats taste as missing. */
export function tasteUntouched(t: TasteProfile): boolean {
  return (Object.keys(DEFAULT_TASTE) as (keyof TasteProfile)[]).every((k) => t[k] === DEFAULT_TASTE[k]);
}
