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
