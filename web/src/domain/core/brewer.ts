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

/** The nine drawings BrewerIcon knows. Names match the 1.x enum. */
export type BrewerArt = "V60" | "Chemex" | "Kalita" | "Switch" | "AeroPress" | "FrenchPress" | "Moka" | "Espresso" | "ColdBrew";

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

/** BrewerArts.of, exact precedence from A§2.5. */
export function brewerArt(b: Brewer): BrewerArt {
  if (b.id === "chemex") return "Chemex";
  switch (b.method) {
    case "moka":
    case "turkish":
      return "Moka";
    case "espresso":
      return "Espresso";
    case "coldBrew":
      return "ColdBrew";
    case "aeropress":
      return "AeroPress";
    case "frenchPress":
      return "FrenchPress";
    case "siphon":
      return "Chemex";
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

/** Brewers that take the same recipes: art for pour-over (V60-01/02/03 are one family), method otherwise. */
export function brewerFamily(b: Brewer): string {
  return b.method === "pourover" ? `pourover:${brewerArt(b)}` : b.method;
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
