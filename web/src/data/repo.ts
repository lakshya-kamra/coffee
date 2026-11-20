/**
 * All writes go through here (web-spec §8.2). Stamps updatedAt on beans, grinders, brews and
 * saved recipes, and replaces Room's foreign-key cascades with explicit transactions.
 */
import { encodeRecipe, type Recipe } from "@/domain/core/recipe";
import { db } from "./db";
import { withBrewDefaults, type AnalysisRow, type BeanRow, type BrewRow, type GrinderRow, type PhotoRow, type SavedRecipeRow } from "./schema";
import { KV } from "./settings";

/** What a reload needs to resume a running brew (web-spec §12.5). */
export interface ActiveBrew {
  recipeId: string;
  startedAtWall: number;
  accumulatedMs: number;
  running: boolean;
  setup: {
    doseG: number;
    beanId: number | null;
    grinderId: number | null;
    grindClicks: number | null;
    grindSetting: string;
    tempC: number;
  };
  stepIndex: number;
  lastPourEndSec: number | null;
  bedExposedSec: number | null;
}

async function deletePhotosWhere(rows: PhotoRow[]): Promise<void> {
  if (rows.length === 0) return;
  await db.photo_blobs.bulkDelete(rows.map((p) => p.uid));
  await db.photos.bulkDelete(rows.map((p) => p.id!).filter((id) => id != null));
}

export const repo = {
  // ------------------------------------------------------------ brews
  async insertBrew(row: BrewRow): Promise<number> {
    const { id: _ignored, ...rest } = row;
    return (await db.brew_logs.add({ ...rest, updatedAt: Date.now() })) as number;
  },
  async updateBrew(row: BrewRow): Promise<void> {
    if (row.id == null) throw new Error("updateBrew needs an id");
    await db.brew_logs.put({ ...row, updatedAt: Date.now() });
  },
  /** The brew, its analyses, its photos and their image bytes, in one transaction. */
  async deleteBrew(id: number): Promise<void> {
    await db.transaction("rw", [db.brew_logs, db.analyses, db.photos, db.photo_blobs], async () => {
      await deletePhotosWhere(await db.photos.where("brewLogId").equals(id).toArray());
      await db.analyses.where("brewLogId").equals(id).delete();
      await db.brew_logs.delete(id);
    });
  },
  async toggleFavourite(id: number): Promise<void> {
    await db.transaction("rw", db.brew_logs, async () => {
      const b = await db.brew_logs.get(id);
      if (b) await db.brew_logs.put({ ...b, isFavourite: !b.isFavourite, updatedAt: Date.now() });
    });
  },
  async brewByUid(uid: string): Promise<BrewRow | undefined> {
    const row = await db.brew_logs.where("uid").equals(uid).first();
    return row && withBrewDefaults(row);
  },

  // ------------------------------------------------------------ beans
  async upsertBean(row: BeanRow): Promise<number> {
    return (await db.beans.put({ ...row, updatedAt: Date.now() })) as number;
  },
  /** The bean, its photos and their bytes. Brews keep beanId and their beanLabel snapshot, as Room. */
  async deleteBean(id: number): Promise<void> {
    await db.transaction("rw", [db.beans, db.photos, db.photo_blobs], async () => {
      await deletePhotosWhere(await db.photos.where("beanId").equals(id).toArray());
      await db.beans.delete(id);
    });
  },

  // ------------------------------------------------------------ grinders
  async upsertGrinder(row: GrinderRow): Promise<number> {
    return (await db.grinders.put({ ...row, updatedAt: Date.now() })) as number;
  },
  async setDefaultGrinder(id: number): Promise<void> {
    await db.transaction("rw", db.grinders, async () => {
      const now = Date.now();
      const all = await db.grinders.toArray();
      const changed = all.filter((g) => g.isDefault !== (g.id === id)).map((g) => ({ ...g, isDefault: g.id === id, updatedAt: now }));
      if (changed.length) await db.grinders.bulkPut(changed);
    });
  },
  async archiveGrinder(id: number, archived = true): Promise<void> {
    await db.transaction("rw", db.grinders, async () => {
      const g = await db.grinders.get(id);
      if (g) await db.grinders.put({ ...g, isArchived: archived, isDefault: archived ? false : g.isDefault, updatedAt: Date.now() });
    });
  },

  // ------------------------------------------------------------ recipes
  /** Saves the user's recipe or their edit of a built-in (same id replaces it in the merged list). */
  async saveRecipe(recipe: Recipe): Promise<void> {
    const json = { ...encodeRecipe(recipe), isBuiltIn: false };
    await db.saved_recipes.put({ id: recipe.id, recipe: json, updatedAt: Date.now() });
  },
  /** Back to the built-in original: the saved edit is deleted. */
  async resetRecipe(id: string): Promise<void> {
    await db.saved_recipes.delete(id);
  },
  async deleteRecipe(id: string): Promise<void> {
    await db.saved_recipes.delete(id);
  },

  // ------------------------------------------------------------ photos
  async addPhoto(meta: PhotoRow, full: Blob, thumb: Blob): Promise<number> {
    return db.transaction("rw", [db.photos, db.photo_blobs], async () => {
      const { id: _ignored, ...rest } = meta;
      const id = (await db.photos.add(rest)) as number;
      await db.photo_blobs.put({ uid: meta.uid, full, thumb });
      return id;
    });
  },
  async updatePhoto(row: PhotoRow): Promise<void> {
    await db.photos.put(row);
  },
  async deletePhoto(id: number): Promise<void> {
    await db.transaction("rw", [db.photos, db.photo_blobs], async () => {
      const p = await db.photos.get(id);
      if (p) await deletePhotosWhere([p]);
    });
  },
  async photosForBrew(brewId: number): Promise<PhotoRow[]> {
    return (await db.photos.where("brewLogId").equals(brewId).toArray()).sort((a, b) => a.takenAt - b.takenAt);
  },
  async photosForBean(beanId: number): Promise<PhotoRow[]> {
    return (await db.photos.where("beanId").equals(beanId).toArray()).sort((a, b) => a.takenAt - b.takenAt);
  },
  async photoBlob(uid: string, which: "full" | "thumb"): Promise<Blob | null> {
    const row = await db.photo_blobs.get(uid);
    return row ? row[which] : null;
  },

  // ------------------------------------------------------------ analyses
  async insertAnalysis(row: AnalysisRow): Promise<number> {
    const { id: _ignored, ...rest } = row;
    return (await db.analyses.add(rest)) as number;
  },
  async analysesForBrew(brewId: number): Promise<AnalysisRow[]> {
    return (await db.analyses.where("brewLogId").equals(brewId).toArray()).sort((a, b) => b.createdAt - a.createdAt);
  },
  async latestTrend(): Promise<AnalysisRow | null> {
    return (await db.analyses.where("[kind+createdAt]").between(["trend", -Infinity], ["trend", Infinity]).last()) ?? null;
  },

  // ------------------------------------------------------------ active brew (survives a reload)
  async setActiveBrew(a: ActiveBrew | null): Promise<void> {
    if (a) await db.kv.put({ key: KV.activeBrew, value: a });
    else await db.kv.delete(KV.activeBrew);
  },

  // ------------------------------------------------------------ whole-database
  /** No beans, grinders or brews (A§4.4 isEmpty). */
  async isEmpty(): Promise<boolean> {
    const [b, g, l] = await Promise.all([db.beans.count(), db.grinders.count(), db.brew_logs.count()]);
    return b + g + l === 0;
  },
  /** Deletes every row in every table, settings included. Keys are not here (keyStore removes those). */
  async wipeAll(): Promise<void> {
    await db.transaction("rw", [db.beans, db.grinders, db.brew_logs, db.saved_recipes, db.analyses, db.photos, db.photo_blobs, db.kv], async () => {
      await Promise.all([
        db.beans.clear(), db.grinders.clear(), db.brew_logs.clear(), db.saved_recipes.clear(),
        db.analyses.clear(), db.photos.clear(), db.photo_blobs.clear(), db.kv.clear(),
      ]);
    });
  },
};

/**
 * Recipe merge = Android CoffeeRepository.recipes: built-ins in catalog order with a saved row of
 * the same id replacing the original, then saved recipes whose ids are not built-in.
 */
export function mergeRecipes(builtIns: readonly Recipe[], saved: readonly Recipe[]): Recipe[] {
  const savedById = new Map(saved.map((r) => [r.id, r]));
  const builtInIds = new Set(builtIns.map((r) => r.id));
  return [...builtIns.map((r) => savedById.get(r.id) ?? r), ...saved.filter((r) => !builtInIds.has(r.id))];
}

export type { SavedRecipeRow };
