/**
 * Port of A§7.8 `Units`. Lead decision 3: brew targets (dose, water, pours) stay in grams in
 * ounce mode; only bag sizes and totals convert. Temperatures convert fully in °F mode.
 */
import { grams, oneDp } from "./format";

export const GRAMS_PER_OUNCE = 28.349523125;

export interface Units {
  readonly fahrenheit: boolean;
  readonly ounces: boolean;
}

export const DEFAULT_UNITS: Units = Object.freeze({ fahrenheit: false, ounces: false });

export function unitsOf(settings: { tempUnit: string; weightUnit: string }): Units {
  return { fahrenheit: settings.tempUnit === "f", ounces: settings.weightUnit === "oz" };
}

export function cToF(c: number): number {
  return Math.round((c * 9) / 5 + 32);
}

/** The number to show for a °C value: "93", or "199" in °F mode. */
export function temp(units: Units, c: number): string {
  return String(units.fahrenheit ? cToF(c) : Math.round(c));
}

export function tempUnit(units: Units): string {
  return units.fahrenheit ? "°F" : "°C";
}

/** "93 °C" / "199 °F". */
export function tempLabel(units: Units, c: number): string {
  return `${temp(units, c)} ${tempUnit(units)}`;
}

/** Bags and totals: ["250", "g"] or ["8.8", "oz"] (oz to 1 dp). */
export function bigWeight(units: Units, g: number): [value: string, unit: string] {
  return units.ounces ? [oneDp(g / GRAMS_PER_OUNCE), "oz"] : [grams(g), "g"];
}

// ---------------------------------------------------------------- v4 §4.15

/** Regions that read temperatures in °F. */
export const FAHRENHEIT_REGIONS: ReadonlySet<string> = new Set(["US", "PR", "GU", "VI", "AS", "MP", "UM", "LR", "BZ", "BS", "KY", "PW", "FM", "MH"]);

/** The region of a BCP 47 tag ("en-US" → "US"), parsed as localCurrency does; null when there is none. */
export function localeRegion(locale: string): string | null {
  return /[-_]([A-Za-z]{2})\b/.exec(locale)?.[1]?.toUpperCase() ?? null;
}

/** "f" for a fresh install in a °F region, else "c". */
export function defaultTempUnit(locale: string): "c" | "f" {
  const region = localeRegion(locale);
  return region != null && FAHRENHEIT_REGIONS.has(region) ? "f" : "c";
}

/**
 * Prose temperatures are written "70 °C" or "90-95 °C" (shared/recipes.json). The same pattern
 * is in shared/fixtures/domain-v4-cases.json, so both apps convert the same text.
 */
export const PROSE_TEMP = String.raw`(\d+(?:\.\d+)?)(?:\s*[-–]\s*(\d+(?:\.\d+)?))?\s*°C`;

/**
 * Steps with their prose in the user's unit: titles too ("CLOSE, 160 g at 70 °C"), not just the
 * details, so a °F person never sees °C beside °F everywhere else (v4 §4.15). Same array for "c".
 */
export function stepsInUnit<S extends { readonly title: string; readonly detail: string }>(steps: readonly S[], unit: "c" | "f"): readonly S[] {
  if (unit !== "f") return steps;
  return steps.map((s) => ({ ...s, title: renderTemps(s.title, unit), detail: renderTemps(s.detail, unit) }));
}

/** Recipe prose in the user's unit: "70 °C" → "158 °F" when unit is "f"; unchanged for "c". */
export function renderTemps(text: string, unit: "c" | "f"): string {
  if (unit !== "f") return text;
  return text.replace(new RegExp(PROSE_TEMP, "g"), (_m, a: string, b: string | undefined) =>
    b == null ? `${cToF(Number(a))} °F` : `${cToF(Number(a))}-${cToF(Number(b))} °F`,
  );
}
