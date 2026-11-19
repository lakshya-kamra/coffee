/**
 * Port of domain/Evaluation.kt. Enumerations use the backup v2 id strings (A§10.1) instead of
 * Kotlin enum names; null means "not recorded" (Kotlin's Unset).
 */

export const NOT_RECORDED = "Not recorded";

export type BedShape = "flat" | "domed" | "crater" | "high-and-dry" | "sloped" | "sinkhole" | "muddy";
export type Sludge = "none" | "light" | "heavy";
export type Channeling = "none" | "suspected" | "obvious";
export type FlowFeel = "stalled" | "slow" | "on-target" | "fast" | "gusher";

export interface BedShapeInfo {
  readonly id: BedShape;
  readonly label: string;
  readonly glyph: string;
  readonly reading: string;
}

/** What the spent bed looked like when you pulled the brewer apart. */
export const BED_SHAPES: readonly BedShapeInfo[] = [
  { id: "flat", label: "Flat and even", glyph: "▬", reading: "The good one. Water moved through the whole bed at the same speed." },
  { id: "domed", label: "Domed in the middle", glyph: "◠", reading: "Gentle pouring, low agitation. Usually harmless, sometimes a touch under-extracted." },
  { id: "crater", label: "Cratered / dug out", glyph: "◡", reading: "Your pour drilled into one spot. Water took a shortcut there." },
  { id: "high-and-dry", label: "High and dry ring", glyph: "◌", reading: "Grounds stranded up the paper never brewed. Swirl or stir earlier." },
  { id: "sloped", label: "Sloped to one side", glyph: "◤", reading: "Uneven pour or an unlevel brewer. Half the bed got more water." },
  { id: "sinkhole", label: "Sinkholes / pits", glyph: "⁙", reading: "Channelling. Water punched through instead of soaking through." },
  { id: "muddy", label: "Muddy and compacted", glyph: "▓", reading: "Fines migrated down and choked the bed. Expect a slow, harsh cup." },
];

/** Silt sitting on top of the bed at the end. */
export const SLUDGE: readonly { readonly id: Sludge; readonly label: string }[] = [
  { id: "none", label: "Clean" },
  { id: "light", label: "A little silt" },
  { id: "heavy", label: "Thick muddy cap" },
];

/** Evidence of water taking a shortcut. */
export const CHANNELING: readonly { readonly id: Channeling; readonly label: string }[] = [
  { id: "none", label: "None visible" },
  { id: "suspected", label: "Maybe" },
  { id: "obvious", label: "Obvious holes" },
];

/** How the drawdown felt against the recipe target. */
export const FLOW_FEEL: readonly { readonly id: FlowFeel; readonly label: string }[] = [
  { id: "stalled", label: "Stalled / never finished" },
  { id: "slow", label: "Slower than target" },
  { id: "on-target", label: "On target" },
  { id: "fast", label: "Faster than target" },
  { id: "gusher", label: "Fell straight through" },
];

function labelIn(list: readonly { id: string; label: string }[], id: string | null | undefined): string {
  return list.find((e) => e.id === id)?.label ?? NOT_RECORDED;
}

export const bedShapeLabel = (id: BedShape | null | undefined) => labelIn(BED_SHAPES, id);
export const sludgeLabel = (id: Sludge | null | undefined) => labelIn(SLUDGE, id);
export const channelingLabel = (id: Channeling | null | undefined) => labelIn(CHANNELING, id);
export const flowFeelLabel = (id: FlowFeel | null | undefined) => labelIn(FLOW_FEEL, id);

export function bedShapeInfo(id: BedShape | null | undefined): BedShapeInfo | null {
  return BED_SHAPES.find((b) => b.id === id) ?? null;
}

/** 0..10 taste axes. 5 is neutral for balance-style axes, more-is-more for the rest. */
export interface TasteProfile {
  readonly acidity: number;
  readonly sweetness: number;
  readonly bitterness: number;
  readonly body: number;
  readonly balance: number;
  readonly aftertaste: number;
  readonly clarity: number;
}

export type TasteAxis = keyof TasteProfile;

export const DEFAULT_TASTE: TasteProfile = Object.freeze({
  acidity: 5,
  sweetness: 5,
  bitterness: 5,
  body: 5,
  balance: 5,
  aftertaste: 5,
  clarity: 5,
});

export const TASTE_AXES: readonly { readonly key: TasteAxis; readonly label: string; readonly range: string }[] = [
  { key: "acidity", label: "Acidity", range: "flat → electric" },
  { key: "sweetness", label: "Sweetness", range: "dry → syrupy" },
  { key: "bitterness", label: "Bitterness", range: "none → harsh" },
  { key: "body", label: "Body", range: "watery → heavy" },
  { key: "balance", label: "Balance", range: "disjointed → seamless" },
  { key: "aftertaste", label: "Aftertaste", range: "vanishes → lingers" },
  { key: "clarity", label: "Clarity", range: "muddled → crystalline" },
];

export function withTaste(t: TasteProfile, axis: TasteAxis, value: number): TasteProfile {
  return { ...t, [axis]: value };
}

export const Descriptors = {
  POSITIVE: [
    "Floral", "Citrus", "Stone fruit", "Berry", "Tropical", "Chocolate", "Caramel",
    "Nutty", "Tea-like", "Winey", "Honey", "Spice", "Malt", "Creamy", "Juicy",
  ],
  FAULTS: [
    "Sour", "Bitter", "Astringent", "Drying", "Hollow", "Thin", "Muddy",
    "Papery", "Ashy", "Salty", "Vegetal", "Boozy / ferment-heavy",
  ],
} as const;

/** Everything the app can compute from a finished brew without asking anyone. */
export const BrewMath = {
  /** Extraction yield %. Needs beverage weight on the scale and a refractometer TDS reading. */
  extractionYield(doseG: number, beverageG: number | null, tdsPercent: number | null): number | null {
    if (beverageG == null || tdsPercent == null || doseG <= 0) return null;
    if (beverageG <= 0 || tdsPercent <= 0) return null;
    return (beverageG * tdsPercent) / doseG;
  },
  /** Water absorbed by the grounds, roughly 2 g per gram of coffee. */
  expectedRetentionG(doseG: number): number {
    return doseG * 2;
  },
  expectedBeverageG(doseG: number, waterG: number): number {
    return Math.max(0, waterG - BrewMath.expectedRetentionG(doseG));
  },
  daysOffRoast(roastEpochDay: number | null, nowEpochDay: number): number | null {
    return roastEpochDay == null ? null : nowEpochDay - roastEpochDay;
  },
  round1(v: number): number {
    return Math.round(v * 10) / 10;
  },
};
