/**
 * Catalog DTOs, 1:1 with shared/catalog/*.json and A§2.3 (same field names and defaults:
 * popularity 3, confidence "low", browserCors "unknown"...). Everything is read-only.
 */

export interface SourceRef {
  readonly who: string;
  readonly url: string;
  readonly note: string;
}

export interface GrindRange {
  readonly from: number;
  readonly to: number;
}

export type SettingStyleCatalog = "clicks" | "numbers" | "stepless";

export interface GrinderSetting {
  readonly style: SettingStyleCatalog;
  readonly unit: string;
  readonly min: number;
  readonly max: number;
  readonly step: number;
  readonly lowerIsFiner: boolean;
  readonly clicksPerRotation: number | null;
  readonly micronsPerStep: number | null;
  readonly dialReading: string;
  readonly entryHint: string;
  /** Optional extension (lead decision 1): "plain" | "turns" | "turns-dot" | "number-tick:<n>". */
  readonly dialFormat: string | null;
  /** v4: "s" = the setting is seconds of grinding (a blade grinder); "" otherwise. */
  readonly settingUnit: string;
}

export interface CatalogGrinder {
  readonly id: string;
  readonly brand: string;
  readonly model: string;
  readonly kind: string;
  readonly burr: string;
  readonly burrSizeMm: number | null;
  readonly popularity: number;
  readonly setting: GrinderSetting;
  /** key = BrewMethod id */
  readonly ranges: Readonly<Record<string, GrindRange>>;
  readonly rangeSources: readonly SourceRef[];
  readonly confidence: "high" | "medium" | "low";
  readonly notes: string;
  /** "<brand> <model>" */
  readonly display: string;
}

export interface CatalogRoaster {
  readonly id: string;
  readonly name: string;
  readonly country: string;
  readonly city: string;
  readonly area: string;
  readonly notable: string;
  /** v4: a supermarket brand (Folgers, Lavazza, Bru…), sorted after the user's region and favourites. */
  readonly mainstream: boolean;
  /** v4: `area` values where the brand is sold; [] for specialty roasters. */
  readonly regions: readonly string[];
}

export interface CatalogOrigin {
  readonly id: string;
  readonly country: string;
  readonly iso2: string;
  readonly area: string;
  readonly growingRegions: readonly string[];
  readonly commonProcesses: readonly string[];
  readonly commonVarietals: readonly string[];
}

export interface Term {
  readonly id: string;
  readonly label: string;
}

export interface DescribedTerm extends Term {
  readonly description: string;
}

export interface ProcessTerm extends DescribedTerm {
  readonly group: string;
}

export interface VarietalTerm extends Term {
  readonly aliases: readonly string[];
  readonly species: string;
}

export interface BeanVocabulary {
  readonly species: readonly Term[];
  readonly processes: readonly ProcessTerm[];
  readonly varietals: readonly VarietalTerm[];
  readonly lotTypes: readonly DescribedTerm[];
  readonly decafMethods: readonly Term[];
  readonly roastLevels: readonly DescribedTerm[];
  readonly roastIntents: readonly Term[];
}

export interface CatalogWater {
  readonly id: string;
  readonly label: string;
  readonly kind: string;
  readonly description: string;
  readonly ghPpm: number | null;
  readonly khPpm: number | null;
}

export interface ListModels {
  readonly path: string;
  readonly works: boolean;
  readonly note: string;
}

export interface CatalogModel {
  readonly id: string;
  readonly label: string;
  readonly inputPerM: number | null;
  readonly outputPerM: number | null;
  readonly vision: boolean;
  readonly default: boolean;
  readonly why: string;
}

export type BrowserCors = "yes" | "no" | "unknown";

export interface CatalogProvider {
  readonly id: string;
  readonly name: string;
  readonly baseUrl: string;
  /** "openai-chat" | "anthropic-messages" today; kept open so a new wire format is not a parse error. */
  readonly api: string;
  /** "bearer" | "x-api-key" today. */
  readonly auth: string;
  readonly extraHeaders: Readonly<Record<string, string>>;
  readonly browserHeaders: Readonly<Record<string, string>>;
  readonly keyUrl: string;
  readonly keyHint: string;
  readonly browserCors: BrowserCors;
  readonly corsEvidence: string;
  readonly listModels: ListModels;
  readonly models: readonly CatalogModel[];
  readonly pricesCheckedOn: string;
  readonly notes: string;
}

/** A§8.9; WP-3 writes the file. */
export interface Principle {
  readonly id: string;
  readonly topic: string;
  /** BrewMethod ids; empty = all methods. */
  readonly appliesTo: readonly string[];
  readonly text: string;
  readonly who: string;
  readonly url: string;
  readonly note: string;
}

export type TermKind = "species" | "process" | "varietal" | "lotType" | "decaf" | "roastLevel" | "roastIntent";

export type CatalogName = "grinders" | "roasters" | "origins" | "beans" | "brewers" | "water" | "providers" | "recipes" | "principles" | "glossary";

export const CATALOG_NAMES: readonly CatalogName[] = [
  "brewers", "recipes", "beans", "grinders", "roasters", "origins", "water", "providers", "principles", "glossary",
];

export type { GlossarySource, GlossaryTerm } from "@/domain/core/glossary";
