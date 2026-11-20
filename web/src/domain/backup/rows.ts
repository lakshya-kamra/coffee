/**
 * Backup v2 DTOs <-> IndexedDB rows (web-spec §8.1, §15.1). Local numeric ids never leave the
 * browser: on the way out they become uids, on the way in the caller hands over the uid -> id
 * maps it built while writing. Pure.
 */
import type { AiPrefs, Settings } from "@/data/settings";
import type { AnalysisRow, BeanRow, BrewRow, GrinderRow, PhotoRow, SavedRecipeRow } from "@/data/schema";
import type { Brewer } from "@/domain/core/brewer";
import { epochDayToIso, isoToEpochDay } from "@/domain/core/format";
import { decodeRecipe, encodeRecipe, type RecipeJson } from "@/domain/core/recipe";
import { formatInstant, parseInstant } from "./codec";
import {
  BACKUP_FORMAT,
  BACKUP_VERSION,
  type AiDto,
  type AnalysisDto,
  type BackupApp,
  type BackupDoc,
  type BeanDto,
  type BrewDto,
  type GrinderDto,
  type PhotoDto,
  type RecipeDto,
  type SettingsDto,
} from "./model";

const iso = (ms: number | null | undefined): string | null => (ms == null ? null : formatInstant(ms));
const day = (d: number | null | undefined): string | null => (d == null ? null : epochDayToIso(d));

// ================================================================ rows -> DTO (export)

export function beanToDto(b: BeanRow): BeanDto {
  return {
    uid: b.uid,
    name: b.name,
    roaster: b.roaster,
    roasterId: b.roasterId,
    origin: b.origin,
    originId: b.originId,
    region: b.region,
    farm: b.farm,
    producer: b.producer,
    washingStation: b.washingStation,
    lotTypeId: b.lotTypeId,
    speciesId: b.speciesId,
    varietalIds: [...b.varietalIds],
    varietal: b.varietal,
    processId: b.processId,
    isDecaf: b.isDecaf,
    decafMethodId: b.decafMethodId,
    roastLevelId: b.roastLevelId,
    roastIntentId: b.roastIntentId,
    // Rows from before v4 may lack these until written again.
    form: b.form ?? "",
    groundFor: b.groundFor ?? "",
    altitudeM: b.altitudeMasl,
    harvest: b.harvest,
    roastedOn: day(b.roastedOnEpochDay),
    openedOn: day(b.openedOnEpochDay),
    restDays: b.restDays,
    bagSizeG: b.bagSizeG,
    priceAmount: b.priceAmount,
    currency: b.currency,
    priceText: b.price,
    tastingNotes: b.tastingNotes,
    notes: b.notes,
    isArchived: b.isArchived,
    createdAt: iso(b.createdAt),
    updatedAt: iso(b.updatedAt),
  };
}

export function grinderToDto(g: GrinderRow): GrinderDto {
  return {
    uid: g.uid,
    catalogId: g.catalogId,
    brand: g.brand,
    model: g.model,
    burr: g.burrType,
    burrSizeMm: g.burrSizeMm,
    setting: {
      style: g.settingStyle,
      min: g.minSetting,
      max: g.maxSetting,
      step: g.step,
      lowerIsFiner: g.lowerIsFiner,
      clicksPerRotation: g.clicksPerRotation > 0 ? g.clicksPerRotation : null,
      micronsPerStep: g.micronsPerClick,
      dialFormat: g.dialFormat,
      settingUnit: g.settingUnit ?? "",
    },
    defaultFilterSetting: g.defaultFilterSetting,
    defaultEspressoSetting: g.defaultEspressoSetting,
    notes: g.notes,
    isDefault: g.isDefault,
    isArchived: g.isArchived,
    updatedAt: iso(g.updatedAt),
  };
}

export function brewToDto(l: BrewRow, beanUid: (id: number) => string | null, grinderUid: (id: number) => string | null): BrewDto {
  return {
    uid: l.uid,
    startedAt: formatInstant(l.startedAt),
    updatedAt: iso(l.updatedAt),
    recipeId: l.recipeId,
    recipeName: l.recipeName,
    recipeAuthor: l.recipeAuthor,
    brewerId: l.brewerId,
    brewerLabel: l.brewer,
    beanUid: l.beanId != null ? beanUid(l.beanId) : null,
    beanLabel: l.beanLabel,
    daysOffRoast: l.daysOffRoast,
    grinderUid: l.grinderId != null ? grinderUid(l.grinderId) : null,
    grinderLabel: l.grinderLabel,
    grindSetting: l.grindSetting,
    grindClicks: l.grindClicks,
    grindValue: l.grindValue,
    grindKind: l.grindKind ?? "",
    doseG: l.doseG,
    waterG: l.waterG,
    tempC: l.tempC,
    waterId: l.waterId,
    totalTimeSec: l.totalTimeSec,
    drawdownSec: l.drawdownSec,
    beverageG: l.beverageG,
    tdsPercent: l.tdsPercent,
    extractionYield: l.extractionYield,
    flow: l.flowFeel,
    bed: { shape: l.bedShape, surface: l.sludge, channelling: l.channeling, notes: l.bedNotes },
    taste: {
      acidity: l.taste.acidity,
      sweetness: l.taste.sweetness,
      bitterness: l.taste.bitterness,
      body: l.taste.body,
      balance: l.taste.balance,
      aftertaste: l.taste.aftertaste,
      clarity: l.taste.clarity,
    },
    overall: l.overall,
    descriptors: [...l.descriptors],
    faults: [...l.faults],
    notes: l.notes,
    nextTime: l.nextTime,
    isFavourite: l.isFavourite,
    sharedAt: iso(l.sharedAt),
  };
}

export function recipeToDto(r: SavedRecipeRow): RecipeDto {
  return { ...r.recipe, id: r.id, updatedAt: iso(r.updatedAt) };
}

const BREW_UIDS = "brewUids";
const isObject = (v: unknown): v is Record<string, unknown> => v != null && typeof v === "object" && !Array.isArray(v);

/** The brew numbers a stored trend's patterns name; null when `result` holds no trend. */
function trendBrewNumbers(result: unknown): number[] | null {
  if (!isObject(result) || !isObject(result.trend)) return null;
  const patterns: unknown[] = Array.isArray(result.trend.patterns) ? result.trend.patterns : [];
  const numbers = patterns.flatMap((p): unknown[] => (isObject(p) && Array.isArray(p.brewIds) ? p.brewIds : []));
  return [...new Set(numbers.filter((n): n is number => typeof n === "number" && Number.isInteger(n)))];
}

/**
 * A trend stored before results kept brew uids is given them on the way out, while its numbers
 * are still this browser's row ids. In the file they would mean nothing. One that another app
 * made (`madeHere` false) never held this browser's row ids: it gets an empty table.
 */
function withBrewUids(result: unknown, madeHere: boolean, brewUid: (id: number) => string | null): unknown {
  const numbers = trendBrewNumbers(result);
  if (numbers == null || !isObject(result) || BREW_UIDS in result) return result;
  if (!madeHere) return { ...result, [BREW_UIDS]: {} };
  const uids: Record<string, string> = {};
  for (const n of numbers) {
    const uid = brewUid(n);
    if (uid) uids[String(n)] = uid;
  }
  return { ...result, [BREW_UIDS]: uids };
}

/**
 * A trend that arrives with no brew uids names its brews by another install's row ids. An empty
 * table says so: none of its numbers is followed, where a guess could open the wrong brew.
 */
function withoutForeignBrewNumbers(result: unknown): unknown {
  if (trendBrewNumbers(result) == null || !isObject(result) || BREW_UIDS in result) return result;
  return { ...result, [BREW_UIDS]: {} };
}

export function analysisToDto(a: AnalysisRow, brewUid: (id: number) => string | null): AnalysisDto {
  return {
    uid: a.uid,
    kind: a.kind,
    brewUid: a.kind === "brew" && a.brewLogId != null ? brewUid(a.brewLogId) : null,
    createdAt: formatInstant(a.createdAt),
    origin: a.origin,
    promptVersion: a.promptVersion,
    inputHash: a.inputHash,
    brewCount: a.brewCount,
    newestBrewAt: a.newestBrewAt > 0 ? formatInstant(a.newestBrewAt) : null,
    providerId: a.providerId,
    model: a.model,
    host: a.host,
    rawText: a.rawText,
    result: withBrewUids(a.result ?? null, a.origin === "web", brewUid),
    flags: [...a.flags],
    promptTokens: a.promptTokens,
    completionTokens: a.completionTokens,
    costMicroUsd: a.costMicroUsd,
    latencyMs: a.latencyMs,
    photoCount: a.photoCount,
  };
}

export function photoToDto(p: PhotoRow, brewUid: (id: number) => string | null, beanUid: (id: number) => string | null): PhotoDto {
  return {
    uid: p.uid,
    brewUid: p.brewLogId != null ? brewUid(p.brewLogId) : null,
    beanUid: p.brewLogId == null && p.beanId != null ? beanUid(p.beanId) : null,
    kind: p.kind,
    takenAt: formatInstant(p.takenAt),
    createdAt: formatInstant(p.createdAt),
    caption: p.caption,
    sendToAi: p.sendToAi,
    widthPx: p.widthPx,
    heightPx: p.heightPx,
    mediaType: "image/jpeg",
    data: null,
  };
}

export function settingsToDto(s: Settings, beanUid: (id: number) => string | null, grinderUid: (id: number) => string | null): Required<SettingsDto> {
  return {
    tempUnit: s.tempUnit,
    weightUnit: s.weightUnit,
    darkMode: s.darkMode,
    haptics: s.haptics,
    soundCues: s.soundCues,
    keepScreenOn: s.keepScreenOn,
    autoAdvanceSteps: s.autoAdvanceSteps,
    name: s.name,
    ownedBrewerIds: [...s.ownedBrewerIds],
    favouriteRoasterIds: [...s.favouriteRoasterIds],
    homeArea: s.homeArea,
    defaultWaterId: s.defaultWaterId,
    defaultBeanUid: s.defaultBeanId > 0 ? beanUid(s.defaultBeanId) : null,
    defaultGrinderUid: s.defaultGrinderId > 0 ? grinderUid(s.defaultGrinderId) : null,
    lastRecipeId: s.lastRecipeId,
    onboardingDone: s.onboardingDone,
    experience: s.experience,
    hasScale: s.hasScale,
    reviewMode: s.reviewMode,
    buysGround: s.buysGround,
  };
}

/** Provider, model and base URL only. aiPrefs holds no key; keys live in Web Storage and never travel. */
export function aiToDto(a: AiPrefs): AiDto {
  return {
    activeProvider: a.activeProvider || null,
    sendPhotos: a.sendPhotos,
    providers: Object.entries(a.providers).map(([id, p]) => ({
      id,
      baseUrl: id === "custom" ? (p.baseUrl ?? null) : null,
      model: p.model,
      vision: p.vision ?? null,
    })),
  };
}

export interface ExportSnapshot {
  beans: readonly BeanRow[];
  grinders: readonly GrinderRow[];
  brews: readonly BrewRow[];
  savedRecipes: readonly SavedRecipeRow[];
  analyses: readonly AnalysisRow[];
  photos: readonly PhotoRow[];
  settings: Settings;
  aiPrefs: AiPrefs;
}

/** The whole document with photo data left null; the exporter streams the bytes in afterwards. */
export function snapshotToDoc(
  s: ExportSnapshot,
  o: { exportedAt: number; app: BackupApp; includesPhotos: boolean; includesAnalyses: boolean },
): BackupDoc {
  const uidOf = (rows: readonly { id?: number; uid: string }[]) => {
    const m = new Map<number, string>();
    for (const r of rows) if (r.id != null) m.set(r.id, r.uid);
    return (id: number) => m.get(id) ?? null;
  };
  const beanUid = uidOf(s.beans);
  const grinderUid = uidOf(s.grinders);
  const brewUid = uidOf(s.brews);
  return {
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    exportedAt: formatInstant(o.exportedAt),
    app: o.app,
    options: { includesPhotos: o.includesPhotos, includesAnalyses: o.includesAnalyses },
    settings: settingsToDto(s.settings, beanUid, grinderUid),
    ai: aiToDto(s.aiPrefs),
    beans: s.beans.map(beanToDto),
    grinders: s.grinders.map(grinderToDto),
    brews: [...s.brews].sort((a, b) => a.startedAt - b.startedAt).map((l) => brewToDto(l, beanUid, grinderUid)),
    recipes: s.savedRecipes.map(recipeToDto),
    analyses: o.includesAnalyses ? s.analyses.map((a) => analysisToDto(a, brewUid)) : [],
    // Orphans (a brew or coffee deleted under them) would import as photos of nothing.
    photos: s.photos.map((p) => photoToDto(p, brewUid, beanUid)).filter((p) => p.brewUid != null || p.beanUid != null),
  };
}

// ================================================================ DTO -> rows (import)

const ms = (v: string | null | undefined): number | null => (v ? parseInstant(v) : null);
const epochDay = (v: string | null): number | null => (v ? isoToEpochDay(v) : null);

export function beanFromDto(d: BeanDto, fallbackMs: number): Omit<BeanRow, "id"> {
  const createdAt = ms(d.createdAt) ?? ms(d.updatedAt) ?? fallbackMs;
  return {
    uid: d.uid,
    name: d.name,
    roaster: d.roaster,
    roasterId: d.roasterId,
    origin: d.origin,
    originId: d.originId,
    region: d.region,
    farm: d.farm,
    producer: d.producer,
    washingStation: d.washingStation,
    lotTypeId: d.lotTypeId,
    speciesId: d.speciesId,
    varietalIds: [...d.varietalIds],
    varietal: d.varietal,
    processId: d.processId,
    isDecaf: d.isDecaf,
    decafMethodId: d.decafMethodId,
    roastLevelId: d.roastLevelId,
    roastIntentId: d.roastIntentId,
    roastedOnEpochDay: epochDay(d.roastedOn),
    openedOnEpochDay: epochDay(d.openedOn),
    bagSizeG: d.bagSizeG,
    price: d.priceText,
    priceAmount: d.priceAmount,
    currency: d.currency,
    altitudeMasl: d.altitudeM,
    harvest: d.harvest,
    restDays: d.restDays,
    tastingNotes: d.tastingNotes,
    notes: d.notes,
    isArchived: d.isArchived,
    createdAt,
    updatedAt: ms(d.updatedAt) ?? createdAt,
    form: d.form,
    groundFor: d.groundFor,
  };
}

export function grinderFromDto(d: GrinderDto, fallbackMs: number): Omit<GrinderRow, "id"> {
  return {
    uid: d.uid,
    brand: d.brand,
    model: d.model,
    burrType: d.burr,
    burrSizeMm: d.burrSizeMm,
    settingStyle: d.setting.style,
    clicksPerRotation: d.setting.clicksPerRotation ?? 0,
    micronsPerClick: d.setting.micronsPerStep,
    lowerIsFiner: d.setting.lowerIsFiner,
    minSetting: d.setting.min,
    maxSetting: d.setting.max,
    step: d.setting.step,
    defaultFilterSetting: d.defaultFilterSetting,
    defaultEspressoSetting: d.defaultEspressoSetting,
    notes: d.notes,
    isDefault: d.isDefault,
    isArchived: d.isArchived,
    catalogId: d.catalogId,
    dialFormat: d.setting.dialFormat,
    updatedAt: ms(d.updatedAt) ?? fallbackMs,
    settingUnit: d.setting.settingUnit,
  };
}

export function brewFromDto(d: BrewDto, beanId: number | null, grinderId: number | null): Omit<BrewRow, "id"> {
  const startedAt = ms(d.startedAt) ?? 0;
  return {
    uid: d.uid,
    startedAt,
    updatedAt: ms(d.updatedAt) ?? startedAt,
    recipeId: d.recipeId,
    recipeName: d.recipeName,
    recipeAuthor: d.recipeAuthor,
    brewer: d.brewerLabel,
    brewerId: d.brewerId,
    beanId,
    beanLabel: d.beanLabel,
    daysOffRoast: d.daysOffRoast,
    grinderId,
    grinderLabel: d.grinderLabel,
    grindSetting: d.grindSetting,
    grindClicks: d.grindClicks,
    grindValue: d.grindValue,
    doseG: d.doseG,
    waterG: d.waterG,
    tempC: d.tempC,
    waterId: d.waterId,
    totalTimeSec: d.totalTimeSec,
    drawdownSec: d.drawdownSec,
    beverageG: d.beverageG,
    tdsPercent: d.tdsPercent,
    extractionYield: d.extractionYield,
    flowFeel: d.flow,
    bedShape: d.bed.shape,
    sludge: d.bed.surface,
    channeling: d.bed.channelling,
    bedNotes: d.bed.notes,
    taste: { ...d.taste },
    overall: d.overall,
    descriptors: [...d.descriptors],
    faults: [...d.faults],
    notes: d.notes,
    nextTime: d.nextTime,
    isFavourite: d.isFavourite,
    sharedAt: ms(d.sharedAt),
    grindKind: d.grindKind,
  };
}

/** Null when the entry is not a recipe this app can open (bad steps, no dose…). */
export function savedRecipeFromDto(d: RecipeDto, resolveBrewer: (s: string) => Brewer, fallbackMs: number): SavedRecipeRow | null {
  const r = decodeRecipe(d, resolveBrewer, false);
  if (!r.ok) return null;
  const recipe: RecipeJson = { ...encodeRecipe(r.value), isBuiltIn: false };
  return { id: d.id, recipe, updatedAt: ms(d.updatedAt) ?? fallbackMs };
}

export function analysisFromDto(d: AnalysisDto, brewLogId: number | null): Omit<AnalysisRow, "id"> {
  return {
    uid: d.uid,
    kind: d.kind,
    brewLogId,
    createdAt: ms(d.createdAt) ?? 0,
    promptVersion: d.promptVersion,
    inputHash: d.inputHash,
    brewCount: d.brewCount,
    newestBrewAt: ms(d.newestBrewAt) ?? 0,
    model: d.model,
    host: d.host,
    rawText: d.rawText,
    result: withoutForeignBrewNumbers(d.result),
    flags: [...d.flags],
    promptTokens: d.promptTokens,
    completionTokens: d.completionTokens,
    costMicroUsd: d.costMicroUsd,
    latencyMs: d.latencyMs,
    providerId: d.providerId,
    photoCount: d.photoCount,
    // Lead decision 12: rows from the other platform read "Made on another device".
    origin: d.origin,
  };
}

export function photoFromDto(d: PhotoDto, brewLogId: number | null, beanId: number | null, bytes: number): Omit<PhotoRow, "id"> {
  const takenAt = ms(d.takenAt) ?? 0;
  return {
    uid: d.uid,
    brewLogId,
    beanId: brewLogId == null ? beanId : null,
    kind: d.kind,
    mediaType: "image/jpeg",
    widthPx: d.widthPx,
    heightPx: d.heightPx,
    bytes,
    takenAt,
    createdAt: ms(d.createdAt) ?? takenAt,
    caption: d.caption,
    sendToAi: d.sendToAi,
  };
}

/** Only the keys the file carries; uids become this browser's ids (0 = none). */
export function settingsPatchFromDto(d: SettingsDto, beanId: (uid: string) => number | null, grinderId: (uid: string) => number | null): Partial<Settings> {
  const { defaultBeanUid, defaultGrinderUid, ...plain } = d;
  const out: Partial<Settings> = { ...plain };
  if (defaultBeanUid !== undefined) out.defaultBeanId = defaultBeanUid ? (beanId(defaultBeanUid) ?? 0) : 0;
  if (defaultGrinderUid !== undefined) out.defaultGrinderId = defaultGrinderUid ? (grinderId(defaultGrinderUid) ?? 0) : 0;
  return out;
}

/**
 * Provider and model choices from the file merged over this browser's. A provider's test result
 * is dropped when its address changes, since that test was for another server.
 */
export function aiPrefsFromDto(d: AiDto, cur: AiPrefs): AiPrefs {
  const providers = { ...cur.providers };
  for (const p of d.providers) {
    const prev = providers[p.id];
    const baseUrl = p.id === "custom" ? (p.baseUrl ?? undefined) : undefined;
    const sameServer = prev != null && prev.baseUrl === baseUrl;
    providers[p.id] = {
      model: p.model,
      ...(baseUrl !== undefined ? { baseUrl } : {}),
      vision: p.vision,
      ...(sameServer && prev.testOkAt != null ? { testOkAt: prev.testOkAt } : {}),
      ...(sameServer && prev.testSig != null ? { testSig: prev.testSig } : {}),
    };
  }
  return { ...cur, activeProvider: d.activeProvider ?? cur.activeProvider, sendPhotos: d.sendPhotos, providers };
}
