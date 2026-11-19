/**
 * Coffee backup v2 (A§10.1, shared/schema/backup-v2.md): the wire shapes, exactly as written to
 * and read from the file. Instants are ISO-8601 UTC strings, calendar dates "YYYY-MM-DD". Numeric
 * database ids never appear; records link by uid. Pure types and constants, safe in a worker.
 */
import type { BedShape, Channeling, FlowFeel, Sludge, TasteProfile } from "@/domain/core/evaluation";

export const BACKUP_FORMAT = "coffee-backup";
export const BACKUP_VERSION = 2;
export const LEGACY_SCHEMA = "coffee-brew-log/1";
/** Bigger files would need several times their size in memory on a phone (web-spec §15.2). */
export const MAX_IMPORT_BYTES = 64 * 1024 * 1024;

export type BurrId = "conical" | "flat" | "ghost" | "blade";
export type SettingStyleId = "clicks" | "numbers" | "stepless" | "microns";
export type PhotoKindId = "bed" | "cup" | "bag" | "other";

export interface BackupApp {
  platform: string;
  appVersion: string;
  packageName: string;
  dbVersion: number;
}

export interface BackupOptions {
  includesPhotos: boolean;
  includesAnalyses: boolean;
}

/** All optional on read; a writer writes every key. Local ids travel as uids. */
export interface SettingsDto {
  tempUnit?: "c" | "f";
  weightUnit?: "g" | "oz";
  darkMode?: "system" | "light" | "dark";
  haptics?: boolean;
  soundCues?: boolean;
  keepScreenOn?: boolean;
  autoAdvanceSteps?: boolean;
  name?: string;
  ownedBrewerIds?: string[];
  favouriteRoasterIds?: string[];
  homeArea?: string;
  defaultWaterId?: string;
  defaultBeanUid?: string | null;
  defaultGrinderUid?: string | null;
  lastRecipeId?: string;
  onboardingDone?: boolean;
}

export interface AiProviderDto {
  id: string;
  /** Only for "custom". */
  baseUrl: string | null;
  model: string;
  /** The user's override for a model the catalog does not know. */
  vision: boolean | null;
}

/** Never a key. */
export interface AiDto {
  activeProvider: string | null;
  sendPhotos: boolean;
  providers: AiProviderDto[];
}

export interface BeanDto {
  uid: string;
  name: string;
  roaster: string;
  roasterId: string | null;
  origin: string;
  originId: string | null;
  region: string;
  farm: string;
  producer: string;
  washingStation: string;
  lotTypeId: string | null;
  speciesId: string | null;
  varietalIds: string[];
  varietal: string;
  /** "" = not recorded. */
  processId: string;
  isDecaf: boolean;
  decafMethodId: string | null;
  /** "" = not recorded. */
  roastLevelId: string;
  roastIntentId: string | null;
  /** "1700" or "1700-1900". */
  altitudeM: string;
  harvest: string;
  roastedOn: string | null;
  openedOn: string | null;
  restDays: number | null;
  bagSizeG: number | null;
  priceAmount: number | null;
  currency: string;
  /** 1.x free text. */
  priceText: string;
  tastingNotes: string;
  notes: string;
  isArchived: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface GrinderSettingDto {
  style: SettingStyleId;
  min: number;
  max: number;
  step: number;
  lowerIsFiner: boolean;
  /** null = unknown (Room and Dexie store 0). */
  clicksPerRotation: number | null;
  micronsPerStep: number | null;
  /** "" | "plain" | "turns" | "turns-dot" | "number-tick:10" */
  dialFormat: string;
}

export interface GrinderDto {
  uid: string;
  catalogId: string | null;
  brand: string;
  model: string;
  burr: BurrId | null;
  burrSizeMm: string;
  setting: GrinderSettingDto;
  defaultFilterSetting: number | null;
  defaultEspressoSetting: number | null;
  notes: string;
  isDefault: boolean;
  isArchived: boolean;
  updatedAt: string | null;
}

export interface BedDto {
  shape: BedShape | null;
  surface: Sludge | null;
  channelling: Channeling | null;
  notes: string;
}

export interface BrewDto {
  uid: string;
  startedAt: string;
  updatedAt: string | null;
  recipeId: string;
  recipeName: string;
  recipeAuthor: string;
  brewerId: string | null;
  brewerLabel: string;
  beanUid: string | null;
  beanLabel: string;
  daysOffRoast: number | null;
  grinderUid: string | null;
  grinderLabel: string;
  grindSetting: string;
  grindClicks: number | null;
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
  flow: FlowFeel | null;
  bed: BedDto;
  taste: TasteProfile;
  overall: number;
  descriptors: string[];
  faults: string[];
  notes: string;
  nextTime: string;
  isFavourite: boolean;
  sharedAt: string | null;
}

/** An entry of shared/recipes.json plus updatedAt; validated with decodeRecipe when applied. */
export interface RecipeDto {
  id: string;
  updatedAt: string | null;
  [key: string]: unknown;
}

export interface AnalysisDto {
  uid: string;
  kind: "brew" | "trend";
  brewUid: string | null;
  createdAt: string;
  origin: string;
  promptVersion: number;
  inputHash: string;
  brewCount: number;
  newestBrewAt: string | null;
  providerId: string;
  model: string;
  host: string;
  rawText: string;
  result: unknown;
  flags: unknown[];
  promptTokens: number | null;
  completionTokens: number | null;
  costMicroUsd: number | null;
  latencyMs: number;
  photoCount: number;
}

export interface PhotoDto {
  uid: string;
  brewUid: string | null;
  beanUid: string | null;
  kind: PhotoKindId;
  takenAt: string;
  createdAt: string;
  caption: string;
  sendToAi: boolean;
  widthPx: number;
  heightPx: number;
  mediaType: "image/jpeg";
  /** Base64, no line breaks; null when saved without photos. */
  data: string | null;
}

export interface BackupDoc {
  format: typeof BACKUP_FORMAT;
  version: number;
  exportedAt: string;
  app: BackupApp;
  options: BackupOptions;
  settings: SettingsDto | null;
  ai: AiDto | null;
  beans: BeanDto[];
  grinders: GrinderDto[];
  brews: BrewDto[];
  recipes: RecipeDto[];
  analyses: AnalysisDto[];
  photos: PhotoDto[];
}

/** Key order of each record as written (A§10.1). Tests assert a writer writes every one. */
export const KEYS = {
  top: ["format", "version", "exportedAt", "app", "options", "settings", "ai", "beans", "grinders", "brews", "recipes", "analyses", "photos"],
  settings: [
    "tempUnit", "weightUnit", "darkMode", "haptics", "soundCues", "keepScreenOn", "autoAdvanceSteps", "name", "ownedBrewerIds",
    "favouriteRoasterIds", "homeArea", "defaultWaterId", "defaultBeanUid", "defaultGrinderUid", "lastRecipeId", "onboardingDone",
  ],
  bean: [
    "uid", "name", "roaster", "roasterId", "origin", "originId", "region", "farm", "producer", "washingStation", "lotTypeId", "speciesId",
    "varietalIds", "varietal", "processId", "isDecaf", "decafMethodId", "roastLevelId", "roastIntentId", "altitudeM", "harvest", "roastedOn",
    "openedOn", "restDays", "bagSizeG", "priceAmount", "currency", "priceText", "tastingNotes", "notes", "isArchived", "createdAt", "updatedAt",
  ],
  grinder: [
    "uid", "catalogId", "brand", "model", "burr", "burrSizeMm", "setting", "defaultFilterSetting", "defaultEspressoSetting", "notes",
    "isDefault", "isArchived", "updatedAt",
  ],
  grinderSetting: ["style", "min", "max", "step", "lowerIsFiner", "clicksPerRotation", "micronsPerStep", "dialFormat"],
  brew: [
    "uid", "startedAt", "updatedAt", "recipeId", "recipeName", "recipeAuthor", "brewerId", "brewerLabel", "beanUid", "beanLabel",
    "daysOffRoast", "grinderUid", "grinderLabel", "grindSetting", "grindClicks", "grindValue", "doseG", "waterG", "tempC", "waterId",
    "totalTimeSec", "drawdownSec", "beverageG", "tdsPercent", "extractionYield", "flow", "bed", "taste", "overall", "descriptors", "faults",
    "notes", "nextTime", "isFavourite", "sharedAt",
  ],
  bed: ["shape", "surface", "channelling", "notes"],
  taste: ["acidity", "sweetness", "bitterness", "body", "balance", "aftertaste", "clarity"],
  analysis: [
    "uid", "kind", "brewUid", "createdAt", "origin", "promptVersion", "inputHash", "brewCount", "newestBrewAt", "providerId", "model", "host",
    "rawText", "result", "flags", "promptTokens", "completionTokens", "costMicroUsd", "latencyMs", "photoCount",
  ],
  photo: ["uid", "brewUid", "beanUid", "kind", "takenAt", "createdAt", "caption", "sendToAi", "widthPx", "heightPx", "mediaType", "data"],
  aiProvider: ["id", "baseUrl", "model", "vision"],
} as const;

export interface Counts {
  brews: number;
  beans: number;
  grinders: number;
  recipes: number;
  analyses: number;
  photos: number;
}

export const ZERO_COUNTS: Readonly<Counts> = Object.freeze({ brews: 0, beans: 0, grinders: 0, recipes: 0, analyses: 0, photos: 0 });

export function countsOf(doc: Pick<BackupDoc, "brews" | "beans" | "grinders" | "recipes" | "analyses" | "photos">): Counts {
  return {
    brews: doc.brews.length,
    beans: doc.beans.length,
    grinders: doc.grinders.length,
    recipes: doc.recipes.length,
    analyses: doc.analyses.length,
    photos: doc.photos.length,
  };
}
