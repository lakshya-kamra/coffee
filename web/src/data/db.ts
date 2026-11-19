/**
 * The Dexie database (web-spec §8.1): Room v4's tables mirrored, plus two web-only tables
 * (photo_blobs for image bytes, kv for settings / AI prefs / the active brew). Booleans are not
 * valid IndexedDB keys, so isArchived / isDefault / isFavourite are filtered in memory.
 */
import { Dexie, type EntityTable, type Table } from "dexie";
import type { AnalysisRow, BeanRow, BrewRow, GrinderRow, KvRow, PhotoBlobRow, PhotoRow, SavedRecipeRow } from "./schema";

export const DB_NAME = "coffee";
/** Written into backups as app.dbVersion (readers never branch on it across platforms). */
export const DB_VERSION = 1;

export class CoffeeDb extends Dexie {
  beans!: EntityTable<BeanRow, "id">;
  grinders!: EntityTable<GrinderRow, "id">;
  brew_logs!: EntityTable<BrewRow, "id">;
  saved_recipes!: Table<SavedRecipeRow, string>;
  analyses!: EntityTable<AnalysisRow, "id">;
  photos!: EntityTable<PhotoRow, "id">;
  photo_blobs!: Table<PhotoBlobRow, string>;
  kv!: Table<KvRow, string>;

  constructor(name = DB_NAME) {
    super(name);
    this.version(DB_VERSION).stores({
      beans: "++id, &uid, updatedAt",
      grinders: "++id, &uid, catalogId",
      brew_logs: "++id, &uid, startedAt, beanId, grinderId, recipeId, updatedAt",
      saved_recipes: "id, updatedAt",
      analyses: "++id, &uid, brewLogId, [kind+createdAt]",
      photos: "++id, &uid, brewLogId, beanId",
      photo_blobs: "uid",
      kv: "key",
    });
  }
}

export const db = new CoffeeDb();
