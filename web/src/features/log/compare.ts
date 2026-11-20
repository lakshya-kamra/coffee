/**
 * Compare two brews (v4 §3.8): the rows of the two-column table, the word that says how the right
 * brew differs from the left one ("finer by 5 clicks", "+0:20", "2 °C hotter", "+1"), and the
 * picker's order (same recipe, then same coffee, then the rest). Pure, so the words are tested.
 */
import type { BrewRow } from "@/data/schema";
import { clock, epochDayOf, grams, oneDp, plural } from "@/domain/core/format";
import { recipeKey } from "@/domain/core/logQuery";
import { signedClock } from "@/domain/core/status";
import { cToF, type Units } from "@/domain/core/units";

/** Minus sign, not a hyphen, like signedClock. */
const MINUS = "−";

function signed(n: number, text: string): string {
  return n < 0 ? `${MINUS}${text}` : `+${text}`;
}

/** Up to two decimals without trailing zeros: 0.3, 1.25, 4. */
function short(v: number): string {
  return String(Math.round(v * 100) / 100);
}

/**
 * The grind change from `a` to `b`. Only on the same grinder, because clicks on two grinders are
 * different units. `lowerIsFiner` is that grinder's direction.
 */
export function grindWord(a: Pick<BrewRow, "grinderId" | "grinderLabel" | "grindClicks" | "grindValue">, b: Pick<BrewRow, "grinderId" | "grinderLabel" | "grindClicks" | "grindValue">, lowerIsFiner = true): string | null {
  const same = a.grinderId != null && b.grinderId != null ? a.grinderId === b.grinderId : a.grinderLabel.trim() !== "" && a.grinderLabel.trim() === b.grinderLabel.trim();
  const av = a.grindClicks ?? a.grindValue;
  const bv = b.grindClicks ?? b.grindValue;
  if (av == null || bv == null) return null;
  if (!same) return "other grinder";
  const d = bv - av;
  if (Math.abs(d) < 1e-9) return null;
  const finer = lowerIsFiner ? d < 0 : d > 0;
  const size = Math.abs(d);
  const amount = a.grindClicks != null && b.grindClicks != null ? plural(Math.round(size), "click") : short(size);
  return `${finer ? "finer" : "coarser"} by ${amount}`;
}

/** "+0:20" / "−0:15"; null when either time is missing or they match. */
export function timeWord(aSec: number, bSec: number): string | null {
  if (aSec <= 0 || bSec <= 0 || aSec === bSec) return null;
  return signedClock(bSec - aSec);
}

/** "2 °C hotter" / "4 °F cooler", in the unit the person reads. */
export function tempWord(aC: number, bC: number, units: Pick<Units, "fahrenheit">): string | null {
  if (aC <= 0 || bC <= 0) return null;
  const d = units.fahrenheit ? cToF(bC) - cToF(aC) : Math.round(bC) - Math.round(aC);
  if (d === 0) return null;
  return `${Math.abs(d)} ${units.fahrenheit ? "°F" : "°C"} ${d > 0 ? "hotter" : "cooler"}`;
}

/** "+2 g" / "−0.5 g". */
export function gramsWord(a: number, b: number): string | null {
  if (a <= 0 || b <= 0) return null;
  const d = Math.round((b - a) * 10) / 10;
  if (d === 0) return null;
  return signed(d, `${grams(Math.abs(d))} g`);
}

/** Less water per gram is a stronger brew. Differences under 0.1 of a part are noise. */
export function ratioWord(aDose: number, aWater: number, bDose: number, bWater: number): string | null {
  if (aDose <= 0 || bDose <= 0 || aWater <= 0 || bWater <= 0) return null;
  const d = bWater / bDose - aWater / aDose;
  if (Math.abs(d) < 0.1) return null;
  return d < 0 ? "stronger" : "weaker";
}

/** "+1" / "−2"; nothing when either is unrated. */
export function scoreWord(a: number, b: number): string | null {
  if (a <= 0 || b <= 0 || a === b) return null;
  return signed(b - a, String(Math.abs(b - a)));
}

/** "3 days later", "same day", "1 day earlier". */
export function dateWord(aAt: number, bAt: number): string {
  const d = epochDayOf(bAt) - epochDayOf(aAt);
  if (d === 0) return "same day";
  return `${plural(Math.abs(d), "day")} ${d > 0 ? "later" : "earlier"}`;
}

/** Faults added and gone: "+Bitter, −Thin". */
export function faultsWord(a: readonly string[], b: readonly string[]): string | null {
  const added = b.filter((f) => !a.includes(f)).map((f) => `+${f}`);
  const gone = a.filter((f) => !b.includes(f)).map((f) => `${MINUS}${f}`);
  const all = [...added, ...gone];
  return all.length ? all.join(", ") : null;
}

/** "+5 days off roast" on the same coffee; "other coffee" when they differ. */
export function coffeeWord(a: Pick<BrewRow, "beanId" | "beanLabel" | "daysOffRoast">, b: Pick<BrewRow, "beanId" | "beanLabel" | "daysOffRoast">): string | null {
  const same = a.beanId != null && b.beanId != null ? a.beanId === b.beanId : a.beanLabel.trim() === b.beanLabel.trim();
  if (!same) return a.beanLabel.trim() || b.beanLabel.trim() ? "other coffee" : null;
  if (a.daysOffRoast == null || b.daysOffRoast == null || a.daysOffRoast === b.daysOffRoast) return null;
  const d = b.daysOffRoast - a.daysOffRoast;
  return `${signed(d, plural(Math.abs(d), "day"))} off roast`;
}

// ---------------------------------------------------------------- rows

export interface CompareRow {
  readonly label: string;
  readonly left: string;
  readonly right: string;
  /** How the right brew differs; null when it doesn't (or it can't be said). */
  readonly word: string | null;
  /** Long text (notes): wraps, no word. */
  readonly long?: boolean;
}

export interface CompareContext {
  readonly units: Units;
  /** Planned total of each brew's recipe, seconds; null when the recipe is gone. */
  readonly planA: number | null;
  readonly planB: number | null;
  /** The grinder direction (the left brew's grinder). */
  readonly lowerIsFiner: boolean;
  /** "WATER" / "YIELD" (espresso), from methodLabels. */
  readonly waterLabel: string;
  /** Screen date for a brew ("Thu 12 Nov, 08:14"). */
  readonly dateOf: (at: number) => string;
}

function grindText(b: BrewRow): string {
  if (b.grindClicks != null) return plural(b.grindClicks, "click");
  if (b.grindValue != null) return short(b.grindValue);
  return b.grindSetting.trim() || "—";
}

function coffeeText(b: BrewRow): string {
  const name = b.beanLabel.trim() || "—";
  return b.daysOffRoast != null ? `${name} (${plural(b.daysOffRoast, "day")} off roast)` : name;
}

function timeText(b: BrewRow, plan: number | null): string {
  if (b.totalTimeSec <= 0) return "—";
  if (plan == null || plan <= 0) return clock(b.totalTimeSec);
  const d = b.totalTimeSec - plan;
  return `${clock(b.totalTimeSec)} (${d === 0 ? "on plan" : `${signedClock(d)} vs plan`})`;
}

function tempText(b: BrewRow, units: Units): string {
  if (b.tempC <= 0) return "—";
  return units.fahrenheit ? `${cToF(b.tempC)} °F` : `${Math.round(b.tempC)} °C`;
}

/** Date, Recipe, Coffee, Grind, Dose, Water/Yield, Ratio, Temp, Time vs plan, Overall, Faults, Notes. */
export function compareRows(a: BrewRow, b: BrewRow, ctx: CompareContext): CompareRow[] {
  const recipeA = a.recipeName.trim() || "Brew";
  const recipeB = b.recipeName.trim() || "Brew";
  const notes = (x: BrewRow) => [x.notes.trim(), x.nextTime.trim() ? `Next time: ${x.nextTime.trim()}` : ""].filter(Boolean).join("\n") || "—";
  return [
    { label: "Date", left: ctx.dateOf(a.startedAt), right: ctx.dateOf(b.startedAt), word: dateWord(a.startedAt, b.startedAt) },
    { label: "Recipe", left: recipeA, right: recipeB, word: recipeKey(a) === recipeKey(b) ? null : "other recipe" },
    { label: "Coffee", left: coffeeText(a), right: coffeeText(b), word: coffeeWord(a, b) },
    { label: "Grind", left: grindText(a), right: grindText(b), word: grindWord(a, b, ctx.lowerIsFiner) },
    { label: "Dose", left: a.doseG > 0 ? `${grams(a.doseG)} g` : "—", right: b.doseG > 0 ? `${grams(b.doseG)} g` : "—", word: gramsWord(a.doseG, b.doseG) },
    { label: ctx.waterLabel, left: a.waterG > 0 ? `${grams(a.waterG)} g` : "—", right: b.waterG > 0 ? `${grams(b.waterG)} g` : "—", word: gramsWord(a.waterG, b.waterG) },
    { label: "Ratio", left: a.doseG > 0 ? `1:${oneDp(a.waterG / a.doseG)}` : "—", right: b.doseG > 0 ? `1:${oneDp(b.waterG / b.doseG)}` : "—", word: ratioWord(a.doseG, a.waterG, b.doseG, b.waterG) },
    { label: "Temp", left: tempText(a, ctx.units), right: tempText(b, ctx.units), word: tempWord(a.tempC, b.tempC, ctx.units) },
    { label: "Time vs plan", left: timeText(a, ctx.planA), right: timeText(b, ctx.planB), word: timeWord(a.totalTimeSec, b.totalTimeSec) },
    { label: "Overall", left: a.overall > 0 ? `${a.overall}/10` : "Unrated", right: b.overall > 0 ? `${b.overall}/10` : "Unrated", word: scoreWord(a.overall, b.overall) },
    { label: "Faults", left: a.faults.join(", ") || "None", right: b.faults.join(", ") || "None", word: faultsWord(a.faults, b.faults) },
    { label: "Notes", left: notes(a), right: notes(b), word: null, long: true },
  ];
}

// ---------------------------------------------------------------- picker

export interface PickerGroup {
  readonly title: string;
  readonly brews: readonly BrewRow[];
}

/**
 * The other brews to compare `base` with: same recipe first, then the same coffee, then the rest,
 * each newest first. Empty groups are left out.
 */
export function comparePickerGroups(base: BrewRow, brews: readonly BrewRow[]): PickerGroup[] {
  const others = brews.filter((b) => b.uid !== base.uid).sort((x, y) => y.startedAt - x.startedAt);
  const sameCoffee = (b: BrewRow) => (base.beanId != null && b.beanId != null ? base.beanId === b.beanId : base.beanLabel.trim() !== "" && base.beanLabel.trim() === b.beanLabel.trim());
  const recipe = others.filter((b) => recipeKey(b) === recipeKey(base));
  const coffee = others.filter((b) => recipeKey(b) !== recipeKey(base) && sameCoffee(b));
  const rest = others.filter((b) => recipeKey(b) !== recipeKey(base) && !sameCoffee(b));
  return [
    { title: "Same recipe", brews: recipe },
    { title: "Same coffee", brews: coffee },
    { title: "Everything else", brews: rest },
  ].filter((g) => g.brews.length > 0);
}

/** The path that compares `uid` with `other`. */
export function comparePath(uid: string, other: string): string {
  return `/log/${encodeURIComponent(uid)}/compare/${encodeURIComponent(other)}`;
}
