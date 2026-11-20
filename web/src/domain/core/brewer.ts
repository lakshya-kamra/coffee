/**
 * Port of A§2.5: Brewer is a catalog-backed value (brewers.json), not a fixed enum. Pure: the
 * lookup list is passed in; src/catalog/catalog.ts binds it to the loaded catalog as `Brewers`.
 */
import { LegacyIds } from "./legacyIds";

export const BREW_METHODS = [
  { id: "espresso", label: "Espresso" },
  { id: "moka", label: "Moka pot" },
  { id: "aeropress", label: "AeroPress" },
  { id: "pourover", label: "Pour-over" },
  { id: "frenchPress", label: "French press" },
  { id: "coldBrew", label: "Cold brew" },
  { id: "siphon", label: "Siphon" },
  { id: "turkish", label: "Turkish / cezve" },
] as const;

export type BrewMethod = (typeof BREW_METHODS)[number]["id"];

export const BREW_METHOD_IDS: readonly BrewMethod[] = BREW_METHODS.map((m) => m.id);

export function isBrewMethod(id: unknown): id is BrewMethod {
  return typeof id === "string" && (BREW_METHOD_IDS as readonly string[]).includes(id);
}

/** BrewMethod.of: unknown ids fall back to pour-over, as on Android. */
export function brewMethodOf(id: string | null | undefined): BrewMethod {
  return isBrewMethod(id) ? id : "pourover";
}

export function brewMethodLabel(m: BrewMethod): string {
  return BREW_METHODS.find((x) => x.id === m)?.label ?? m;
}

/** Runs water through a bed at a filter ratio. Same set the 1.x data-quality rules used. */
export function isFilterStyle(m: BrewMethod): boolean {
  return m !== "espresso" && m !== "moka" && m !== "coldBrew" && m !== "turkish";
}

/**
 * The drawings BrewerIcon knows. The first nine names match the 1.x enum; Drip, Cezve, Siphon and
 * Phin (v4) start as placeholders that reuse the nearest drawing until the screen packages draw them.
 */
export type BrewerArt =
  | "V60" | "Chemex" | "Kalita" | "Switch" | "AeroPress" | "FrenchPress" | "Moka" | "Espresso" | "ColdBrew"
  | "Drip" | "Cezve" | "Siphon" | "Phin";

export interface Brewer {
  readonly id: string;
  readonly label: string;
  readonly brand: string;
  readonly category: string;
  readonly method: BrewMethod;
  readonly filter: string;
  readonly notes: string;
}

export function makeBrewer(p: Partial<Brewer> & { id: string; label: string }): Brewer {
  return Object.freeze({
    id: p.id,
    label: p.label,
    brand: p.brand ?? "",
    category: p.category ?? "other",
    method: p.method ?? "pourover",
    filter: p.filter ?? "paper",
    notes: p.notes ?? "",
  });
}

/** BrewerArts.of: A§2.5 precedence, plus the v4 arts for drip machines, cezves, siphons and phins. */
export function brewerArt(b: Brewer): BrewerArt {
  if (b.id === "chemex") return "Chemex";
  if (b.id === "drip-machine") return "Drip";
  // Both are stacked metal gravity filters; the catalog files the phin under the AeroPress method.
  if (b.id === "phin" || b.id === "south-indian-filter") return "Phin";
  switch (b.method) {
    case "moka":
      return "Moka";
    case "turkish":
      return "Cezve";
    case "espresso":
      return "Espresso";
    case "coldBrew":
      return "ColdBrew";
    case "aeropress":
      return "AeroPress";
    case "frenchPress":
      return "FrenchPress";
    case "siphon":
      return "Siphon";
    case "pourover":
      if (b.category === "flat-bottom") return "Kalita";
      if (b.category === "hybrid" || b.category === "immersion") return "Switch";
      return "V60";
  }
}

/** 1.x short names for the legacy brewers, so old log rows read exactly as before. */
const LEGACY_SHORT: Readonly<Record<string, string>> = {
  "hario-v60-02": "V60",
  chemex: "Chemex",
  "kalita-wave-185": "Kalita",
  "hario-switch": "Switch",
  aeropress: "AeroPress",
  "french-press": "French press",
  "moka-pot": "Moka",
  "espresso-machine": "Espresso",
  "cold-brew-jar": "Cold brew",
};

/** "V60", "Kalita", "French press"…: legacy short names, else the label minus a leading brand, ≤ 14 chars. */
export function brewerShortName(b: Brewer): string {
  const legacy = LEGACY_SHORT[b.id];
  if (legacy) return legacy;
  let s = b.label.trim();
  if (b.brand && s.toLowerCase().startsWith(b.brand.toLowerCase() + " ")) s = s.slice(b.brand.length + 1).trim();
  return s.length <= 14 ? s : s.slice(0, 13).trimEnd() + "…";
}

/** The narrowest honest tag for a log row's 68 px column. */
export function brewerRowTag(b: Brewer): string {
  return brewerArt(b) === "FrenchPress" ? "Press" : brewerShortName(b);
}

/**
 * Brewers with their own family (v4 §4.13): they never borrow a V60 or AeroPress recipe. The
 * Melodrip (an accessory) and "other" get families that match nothing, not even each other.
 */
const OWN_FAMILY: Readonly<Record<string, string>> = {
  "drip-machine": "drip-machine",
  "drip-bag": "drip-bag",
  phin: "phin",
  "south-indian-filter": "south-indian-filter",
  melodrip: "accessory",
  other: "other",
};

/** Families that never match a recipe: ownedFamilies leaves them out. */
export const NO_MATCH_FAMILIES: ReadonlySet<string> = new Set(["accessory", "other"]);

/** Brewers that take the same recipes: art for pour-over (V60-01/02/03 are one family), method otherwise. */
export function brewerFamily(b: Brewer): string {
  return OWN_FAMILY[b.id] ?? (b.method === "pourover" ? `pourover:${brewerArt(b)}` : b.method);
}

/** The families of the brewers someone owns, without the ones that match nothing. */
export function ownedFamilies(ownedBrewerIds: readonly string[], byId: (id: string) => Brewer | null): Set<string> {
  const out = new Set<string>();
  for (const id of ownedBrewerIds) {
    const b = byId(id);
    if (!b) continue;
    const f = brewerFamily(b);
    if (!NO_MATCH_FAMILIES.has(f)) out.add(f);
  }
  return out;
}

/** How the water meets the coffee: it decides which advice and which review sections apply. */
export type BrewStyle = "percolation" | "immersion" | "espresso";

/** An unknown brewer is treated as percolation (a filter brew), as data quality does. */
export function brewStyle(b: Brewer | null): BrewStyle {
  if (b == null) return "percolation";
  if (b.id === "phin" || b.id === "south-indian-filter") return "percolation";
  switch (b.method) {
    case "espresso":
      return "espresso";
    case "frenchPress":
    case "aeropress":
    case "coldBrew":
    case "turkish":
    case "siphon":
      return "immersion";
    case "pourover":
      return b.category === "immersion" ? "immersion" : "percolation";
    case "moka":
      return "percolation";
  }
}

/** Which Review sections a brew gets (v4 §4.4). TDS and extraction always sit under "Advanced". */
export interface ReviewSections {
  /** Drawdown time, In cup and Flow. */
  readonly drawdown: boolean;
  /** Shape, surface, channelling. */
  readonly bed: boolean;
  /** "Retained by the bed". */
  readonly retained: boolean;
  readonly advanced: true;
  readonly style: BrewStyle;
}

const DRAWDOWN_CATEGORIES: ReadonlySet<string> = new Set(["cone", "flat-bottom", "hybrid", "immersion"]);

export function reviewSections(b: Brewer | null): ReviewSections {
  const style = brewStyle(b);
  // An unknown brewer keeps every section, as before v4.
  const pourOver = b == null || (b.method === "pourover" && DRAWDOWN_CATEGORIES.has(b.category) && OWN_FAMILY[b.id] == null);
  return {
    drawdown: pourOver,
    bed: pourOver || b?.id === "drip-machine",
    retained: style === "percolation" && b?.method !== "moka",
    advanced: true,
    style,
  };
}

/** Field labels that change by method (v4 §4.9). */
export interface MethodLabels {
  /** "WATER", "YIELD" or "WATER IN THE BASE". */
  readonly water: string;
  /** A short helper under the water label: "in the cup" for espresso, else "". */
  readonly waterNote: string;
  /** "TIME" or "SHOT TIME". */
  readonly time: string;
  /** Cold brew: durations read in hours ("12 h 5 min"), see formatDuration. */
  readonly longTimes: boolean;
}

export function methodLabels(method: BrewMethod): MethodLabels {
  switch (method) {
    case "espresso":
      return { water: "YIELD", waterNote: "in the cup", time: "SHOT TIME", longTimes: false };
    case "moka":
      return { water: "WATER IN THE BASE", waterNote: "", time: "TIME", longTimes: false };
    case "coldBrew":
      return { water: "WATER", waterNote: "", time: "TIME", longTimes: true };
    default:
      return { water: "WATER", waterNote: "", time: "TIME", longTimes: false };
  }
}

export function unknownBrewer(raw: string): Brewer {
  return makeBrewer({ id: "other", label: raw.trim() || "Other" });
}

/** id → legacy enum name/label → case-insensitive label → null. Never throws. */
export function resolveBrewerIn(list: readonly Brewer[], raw: string | null | undefined): Brewer | null {
  if (raw == null) return null;
  const s = raw.trim();
  if (!s) return null;
  const byId = list.find((b) => b.id === s);
  if (byId) return byId;
  const legacyId = LegacyIds.BREWER[s];
  if (legacyId) {
    const legacy = list.find((b) => b.id === legacyId) ?? LegacyIds.FALLBACK_BREWERS.find((b) => b.id === legacyId);
    if (legacy) return legacy;
  }
  const lower = s.toLowerCase();
  return list.find((b) => b.label.toLowerCase() === lower) ?? null;
}
