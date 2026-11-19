/**
 * Applies a read backup to IndexedDB (web-spec §15.3). One Dexie "rw" transaction over every
 * table, photo bytes included: any failure aborts it and nothing changes. The pure planner
 * (domain/backup/merge.ts) decides add / replace / keep; this file only maps uids to this
 * browser's ids and writes. Rows keep the file's updatedAt (not repo's stamp), which is what
 * makes a second import of the same file a no-op.
 */
import { Brewers, catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import type { GrinderRow } from "@/data/schema";
import { KV, withAiDefaults, withSettingsDefaults, type Settings } from "@/data/settings";
import { persistOnce } from "@/data/storage";
import { parseInstant } from "@/domain/backup/codec";
import { planBackup, type Existing, type MergeMode } from "@/domain/backup/merge";
import { ZERO_COUNTS, type BackupDoc, type Counts } from "@/domain/backup/model";
import type { PhotoBytes } from "@/domain/backup/read";
import {
  aiPrefsFromDto,
  analysisFromDto,
  beanFromDto,
  brewFromDto,
  grinderFromDto,
  photoFromDto,
  savedRecipeFromDto,
  settingsPatchFromDto,
} from "@/domain/backup/rows";
import { linkAll } from "./linking";

export interface ImportOptions {
  mode: MergeMode;
  bringSettings: boolean;
  bringAi: boolean;
}

export interface ImportResult {
  added: Counts;
  replaced: Counts;
  /** Already here and not older here: left as they are. */
  kept: Counts;
  /** Records that could not be placed (no image bytes, the brew they belong to is missing…). */
  skipped: string[];
}

export class ImportError extends Error {
  constructor(readonly kind: "storageFull" | "failed", cause?: unknown) {
    super(kind, { cause });
  }
}

function isQuota(e: unknown): boolean {
  for (let x: unknown = e, i = 0; x != null && i < 4; i++) {
    const name = (x as { name?: unknown }).name;
    if (name === "QuotaExceededError") return true;
    x = (x as { inner?: unknown; cause?: unknown }).inner ?? (x as { cause?: unknown }).cause;
  }
  return false;
}

function index<Id>(rows: readonly { uid: string; id?: Id; updatedAt?: number }[]): Map<string, Existing<Id>> {
  const m = new Map<string, Existing<Id>>();
  for (const r of rows) if (r.id != null) m.set(r.uid, { id: r.id, updatedAt: r.updatedAt ?? null });
  return m;
}

export async function applyBackup(
  read: { doc: BackupDoc; photoBytes: readonly (PhotoBytes | null)[]; preview?: { kind: "v2" | "legacy1" } },
  opts: ImportOptions,
  now = Date.now(),
): Promise<ImportResult> {
  const { doc } = read;
  // Saved recipes are checked against the brewer list before they are written.
  await catalog.load(["brewers"]);
  const fallbackMs = parseInstant(doc.exportedAt) ?? now;
  const bytesByUid = new Map<string, PhotoBytes>();
  doc.photos.forEach((p, i) => {
    const b = read.photoBytes[i];
    if (b && !bytesByUid.has(p.uid)) bytesByUid.set(p.uid, b);
  });

  const result: ImportResult = { added: { ...ZERO_COUNTS }, replaced: { ...ZERO_COUNTS }, kept: { ...ZERO_COUNTS }, skipped: [] };
  const tables = [db.beans, db.grinders, db.brew_logs, db.saved_recipes, db.analyses, db.photos, db.photo_blobs, db.kv];

  /** Uids of the beans and grinders written, for linking afterwards. */
  const written = new Set<string>();
  try {
    await db.transaction("rw", tables, async () => {
      if (opts.mode === "replace") {
        await Promise.all([
          db.beans.clear(), db.grinders.clear(), db.brew_logs.clear(), db.saved_recipes.clear(),
          db.analyses.clear(), db.photos.clear(), db.photo_blobs.clear(),
        ]);
      }
      const [beans, grinders, brews, saved, analyses, photos] = await Promise.all([
        db.beans.toArray(), db.grinders.toArray(), db.brew_logs.toArray(), db.saved_recipes.toArray(), db.analyses.toArray(), db.photos.toArray(),
      ]);
      const plan = planBackup(
        doc,
        {
          beans: index(beans),
          grinders: index(grinders),
          brews: index(brews),
          recipes: new Map(saved.map((r) => [r.id, { id: r.id, updatedAt: r.updatedAt }])),
          analyses: index(analyses),
          photos: index(photos),
        },
        opts.mode,
      );

      // ---- beans
      const beanIds = new Map(beans.map((b) => [b.uid, b.id!]));
      const newBeanIds = await db.beans.bulkAdd(plan.beans.add.map((d) => beanFromDto(d, fallbackMs)), { allKeys: true });
      plan.beans.add.forEach((d, i) => beanIds.set(d.uid, newBeanIds[i] as number));
      await db.beans.bulkPut(plan.beans.replace.map(({ item, id }) => ({ ...beanFromDto(item, fallbackMs), id })));
      result.added.beans = plan.beans.add.length;
      result.replaced.beans = plan.beans.replace.length;
      result.kept.beans = plan.beans.keep.length;

      // ---- grinders (at most one default: in "add" mode the one already here stays default)
      const localDefault = grinders.find((g) => g.isDefault);
      let defaultTaken = localDefault != null;
      const grinderRow = (d: (typeof plan.grinders.add)[number], keepFlagOf?: GrinderRow): Omit<GrinderRow, "id"> => {
        const row = grinderFromDto(d, fallbackMs);
        if (keepFlagOf) return { ...row, isDefault: keepFlagOf.isDefault };
        if (row.isDefault && defaultTaken) return { ...row, isDefault: false };
        if (row.isDefault) defaultTaken = true;
        return row;
      };
      const grinderById = new Map(grinders.map((g) => [g.id!, g]));
      const grinderIds = new Map(grinders.map((g) => [g.uid, g.id!]));
      const replacedGrinders = plan.grinders.replace.map(({ item, id }) => ({ ...grinderRow(item, grinderById.get(id)), id }));
      const newGrinderIds = await db.grinders.bulkAdd(plan.grinders.add.map((d) => grinderRow(d)), { allKeys: true });
      plan.grinders.add.forEach((d, i) => grinderIds.set(d.uid, newGrinderIds[i] as number));
      await db.grinders.bulkPut(replacedGrinders);
      result.added.grinders = plan.grinders.add.length;
      result.replaced.grinders = plan.grinders.replace.length;
      result.kept.grinders = plan.grinders.keep.length;

      for (const r of [...plan.beans.add, ...plan.beans.replace.map((x) => x.item), ...plan.grinders.add, ...plan.grinders.replace.map((x) => x.item)]) {
        written.add(r.uid);
      }

      // ---- brews
      const brewIds = new Map(brews.map((l) => [l.uid, l.id!]));
      const brewRow = (d: (typeof plan.brews.add)[number]) =>
        brewFromDto(d, d.beanUid ? (beanIds.get(d.beanUid) ?? null) : null, d.grinderUid ? (grinderIds.get(d.grinderUid) ?? null) : null);
      const newBrewIds = await db.brew_logs.bulkAdd(plan.brews.add.map(brewRow), { allKeys: true });
      plan.brews.add.forEach((d, i) => brewIds.set(d.uid, newBrewIds[i] as number));
      await db.brew_logs.bulkPut(plan.brews.replace.map(({ item, id }) => ({ ...brewRow(item), id })));
      result.added.brews = plan.brews.add.length;
      result.replaced.brews = plan.brews.replace.length;
      result.kept.brews = plan.brews.keep.length;

      // ---- the user's recipes and edits of built-ins
      for (const [part, list] of [["add", plan.recipes.add], ["replace", plan.recipes.replace.map((r) => r.item)]] as const) {
        for (const d of list) {
          const row = savedRecipeFromDto(d, Brewers.resolve, fallbackMs);
          if (!row) {
            result.skipped.push(`Recipe "${typeof d.name === "string" ? d.name : d.id}" couldn't be read`);
            continue;
          }
          await db.saved_recipes.put(row);
          if (part === "add") result.added.recipes++;
          else result.replaced.recipes++;
        }
      }
      result.kept.recipes = plan.recipes.keep.length;

      // ---- analyses (a brew analysis whose brew is not here would never show)
      const analysisRows = [];
      for (const d of plan.analyses.add) {
        const brewLogId = d.kind === "brew" ? (d.brewUid ? (brewIds.get(d.brewUid) ?? null) : null) : null;
        if (d.kind === "brew" && brewLogId == null) {
          result.skipped.push("An analysis of a brew that isn't in the backup");
          continue;
        }
        analysisRows.push(analysisFromDto(d, brewLogId));
      }
      await db.analyses.bulkAdd(analysisRows);
      result.added.analyses = analysisRows.length;
      result.kept.analyses = plan.analyses.keep.length;

      // ---- photos, rows and bytes together
      const photoRows = [];
      const blobRows = [];
      let noBytes = 0;
      for (const d of plan.photos.add) {
        const bytes = bytesByUid.get(d.uid);
        if (!bytes) {
          noBytes++;
          continue;
        }
        const brewLogId = d.brewUid ? (brewIds.get(d.brewUid) ?? null) : null;
        const beanId = d.beanUid ? (beanIds.get(d.beanUid) ?? null) : null;
        if (brewLogId == null && beanId == null) {
          result.skipped.push("A photo of a brew or coffee that isn't in the backup");
          continue;
        }
        photoRows.push({ ...photoFromDto(d, brewLogId, beanId, bytes.full.size), ...bytes.size });
        blobRows.push({ uid: d.uid, full: bytes.full, thumb: bytes.thumb ?? bytes.full });
      }
      if (noBytes > 0) result.skipped.push(noBytes === 1 ? "1 photo isn't in the file" : `${noBytes} photos aren't in the file`);
      await db.photos.bulkAdd(photoRows);
      await db.photo_blobs.bulkPut(blobRows);
      result.added.photos = photoRows.length;
      result.kept.photos = plan.photos.keep.length;

      // ---- settings and AI choices (only when ticked; keys are never in a backup)
      const arrived = Object.values(result.added).some((n) => n > 0) || Object.values(result.replaced).some((n) => n > 0);
      const cur = withSettingsDefaults((await db.kv.get(KV.settings))?.value);
      let next: Settings = cur;
      if (opts.mode === "replace") {
        // The rows those ids pointed at were just deleted.
        if (!newBeanIds.includes(cur.defaultBeanId)) next = { ...next, defaultBeanId: 0 };
        if (!newGrinderIds.includes(cur.defaultGrinderId)) next = { ...next, defaultGrinderId: 0 };
      }
      if (opts.bringSettings && doc.settings) {
        next = {
          ...next,
          ...settingsPatchFromDto(doc.settings, (uid) => beanIds.get(uid) ?? null, (uid) => grinderIds.get(uid) ?? null),
        };
      }
      // Someone who brought data has used the app: no onboarding (A§6 step 1b). Setup was skipped
      // for them, not by them, so Today offers the "Personalise" card (A§7.8).
      if (arrived && !next.onboardingDone) next = { ...next, onboardingDone: true, onboardingAuto: true };
      if (next !== cur) await db.kv.put({ key: KV.settings, value: next });

      if (opts.bringAi && doc.ai) {
        const ai = withAiDefaults((await db.kv.get(KV.ai))?.value);
        await db.kv.put({ key: KV.ai, value: aiPrefsFromDto(doc.ai, ai) });
      }
    });
  } catch (e) {
    throw new ImportError(isQuota(e) ? "storageFull" : "failed", e);
  }

  // Outside the transaction: neither step may undo the import if it fails. Only 1.x rows are
  // linked: a v2 row with no catalog id was saved that way on purpose (a custom name), and
  // Android has already linked everything it could (A§6).
  try {
    if (read.preview?.kind === "legacy1") await linkAll(written);
  } catch (e) {
    console.warn("Catalog linking after import failed", e);
  }
  void persistOnce();
  return result;
}
