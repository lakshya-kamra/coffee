/**
 * Pure helpers behind the Gear screens: catalog → grinder row (A§7.4), the scale notice
 * (A§6/§7.4), the scale summary words, bean altitude and rest status (A§7.6). No React.
 */
import type { CatalogGrinder } from "@/catalog/types";
import type { BeanRow, BurrType, GrinderRow, SettingStyle } from "@/data/schema";
import { groupedInt, oneDp } from "@/domain/core/format";
import { normaliseText } from "@/domain/search";

// ---------------------------------------------------------------- grinders

const BURRS: readonly BurrType[] = ["conical", "flat", "ghost", "blade"];

/** "48.0" → "48", 47.5 → "47.5" (A§7.4 burrSizeMm). */
export function burrSizeText(mm: number | null): string {
  if (mm == null || !Number.isFinite(mm)) return "";
  return Number.isInteger(mm) ? String(mm) : String(Math.round(mm * 10) / 10);
}

/**
 * The fields a catalog pick fills (A§7.4). Usual filter/espresso are not prefilled; the dial
 * format is left on auto so the catalog's own dialFormat applies.
 */
export function fieldsFromCatalog(c: CatalogGrinder): Pick<
  GrinderRow,
  "brand" | "model" | "burrType" | "burrSizeMm" | "settingStyle" | "clicksPerRotation" | "micronsPerClick" | "lowerIsFiner" | "minSetting" | "maxSetting" | "step" | "catalogId" | "dialFormat"
> {
  return {
    brand: c.brand,
    model: c.model,
    burrType: (BURRS as readonly string[]).includes(c.burr) ? (c.burr as BurrType) : null,
    burrSizeMm: burrSizeText(c.burrSizeMm),
    settingStyle: c.setting.style,
    clicksPerRotation: c.setting.clicksPerRotation ?? 0,
    micronsPerClick: c.setting.micronsPerStep,
    lowerIsFiner: c.setting.lowerIsFiner,
    minSetting: c.setting.min,
    maxSetting: c.setting.max,
    step: c.setting.step > 0 ? c.setting.step : 1,
    catalogId: c.id,
    dialFormat: "",
  };
}

/** The numbers "Use these numbers" copies from the catalog (A§7.4): the scale only. */
export function scaleFromCatalog(c: CatalogGrinder): Pick<GrinderRow, "settingStyle" | "clicksPerRotation" | "micronsPerClick" | "lowerIsFiner" | "minSetting" | "maxSetting" | "step"> {
  const f = fieldsFromCatalog(c);
  return { settingStyle: f.settingStyle, clicksPerRotation: f.clicksPerRotation, micronsPerClick: f.micronsPerClick, lowerIsFiner: f.lowerIsFiner, minSetting: f.minSetting, maxSetting: f.maxSetting, step: f.step };
}

function sameNumber(a: number | null, b: number | null): boolean {
  if (a == null || b == null) return a == b;
  return Math.abs(a - b) < 1e-9;
}

/** Fields where the row disagrees with its catalog entry (the editor's "Edited" chip). */
export function scaleDifferences(g: GrinderRow, c: CatalogGrinder): ("style" | "clicksPerRotation" | "micronsPerClick" | "lowerIsFiner" | "range")[] {
  const out: ("style" | "clicksPerRotation" | "micronsPerClick" | "lowerIsFiner" | "range")[] = [];
  const style: SettingStyle = g.settingStyle === "microns" ? "numbers" : g.settingStyle;
  if (style !== c.setting.style) out.push("style");
  if ((c.setting.clicksPerRotation ?? 0) !== g.clicksPerRotation) out.push("clicksPerRotation");
  if (!sameNumber(c.setting.micronsPerStep, g.micronsPerClick)) out.push("micronsPerClick");
  if (c.setting.lowerIsFiner !== g.lowerIsFiner) out.push("lowerIsFiner");
  if (!sameNumber(c.setting.min, g.minSetting) || !sameNumber(c.setting.max, g.maxSetting) || !sameNumber(c.setting.step, g.step)) out.push("range");
  return out;
}

export const scaleNoticeId = (uid: string): string => `grinder-scale:${uid}`;

/** Who the catalog's numbers come from: the first source, else the maker. */
export function catalogAuthority(c: CatalogGrinder): string {
  return c.rangeSources[0]?.who || c.brand || "The catalog";
}

/**
 * "1Zpresso says 100 clicks a turn; yours says 10" (A§7.4). Falls back to the first field that
 * differs when clicks per turn agree.
 */
export function scaleNoticeText(g: GrinderRow, c: CatalogGrinder): string {
  const who = catalogAuthority(c);
  const cpr = c.setting.clicksPerRotation ?? 0;
  if (cpr !== g.clicksPerRotation) {
    const theirs = cpr > 0 ? `${cpr} clicks a turn` : "no fixed clicks per turn";
    const yours = g.clicksPerRotation > 0 ? String(g.clicksPerRotation) : "nothing";
    return `${who} says ${theirs}; yours says ${yours}`;
  }
  if (!sameNumber(c.setting.micronsPerStep, g.micronsPerClick)) {
    const theirs = c.setting.micronsPerStep != null ? `${oneDp(c.setting.micronsPerStep).replace(/\.0$/, "")} µm a click` : "no µm figure";
    const yours = g.micronsPerClick != null ? `${oneDp(g.micronsPerClick).replace(/\.0$/, "")} µm` : "nothing";
    return `${who} says ${theirs}; yours says ${yours}`;
  }
  if (c.setting.lowerIsFiner !== g.lowerIsFiner) return `${who} says ${c.setting.lowerIsFiner ? "lower is finer" : "higher is finer"}; yours says the opposite`;
  return `${who} reads this dial differently from your settings`;
}

/** The copy under the notice: what accepting does and does not change. */
export const SCALE_NOTICE_FOOTNOTE = "Your logged settings keep their numbers. 75 stays 75 — only how it's read out changes.";

const STYLE_WORD: Readonly<Record<SettingStyle, string>> = { clicks: "clicks", numbers: "numbered dial", stepless: "stepless", microns: "microns" };

/** "clicks · fewer is finer · 100 a turn · 20 µm a click" (A§7.3 scale words). */
export function scaleSummary(g: Pick<GrinderRow, "settingStyle" | "lowerIsFiner" | "clicksPerRotation" | "micronsPerClick" | "minSetting" | "maxSetting" | "step">): string {
  const clicks = g.settingStyle === "clicks";
  const parts: string[] = [STYLE_WORD[g.settingStyle]];
  parts.push(clicks ? (g.lowerIsFiner ? "fewer is finer" : "more is finer") : g.lowerIsFiner ? "lower is finer" : "higher is finer");
  if (clicks && g.clicksPerRotation > 0) parts.push(`${g.clicksPerRotation} a turn`);
  if (g.micronsPerClick != null && g.micronsPerClick > 0) parts.push(`${oneDp(g.micronsPerClick).replace(/\.0$/, "")} µm a ${clicks ? "click" : "step"}`);
  if (!clicks) parts.push(`${groupedNumber(g.minSetting)}–${groupedNumber(g.maxSetting)}${g.step > 0 && g.step !== 1 ? ` in steps of ${g.step}` : ""}`);
  return parts.join(" · ");
}

function groupedNumber(v: number): string {
  return Number.isInteger(v) ? groupedInt(v) : String(Math.round(v * 100) / 100);
}

/** On "Run setup again": the existing row for this catalog entry (or same brand + model), if any. */
export function findExistingGrinder(rows: readonly GrinderRow[], pick: { catalogId: string | null; brand: string; model: string }): GrinderRow | null {
  const norm = (s: string) => normaliseText(s).replace(/ /g, "");
  const key = norm(pick.brand + pick.model);
  return (
    rows.find((g) => !g.isArchived && pick.catalogId != null && g.catalogId === pick.catalogId) ??
    rows.find((g) => !g.isArchived && key !== "" && norm(g.brand + g.model) === key) ??
    null
  );
}

// ---------------------------------------------------------------- beans

/** "1700" or "1700-1900" (A§7.6) → [low, high] texts for the two fields. */
export function splitAltitude(s: string): [string, string] {
  const m = /^\s*(\d+)\s*(?:[-–]\s*(\d+))?\s*$/.exec(s);
  if (!m) return [s.trim(), ""];
  return [m[1] ?? "", m[2] ?? ""];
}

/** Low/high metres → "1700" or "1700-1900"; equal or one-sided values collapse to one number. */
export function joinAltitude(low: string, high: string): string {
  const l = low.replace(/[^\d]/g, "");
  const h = high.replace(/[^\d]/g, "");
  if (l && h && l !== h) return Number(l) <= Number(h) ? `${l}-${h}` : `${h}-${l}`;
  return l || h;
}

/**
 * "Resting · ready in 3 days" while roastedOn + restDays is still ahead (A§7.6), only when the
 * roaster's rest is recorded.
 */
export function restStatus(b: Pick<BeanRow, "roastedOnEpochDay" | "restDays">, todayEpochDay: number): string | null {
  if (b.roastedOnEpochDay == null || b.restDays == null || b.restDays <= 0) return null;
  const left = b.roastedOnEpochDay + b.restDays - todayEpochDay;
  if (left <= 0) return null;
  return `Resting · ready in ${left === 1 ? "1 day" : `${left} days`}`;
}

/** ISO 4217 code from the browser's region, for the price field's default. */
const REGION_CURRENCY: Readonly<Record<string, string>> = {
  GB: "GBP", US: "USD", CA: "CAD", AU: "AUD", NZ: "NZD", IN: "INR", JP: "JPY", KR: "KRW", CN: "CNY", HK: "HKD", TW: "TWD", SG: "SGD",
  CH: "CHF", SE: "SEK", NO: "NOK", DK: "DKK", PL: "PLN", CZ: "CZK", HU: "HUF", BR: "BRL", MX: "MXN", ZA: "ZAR", AE: "AED", IL: "ILS",
  TH: "THB", ID: "IDR", MY: "MYR", PH: "PHP", VN: "VND", TR: "TRY", CO: "COP", CL: "CLP", AR: "ARS", PE: "PEN", KE: "KES", ET: "ETB",
  DE: "EUR", FR: "EUR", ES: "EUR", IT: "EUR", NL: "EUR", BE: "EUR", AT: "EUR", IE: "EUR", PT: "EUR", FI: "EUR", GR: "EUR", LU: "EUR",
  SK: "EUR", SI: "EUR", EE: "EUR", LV: "EUR", LT: "EUR", HR: "EUR", MT: "EUR", CY: "EUR",
};

export function localCurrency(locale: string = typeof navigator === "undefined" ? "en-GB" : navigator.language): string {
  const region = /[-_]([A-Za-z]{2})\b/.exec(locale)?.[1]?.toUpperCase();
  return (region && REGION_CURRENCY[region]) || "";
}
