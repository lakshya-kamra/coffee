/**
 * Backup v2 codec (web-spec §15.2): detect what a file is, decode it leniently (every field
 * defaulted, unknown keys ignored, bad records skipped with a reason), and encode. Pure; runs in
 * the backup worker and in tests.
 */
import { DEFAULT_TASTE, type TasteProfile } from "@/domain/core/evaluation";
import { LegacyLabels, legacyLabel } from "@/domain/core/legacyIds";
import { sanitizeNonStandardNumbers } from "./sanitize";
import {
  BACKUP_FORMAT,
  BACKUP_VERSION,
  LEGACY_SCHEMA,
  type AiDto,
  type AnalysisDto,
  type BackupDoc,
  type BeanDto,
  type BrewDto,
  type BurrId,
  type GrinderDto,
  type PhotoDto,
  type PhotoKindId,
  type RecipeDto,
  type SettingStyleId,
  type SettingsDto,
} from "./model";

export type BackupKind = "v2" | "legacy1" | "unknown" | "tooNew";

// ---------------------------------------------------------------- lenient field readers

type Obj = Record<string, unknown>;

export function isObj(v: unknown): v is Obj {
  return v != null && typeof v === "object" && !Array.isArray(v);
}

export function str(v: unknown, d = ""): string {
  return typeof v === "string" ? v : d;
}

export function strOrNull(v: unknown): string | null {
  return typeof v === "string" && v !== "" ? v : null;
}

/** A finite number, or a string holding one (Kotlin's lenient reader takes both). */
export function numOrNull(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

export function num(v: unknown, d = 0): number {
  return numOrNull(v) ?? d;
}

export function intOrNull(v: unknown): number | null {
  const n = numOrNull(v);
  return n == null ? null : Math.trunc(n);
}

export function int(v: unknown, d = 0): number {
  return intOrNull(v) ?? d;
}

export function bool(v: unknown, d = false): boolean {
  return typeof v === "boolean" ? v : d;
}

export function boolOrNull(v: unknown): boolean | null {
  return typeof v === "boolean" ? v : null;
}

export function strList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
}

function oneOf<T extends string>(v: unknown, allowed: readonly T[]): T | null {
  return typeof v === "string" && (allowed as readonly string[]).includes(v) ? (v as T) : null;
}

const UID_MAX = 64;

export function validUid(v: unknown): string | null {
  return typeof v === "string" && v.length >= 1 && v.length <= UID_MAX ? v : null;
}

// ---------------------------------------------------------------- instants and dates

const INSTANT = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?(Z|[+-]\d{2}:?\d{2})?$/i;

/**
 * Epoch ms from an ISO-8601 instant, with or without milliseconds (Java writes up to nine
 * fraction digits: 1.x wrote "…07.123456Z"). A number is taken as epoch ms. No zone means UTC.
 */
export function parseInstant(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? Math.trunc(v) : null;
  if (typeof v !== "string") return null;
  const m = INSTANT.exec(v.trim());
  if (!m) return null;
  const frac = (m[7] ?? "").padEnd(3, "0").slice(0, 3);
  let ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6] ?? 0), Number(frac));
  const zone = m[8];
  if (zone && zone.toUpperCase() !== "Z") {
    const sign = zone.startsWith("-") ? -1 : 1;
    const digits = zone.slice(1).replace(":", "");
    ms -= sign * (Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2, 4))) * 60_000;
  }
  return Number.isNaN(ms) ? null : ms;
}

export function formatInstant(ms: number): string {
  return new Date(ms).toISOString();
}

/** Canonical instant string, or null when unreadable. */
export function instantOrNull(v: unknown): string | null {
  const ms = parseInstant(v);
  return ms == null ? null : formatInstant(ms);
}

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

export function dateOrNull(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const t = v.trim().slice(0, 10);
  return DATE.test(t) ? t : null;
}

// ---------------------------------------------------------------- detect

export function detectValue(v: unknown): BackupKind {
  if (!isObj(v)) return "unknown";
  if (v.format === BACKUP_FORMAT) {
    const version = v.version;
    if (typeof version !== "number" || !Number.isInteger(version)) return "unknown";
    if (version > BACKUP_VERSION) return "tooNew";
    return version === BACKUP_VERSION ? "v2" : "unknown";
  }
  if (typeof v.schema === "string") {
    if (v.schema === LEGACY_SCHEMA) return "legacy1";
    const m = /^coffee-brew-log\/(\d+)$/.exec(v.schema);
    if (m && Number(m[1]) > 1) return "tooNew";
  }
  return "unknown";
}

export type ParseResult = { ok: true; value: unknown } | { ok: false; error: "notJson" };

/** Sanitise (NaN, Infinity) then parse. */
export function parseBackupText(text: string): ParseResult {
  // A UTF-8 BOM is legal in a file but not in JSON.parse.
  const body = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  try {
    return { ok: true, value: JSON.parse(sanitizeNonStandardNumbers(body)) as unknown };
  } catch {
    return { ok: false, error: "notJson" };
  }
}

export function detect(text: string): BackupKind {
  const p = parseBackupText(text);
  return p.ok ? detectValue(p.value) : "unknown";
}

// ---------------------------------------------------------------- decode v2

const BURRS: readonly BurrId[] = ["conical", "flat", "ghost", "blade"];
const STYLES: readonly SettingStyleId[] = ["clicks", "numbers", "stepless", "microns"];
const PHOTO_KINDS: readonly PhotoKindId[] = ["bed", "cup", "bag", "other"];
// Added in 2.1. An unknown value reads as "" (not recorded), never as an error.
const BEAN_FORM_IDS = ["whole", "ground"] as const;
const GROUND_FOR_IDS = ["filter", "press", "espresso", "moka"] as const;
const SETTING_UNIT_IDS = ["s"] as const;
const GRIND_KIND_IDS = ["dial", "blade", "preground"] as const;

/** v2 ids, and leniently the 1.x labels / Kotlin enum names for the same thing. */
function enumId<T extends string>(v: unknown, table: Readonly<Record<string, T | "">>): T | null {
  if (typeof v !== "string" || v === "") return null;
  const values = Object.values(table) as string[];
  if (values.includes(v)) return v as T;
  return legacyLabel(table, v);
}

function taste(v: unknown): TasteProfile {
  const o = isObj(v) ? v : {};
  const axis = (k: keyof TasteProfile) => Math.min(10, Math.max(0, int(o[k], DEFAULT_TASTE[k])));
  return {
    acidity: axis("acidity"),
    sweetness: axis("sweetness"),
    bitterness: axis("bitterness"),
    body: axis("body"),
    balance: axis("balance"),
    aftertaste: axis("aftertaste"),
    clarity: axis("clarity"),
  };
}

export function decodeBean(o: Obj): BeanDto | string {
  const uid = validUid(o.uid);
  if (!uid) return "no uid";
  const name = str(o.name).trim();
  if (!name) return "no name";
  return {
    uid,
    name: str(o.name),
    roaster: str(o.roaster),
    roasterId: strOrNull(o.roasterId),
    origin: str(o.origin),
    originId: strOrNull(o.originId),
    region: str(o.region),
    farm: str(o.farm),
    producer: str(o.producer),
    washingStation: str(o.washingStation),
    lotTypeId: strOrNull(o.lotTypeId),
    speciesId: strOrNull(o.speciesId),
    varietalIds: strList(o.varietalIds),
    varietal: str(o.varietal),
    processId: str(o.processId),
    isDecaf: bool(o.isDecaf),
    decafMethodId: strOrNull(o.decafMethodId),
    roastLevelId: str(o.roastLevelId),
    roastIntentId: strOrNull(o.roastIntentId),
    form: oneOf(o.form, BEAN_FORM_IDS) ?? "",
    groundFor: oneOf(o.groundFor, GROUND_FOR_IDS) ?? "",
    altitudeM: typeof o.altitudeM === "number" ? String(o.altitudeM) : str(o.altitudeM),
    harvest: str(o.harvest),
    roastedOn: dateOrNull(o.roastedOn),
    openedOn: dateOrNull(o.openedOn),
    restDays: intOrNull(o.restDays),
    bagSizeG: numOrNull(o.bagSizeG),
    priceAmount: numOrNull(o.priceAmount),
    currency: str(o.currency),
    priceText: str(o.priceText),
    tastingNotes: str(o.tastingNotes),
    notes: str(o.notes),
    isArchived: bool(o.isArchived),
    createdAt: instantOrNull(o.createdAt),
    updatedAt: instantOrNull(o.updatedAt),
  };
}

export function decodeGrinder(o: Obj): GrinderDto | string {
  const uid = validUid(o.uid);
  if (!uid) return "no uid";
  const s = isObj(o.setting) ? o.setting : {};
  const cpr = intOrNull(s.clicksPerRotation);
  return {
    uid,
    catalogId: strOrNull(o.catalogId),
    brand: str(o.brand),
    model: str(o.model),
    burr: oneOf(o.burr, BURRS) ?? legacyLabel(LegacyLabels.BURR, str(o.burr)),
    burrSizeMm: typeof o.burrSizeMm === "number" ? String(o.burrSizeMm) : str(o.burrSizeMm),
    setting: {
      style: oneOf(s.style, STYLES) ?? legacyLabel(LegacyLabels.SETTING_STYLE, str(s.style)) ?? "clicks",
      min: num(s.min, 0),
      max: num(s.max, 40),
      step: num(s.step, 1),
      lowerIsFiner: bool(s.lowerIsFiner, true),
      clicksPerRotation: cpr != null && cpr > 0 ? cpr : null,
      micronsPerStep: numOrNull(s.micronsPerStep),
      dialFormat: str(s.dialFormat),
      settingUnit: oneOf(s.settingUnit, SETTING_UNIT_IDS) ?? "",
    },
    defaultFilterSetting: numOrNull(o.defaultFilterSetting),
    defaultEspressoSetting: numOrNull(o.defaultEspressoSetting),
    notes: str(o.notes),
    isDefault: bool(o.isDefault),
    isArchived: bool(o.isArchived),
    updatedAt: instantOrNull(o.updatedAt),
  };
}

export function decodeBrew(o: Obj): BrewDto | string {
  const uid = validUid(o.uid);
  if (!uid) return "no uid";
  const startedAt = instantOrNull(o.startedAt);
  if (!startedAt) return "no startedAt";
  const bed = isObj(o.bed) ? o.bed : {};
  return {
    uid,
    startedAt,
    updatedAt: instantOrNull(o.updatedAt),
    recipeId: str(o.recipeId),
    recipeName: str(o.recipeName),
    recipeAuthor: str(o.recipeAuthor),
    brewerId: strOrNull(o.brewerId),
    brewerLabel: str(o.brewerLabel),
    beanUid: validUid(o.beanUid),
    beanLabel: str(o.beanLabel),
    daysOffRoast: intOrNull(o.daysOffRoast),
    grinderUid: validUid(o.grinderUid),
    grinderLabel: str(o.grinderLabel),
    grindSetting: str(o.grindSetting),
    grindClicks: intOrNull(o.grindClicks),
    grindValue: numOrNull(o.grindValue),
    grindKind: oneOf(o.grindKind, GRIND_KIND_IDS) ?? "",
    doseG: num(o.doseG),
    waterG: num(o.waterG),
    tempC: int(o.tempC),
    waterId: strOrNull(o.waterId),
    totalTimeSec: int(o.totalTimeSec),
    drawdownSec: intOrNull(o.drawdownSec),
    beverageG: numOrNull(o.beverageG),
    tdsPercent: numOrNull(o.tdsPercent),
    extractionYield: numOrNull(o.extractionYield),
    flow: enumId(o.flow, LegacyLabels.FLOW),
    bed: {
      shape: enumId(bed.shape, LegacyLabels.BED),
      surface: enumId(bed.surface, LegacyLabels.SLUDGE),
      channelling: enumId(bed.channelling, LegacyLabels.CHANNELING),
      notes: str(bed.notes),
    },
    taste: taste(o.taste),
    overall: Math.min(10, Math.max(0, int(o.overall))),
    descriptors: strList(o.descriptors),
    faults: strList(o.faults),
    notes: str(o.notes),
    nextTime: str(o.nextTime),
    isFavourite: bool(o.isFavourite),
    sharedAt: instantOrNull(o.sharedAt),
  };
}

export function decodeRecipeDto(o: Obj): RecipeDto | string {
  const id = typeof o.id === "string" ? o.id.trim() : "";
  if (!id) return "no id";
  return { ...o, id, updatedAt: instantOrNull(o.updatedAt) };
}

export function decodeAnalysis(o: Obj, defaultOrigin: string): AnalysisDto | string {
  const uid = validUid(o.uid);
  if (!uid) return "no uid";
  const kind = oneOf(o.kind, ["brew", "trend"] as const);
  if (!kind) return "unknown kind";
  const createdAt = instantOrNull(o.createdAt);
  if (!createdAt) return "no createdAt";
  return {
    uid,
    kind,
    brewUid: kind === "brew" ? validUid(o.brewUid) : null,
    createdAt,
    origin: str(o.origin) || defaultOrigin,
    promptVersion: int(o.promptVersion, 1),
    inputHash: str(o.inputHash),
    brewCount: int(o.brewCount),
    newestBrewAt: instantOrNull(o.newestBrewAt),
    providerId: str(o.providerId) || "custom",
    model: str(o.model),
    host: str(o.host),
    rawText: str(o.rawText),
    result: o.result === undefined ? null : o.result,
    flags: Array.isArray(o.flags) ? o.flags : [],
    promptTokens: intOrNull(o.promptTokens),
    completionTokens: intOrNull(o.completionTokens),
    costMicroUsd: intOrNull(o.costMicroUsd),
    latencyMs: int(o.latencyMs),
    photoCount: int(o.photoCount),
  };
}

export function decodePhoto(o: Obj): PhotoDto | string {
  const uid = validUid(o.uid);
  if (!uid) return "no uid";
  const brewUid = validUid(o.brewUid);
  const beanUid = validUid(o.beanUid);
  if (!brewUid && !beanUid) return "belongs to no brew or coffee";
  const createdAt = instantOrNull(o.createdAt);
  const takenAt = instantOrNull(o.takenAt) ?? createdAt;
  if (!takenAt) return "no takenAt";
  const data = typeof o.data === "string" && o.data !== "" ? o.data : null;
  return {
    uid,
    brewUid,
    beanUid: brewUid ? null : beanUid,
    kind: oneOf(o.kind, PHOTO_KINDS) ?? "other",
    takenAt,
    createdAt: createdAt ?? takenAt,
    caption: str(o.caption),
    sendToAi: bool(o.sendToAi, true),
    widthPx: int(o.widthPx),
    heightPx: int(o.heightPx),
    mediaType: "image/jpeg",
    data,
  };
}

export function decodeSettings(v: unknown): SettingsDto | null {
  if (!isObj(v)) return null;
  const out: SettingsDto = {};
  const t = oneOf(v.tempUnit, ["c", "f"] as const);
  if (t) out.tempUnit = t;
  const w = oneOf(v.weightUnit, ["g", "oz"] as const);
  if (w) out.weightUnit = w;
  const d = oneOf(v.darkMode, ["system", "light", "dark"] as const);
  if (d) out.darkMode = d;
  const e = oneOf(v.experience, ["", "new", "recipes", "tinker"] as const);
  if (e != null) out.experience = e;
  const rm = oneOf(v.reviewMode, ["quick", "detailed"] as const);
  if (rm) out.reviewMode = rm;
  for (const k of ["haptics", "soundCues", "keepScreenOn", "autoAdvanceSteps", "onboardingDone", "hasScale", "buysGround"] as const) {
    const b = boolOrNull(v[k]);
    if (b != null) out[k] = b;
  }
  for (const k of ["name", "homeArea", "defaultWaterId", "lastRecipeId"] as const) {
    if (typeof v[k] === "string") out[k] = v[k];
  }
  for (const k of ["ownedBrewerIds", "favouriteRoasterIds"] as const) {
    if (Array.isArray(v[k])) out[k] = strList(v[k]);
  }
  for (const k of ["defaultBeanUid", "defaultGrinderUid"] as const) {
    if (k in v) out[k] = validUid(v[k]);
  }
  return out;
}

export function decodeAi(v: unknown): AiDto | null {
  if (!isObj(v)) return null;
  const providers = Array.isArray(v.providers)
    ? v.providers.filter(isObj).flatMap((p) => {
        const id = str(p.id).trim();
        if (!id) return [];
        return [{ id, baseUrl: id === "custom" ? strOrNull(p.baseUrl) : null, model: str(p.model), vision: boolOrNull(p.vision) }];
      })
    : [];
  return { activeProvider: strOrNull(v.activeProvider), sendPhotos: bool(v.sendPhotos, true), providers };
}

export interface DecodeOutcome {
  doc: BackupDoc;
  /** "brews[3]: no uid" — records skipped while reading. */
  problems: string[];
}

function decodeList<T>(raw: unknown, name: string, decode: (o: Obj) => T | string, problems: string[]): T[] {
  if (!Array.isArray(raw)) return [];
  const out: T[] = [];
  raw.forEach((item, i) => {
    const r = isObj(item) ? decode(item) : "not an object";
    if (typeof r === "string") problems.push(`${name}[${i}]: ${r}`);
    else out.push(r);
  });
  return out;
}

/** Lenient decode of a value detectValue() called "v2". */
export function decodeV2(v: unknown): DecodeOutcome {
  const o = isObj(v) ? v : {};
  const problems: string[] = [];
  const app = isObj(o.app) ? o.app : {};
  const options = isObj(o.options) ? o.options : {};
  const platform = str(app.platform) || "android";
  const doc: BackupDoc = {
    format: BACKUP_FORMAT,
    version: int(o.version, BACKUP_VERSION),
    exportedAt: instantOrNull(o.exportedAt) ?? "",
    app: { platform, appVersion: str(app.appVersion), packageName: str(app.packageName), dbVersion: int(app.dbVersion) },
    options: { includesPhotos: bool(options.includesPhotos), includesAnalyses: bool(options.includesAnalyses) },
    settings: decodeSettings(o.settings),
    ai: decodeAi(o.ai),
    beans: decodeList(o.beans, "beans", decodeBean, problems),
    grinders: decodeList(o.grinders, "grinders", decodeGrinder, problems),
    brews: decodeList(o.brews, "brews", decodeBrew, problems),
    recipes: decodeList(o.recipes, "recipes", decodeRecipeDto, problems),
    analyses: decodeList(o.analyses, "analyses", (x) => decodeAnalysis(x, platform), problems),
    photos: decodeList(o.photos, "photos", decodePhoto, problems),
  };
  return { doc, problems };
}

// ---------------------------------------------------------------- encode

/** Pretty JSON with a two-space indent, as Android writes it. Keys follow the doc's own order. */
export function encode(doc: BackupDoc): string {
  return JSON.stringify(doc, null, 2);
}
