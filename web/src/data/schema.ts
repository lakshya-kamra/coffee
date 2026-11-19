/**
 * IndexedDB row types mirroring Room v4 (A§4.2), so domain code ports field for field. Two
 * deliberate differences (web-spec §8.1): enum columns hold the backup v2 id strings, and
 * `taste` is a nested object instead of taste_* columns. Every field is present on every row.
 */
import type { BedShape, Channeling, FlowFeel, Sludge, TasteProfile } from "@/domain/core/evaluation";
import { DEFAULT_TASTE } from "@/domain/core/evaluation";
import type { RecipeJson } from "@/domain/core/recipe";

export type BurrType = "conical" | "flat" | "ghost" | "blade";
export type SettingStyle = "clicks" | "numbers" | "stepless" | "microns";
export type PhotoKind = "bed" | "cup" | "bag" | "other";

export interface BeanRow {
  id?: number;
  uid: string;
  name: string;
  /** Display or custom roaster name; roasterId links the catalog entry. */
  roaster: string;
  roasterId: string | null;
  /** Display or custom country; originId links the catalog entry. */
  origin: string;
  originId: string | null;
  /** Growing region. */
  region: string;
  farm: string;
  producer: string;
  washingStation: string;
  lotTypeId: string | null;
  speciesId: string | null;
  varietalIds: string[];
  /** Free text for custom varietal names. */
  varietal: string;
  /** "" = not recorded. */
  processId: string;
  isDecaf: boolean;
  decafMethodId: string | null;
  /** "" = not recorded. */
  roastLevelId: string;
  roastIntentId: string | null;
  roastedOnEpochDay: number | null;
  openedOnEpochDay: number | null;
  bagSizeG: number | null;
  /** 1.x free text. */
  price: string;
  priceAmount: number | null;
  /** ISO 4217, e.g. "GBP". */
  currency: string;
  /** "1700" or "1700-1900" metres. */
  altitudeMasl: string;
  harvest: string;
  restDays: number | null;
  tastingNotes: string;
  notes: string;
  isArchived: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface GrinderRow {
  id?: number;
  uid: string;
  brand: string;
  model: string;
  burrType: BurrType | null;
  /** As typed, e.g. "48". */
  burrSizeMm: string;
  settingStyle: SettingStyle;
  /** 0 = unknown. */
  clicksPerRotation: number;
  micronsPerClick: number | null;
  lowerIsFiner: boolean;
  minSetting: number;
  maxSetting: number;
  step: number;
  /** Stored units: total clicks on click grinders, the dial number otherwise. */
  defaultFilterSetting: number | null;
  defaultEspressoSetting: number | null;
  notes: string;
  isDefault: boolean;
  isArchived: boolean;
  catalogId: string | null;
  /** "" = auto (A§3.2). */
  dialFormat: string;
  updatedAt: number;
}

export interface BrewRow {
  id?: number;
  uid: string;
  startedAt: number;
  updatedAt: number;
  recipeId: string;
  recipeName: string;
  recipeAuthor: string;
  /** Label snapshot, as Room's `brewer`. */
  brewer: string;
  brewerId: string | null;
  beanId: number | null;
  beanLabel: string;
  daysOffRoast: number | null;
  grinderId: number | null;
  grinderLabel: string;
  /** Free text; for click grinders a snapshot like "75 clicks". */
  grindSetting: string;
  /** Total clicks from zero on click grinders. */
  grindClicks: number | null;
  /** The printed number on numbered / stepless dials. */
  grindValue: number | null;
  doseG: number;
  waterG: number;
  /** Always °C. */
  tempC: number;
  waterId: string | null;
  totalTimeSec: number;
  drawdownSec: number | null;
  beverageG: number | null;
  tdsPercent: number | null;
  extractionYield: number | null;
  flowFeel: FlowFeel | null;
  bedShape: BedShape | null;
  sludge: Sludge | null;
  channeling: Channeling | null;
  bedNotes: string;
  taste: TasteProfile;
  /** 0 = not rated. */
  overall: number;
  descriptors: string[];
  faults: string[];
  notes: string;
  nextTime: string;
  isFavourite: boolean;
  sharedAt: number | null;
}

export interface SavedRecipeRow {
  id: string;
  /** isBuiltIn is always false here, as Android's saveRecipe. */
  recipe: RecipeJson;
  updatedAt: number;
}

export interface AnalysisRow {
  id?: number;
  uid: string;
  kind: "brew" | "trend";
  brewLogId: number | null;
  createdAt: number;
  promptVersion: number;
  inputHash: string;
  brewCount: number;
  newestBrewAt: number;
  model: string;
  host: string;
  rawText: string;
  /** Parsed result object, or null when parsing failed. */
  result: unknown;
  /** DataFlag[] (WEB-3 owns the type). */
  flags: unknown[];
  promptTokens: number | null;
  completionTokens: number | null;
  costMicroUsd: number | null;
  latencyMs: number;
  providerId: string;
  photoCount: number;
  /** "web" for rows made here; "android" for imported ones. */
  origin: string;
}

export interface PhotoRow {
  id?: number;
  uid: string;
  brewLogId: number | null;
  beanId: number | null;
  kind: PhotoKind;
  mediaType: "image/jpeg";
  widthPx: number;
  heightPx: number;
  bytes: number;
  takenAt: number;
  createdAt: number;
  caption: string;
  sendToAi: boolean;
}

/** Web only: the image bytes, kept apart from the rows so lists never load Blobs. */
export interface PhotoBlobRow {
  uid: string;
  full: Blob;
  thumb: Blob;
}

export interface KvRow {
  key: string;
  value: unknown;
}

export function newUid(): string {
  return crypto.randomUUID();
}

// ---------------------------------------------------------------- defaults (Room column defaults)

export function newBean(p: Partial<BeanRow> & { name: string }, now = Date.now()): BeanRow {
  return {
    uid: newUid(), roaster: "", roasterId: null, origin: "", originId: null, region: "", farm: "", producer: "",
    washingStation: "", lotTypeId: null, speciesId: null, varietalIds: [], varietal: "", processId: "", isDecaf: false,
    decafMethodId: null, roastLevelId: "", roastIntentId: null, roastedOnEpochDay: null, openedOnEpochDay: null,
    bagSizeG: null, price: "", priceAmount: null, currency: "", altitudeMasl: "", harvest: "", restDays: null,
    tastingNotes: "", notes: "", isArchived: false, createdAt: now, updatedAt: now,
    ...p,
  };
}

export function newGrinder(p: Partial<GrinderRow> & { brand: string; model: string }, now = Date.now()): GrinderRow {
  return {
    uid: newUid(), burrType: null, burrSizeMm: "", settingStyle: "clicks", clicksPerRotation: 0, micronsPerClick: null,
    lowerIsFiner: true, minSetting: 0, maxSetting: 40, step: 1, defaultFilterSetting: null, defaultEspressoSetting: null,
    notes: "", isDefault: false, isArchived: false, catalogId: null, dialFormat: "", updatedAt: now,
    ...p,
  };
}

export function newBrew(p: Partial<BrewRow> = {}, now = Date.now()): BrewRow {
  return {
    uid: newUid(), startedAt: now, updatedAt: now, recipeId: "", recipeName: "", recipeAuthor: "", brewer: "", brewerId: null,
    beanId: null, beanLabel: "", daysOffRoast: null, grinderId: null, grinderLabel: "", grindSetting: "", grindClicks: null,
    grindValue: null, doseG: 0, waterG: 0, tempC: 0, waterId: null, totalTimeSec: 0, drawdownSec: null, beverageG: null,
    tdsPercent: null, extractionYield: null, flowFeel: null, bedShape: null, sludge: null, channeling: null, bedNotes: "",
    taste: { ...DEFAULT_TASTE }, overall: 0, descriptors: [], faults: [], notes: "", nextTime: "", isFavourite: false,
    sharedAt: null,
    ...p,
  };
}

/** "Square Mile — Red Brick", or the name alone (BeanEntity.display). */
export function beanDisplay(b: Pick<BeanRow, "roaster" | "name">): string {
  return b.roaster.trim() ? `${b.roaster} — ${b.name}` : b.name;
}

/** "1Zpresso K-Ultra" (GrinderEntity.display). */
export function grinderDisplay(g: Pick<GrinderRow, "brand" | "model">): string {
  return [g.brand, g.model].filter((s) => s.trim() !== "").join(" ");
}
