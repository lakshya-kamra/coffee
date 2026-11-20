/**
 * Reads the 1.x JSON export ("schema": "coffee-brew-log/1", BrewReport.json in every Android
 * build up to 1.0, database versions 1-3) into a v2 document (A§10.1 "Older files").
 *
 * It has no uids, so each record gets uid = "legacy-" + the first 16 hex characters of
 * SHA-256("<exportedAt>|<type>|<id>") over UTF-8, with exportedAt exactly as written in the file
 * and type one of "bean", "grinder", "brew". Importing the same file twice therefore changes
 * nothing. Records carry no updatedAt, so they never replace a row already here.
 */
import { LegacyIds, LegacyLabels, legacyLabel } from "@/domain/core/legacyIds";
import { epochDayToIso } from "@/domain/core/format";
import { bool, dateOrNull, instantOrNull, int, intOrNull, isObj, num, numOrNull, str, strList, type DecodeOutcome } from "./codec";
import { BACKUP_FORMAT, BACKUP_VERSION, type BackupDoc, type BeanDto, type BrewDto, type GrinderDto } from "./model";

export type LegacyType = "bean" | "grinder" | "brew";

/** The built-in recipes a 1.x brew can resolve to by exact name + author, in catalog order. */
export interface RecipeRef {
  id: string;
  name: string;
  author: string;
}

export async function legacyUid(exportedAt: string, type: LegacyType, id: string | number): Promise<string> {
  const bytes = new TextEncoder().encode(`${exportedAt}|${type}|${id}`);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  let hex = "";
  for (let i = 0; i < 8; i++) hex += digest[i]!.toString(16).padStart(2, "0");
  return "legacy-" + hex;
}

/** The 1.x id as written (a number), kept as text so a hand-edited "7" hashes the same as 7. */
function idText(v: unknown): string | null {
  if (typeof v === "number" && Number.isFinite(v)) return String(Math.trunc(v));
  if (typeof v === "string" && v.trim() !== "") return v.trim();
  return null;
}

/** GrinderEntity.display: brand and model, blanks dropped, joined by a space. */
function grinderLabel(brand: string, model: string): string {
  return [brand, model].filter((s) => s.trim() !== "").join(" ");
}

function legacyRecipeId(name: string, author: string, builtIns: readonly RecipeRef[]): string {
  const id = LegacyIds.RECIPE[`${name.trim()}|${author.trim()}`];
  return id != null && builtIns.some((r) => r.id === id) ? id : "";
}

export async function readLegacy(v: unknown, builtIns: readonly RecipeRef[]): Promise<DecodeOutcome> {
  const o = isObj(v) ? v : {};
  const problems: string[] = [];
  const exportedAtRaw = str(o.exportedAt);
  const exportedAt = instantOrNull(exportedAtRaw) ?? "";

  const beanUids = new Map<string, string>();
  const beans: BeanDto[] = [];
  for (const [i, b] of (Array.isArray(o.beans) ? o.beans : []).entries()) {
    const id = isObj(b) ? idText(b.id) : null;
    if (!isObj(b) || id == null) {
      problems.push(`beans[${i}]: no id`);
      continue;
    }
    if (!str(b.name).trim()) {
      problems.push(`beans[${i}]: no name`);
      continue;
    }
    const uid = await legacyUid(exportedAtRaw, "bean", id);
    beanUids.set(id, uid);
    const roastedDay = intOrNull(b.roastedOnEpochDay);
    beans.push({
      uid,
      name: str(b.name),
      roaster: str(b.roaster),
      roasterId: null,
      origin: str(b.origin),
      originId: null,
      region: str(b.region),
      farm: "",
      producer: "",
      washingStation: "",
      lotTypeId: null,
      speciesId: null,
      varietalIds: [],
      varietal: str(b.varietal),
      processId: LegacyIds.PROCESS_LABEL[str(b.process).trim()] ?? LegacyIds.PROCESS[str(b.process).trim()] ?? "",
      isDecaf: false,
      decafMethodId: null,
      roastLevelId: LegacyIds.ROAST_LEVEL_LABEL[str(b.roastLevel).trim()] ?? LegacyIds.ROAST_LEVEL[str(b.roastLevel).trim()] ?? "",
      roastIntentId: null,
      form: "",
      groundFor: "",
      altitudeM: "",
      harvest: "",
      roastedOn: roastedDay != null ? epochDayToIso(roastedDay) : dateOrNull(b.roastedOn),
      openedOn: null,
      restDays: null,
      bagSizeG: null,
      priceAmount: null,
      currency: "",
      priceText: "",
      tastingNotes: str(b.tastingNotes),
      notes: "",
      isArchived: false,
      createdAt: null,
      updatedAt: null,
    });
  }

  const grinders: GrinderDto[] = [];
  const grinderByLabel = new Map<string, string>();
  for (const [i, g] of (Array.isArray(o.grinders) ? o.grinders : []).entries()) {
    const id = isObj(g) ? idText(g.id) : null;
    if (!isObj(g) || id == null) {
      problems.push(`grinders[${i}]: no id`);
      continue;
    }
    const uid = await legacyUid(exportedAtRaw, "grinder", id);
    const brand = str(g.brand);
    const model = str(g.model);
    const label = grinderLabel(brand, model);
    // Two grinders with the same name: brews link to the first, as a label can say no more.
    if (label && !grinderByLabel.has(label)) grinderByLabel.set(label, uid);
    const cpr = intOrNull(g.clicksPerRotation);
    grinders.push({
      uid,
      catalogId: null,
      brand,
      model,
      burr: legacyLabel(LegacyLabels.BURR, str(g.burrType)),
      burrSizeMm: "",
      // Fields 1.x did not export take Room's defaults (0-40, step 1). The click count per turn
      // is kept exactly as stored: a 1.x K-Ultra row stays at 10 (lead decision 2).
      setting: {
        style: legacyLabel(LegacyLabels.SETTING_STYLE, str(g.settingStyle)) ?? "clicks",
        min: 0,
        max: 40,
        step: 1,
        lowerIsFiner: bool(g.lowerIsFiner, true),
        clicksPerRotation: cpr != null && cpr > 0 ? cpr : null,
        micronsPerStep: numOrNull(g.micronsPerClick),
        dialFormat: "",
        settingUnit: "",
      },
      defaultFilterSetting: null,
      defaultEspressoSetting: null,
      notes: "",
      isDefault: false,
      isArchived: false,
      updatedAt: null,
    });
  }

  const brews: BrewDto[] = [];
  for (const [i, l] of (Array.isArray(o.brews) ? o.brews : []).entries()) {
    const id = isObj(l) ? idText(l.id) : null;
    if (!isObj(l) || id == null) {
      problems.push(`brews[${i}]: no id`);
      continue;
    }
    const startedAt = instantOrNull(l.startedAt);
    if (!startedAt) {
      problems.push(`brews[${i}]: no startedAt`);
      continue;
    }
    const recipeName = str(l.recipe);
    const recipeAuthor = str(l.recipeAuthor);
    const brewerLabel = str(l.brewer);
    const beanId = idText(l.beanId);
    const gLabel = str(l.grinder);
    const t = isObj(l.taste) ? l.taste : {};
    brews.push({
      uid: await legacyUid(exportedAtRaw, "brew", id),
      startedAt,
      updatedAt: null,
      // The current name first, then the 1.x name of a built-in renamed or re-attributed since.
      recipeId: builtIns.find((r) => r.name === recipeName && r.author === recipeAuthor)?.id ?? legacyRecipeId(recipeName, recipeAuthor, builtIns),
      recipeName,
      recipeAuthor,
      brewerId: LegacyIds.BREWER[brewerLabel.trim()] ?? null,
      brewerLabel,
      beanUid: beanId != null ? (beanUids.get(beanId) ?? null) : null,
      beanLabel: str(l.bean),
      daysOffRoast: intOrNull(l.daysOffRoast),
      grinderUid: gLabel.trim() ? (grinderByLabel.get(gLabel.trim()) ?? null) : null,
      grinderLabel: gLabel,
      grindSetting: str(l.grindSetting),
      grindClicks: intOrNull(l.grindClicks),
      grindValue: null,
      grindKind: "",
      doseG: num(l.doseG),
      waterG: num(l.waterG),
      tempC: int(l.tempC),
      waterId: null,
      totalTimeSec: int(l.totalTimeSec),
      drawdownSec: intOrNull(l.drawdownSec),
      beverageG: numOrNull(l.beverageG),
      tdsPercent: numOrNull(l.tdsPercent),
      extractionYield: numOrNull(l.extractionYield),
      flow: legacyLabel(LegacyLabels.FLOW, str(l.flowFeel)),
      bed: {
        shape: legacyLabel(LegacyLabels.BED, str(l.bedShape)),
        surface: legacyLabel(LegacyLabels.SLUDGE, str(l.sludge)),
        channelling: legacyLabel(LegacyLabels.CHANNELING, str(l.channeling)),
        notes: str(l.bedNotes),
      },
      taste: {
        acidity: int(t.acidity, 5),
        sweetness: int(t.sweetness, 5),
        bitterness: int(t.bitterness, 5),
        body: int(t.body, 5),
        balance: int(t.balance, 5),
        aftertaste: int(t.aftertaste, 5),
        clarity: int(t.clarity, 5),
      },
      overall: int(l.overall),
      descriptors: strList(l.descriptors),
      faults: strList(l.faults),
      notes: str(l.notes),
      nextTime: str(l.nextTime),
      isFavourite: false,
      sharedAt: null,
    });
  }

  const doc: BackupDoc = {
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    exportedAt,
    app: { platform: "android", appVersion: "1.x", packageName: "", dbVersion: 0 },
    options: { includesPhotos: false, includesAnalyses: false },
    settings: null,
    ai: null,
    beans,
    grinders,
    brews,
    recipes: [],
    analyses: [],
    photos: [],
  };
  return { doc, problems };
}
