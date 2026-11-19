/**
 * Writes a backup v2 file (web-spec §15.4). Everything is read in one IndexedDB transaction so
 * the file is a consistent snapshot. The document is built as Blob parts: the envelope up to
 * "photos": [, then one part per photo (base64 made in the worker, one photo at a time), then the
 * closing brackets, so no single giant string ever exists. Never reads Web Storage, so an API
 * key cannot end up in a file.
 */
import { db } from "@/data/db";
import { DB_VERSION } from "@/data/db";
import { KV, withAiDefaults, withSettingsDefaults } from "@/data/settings";
import { encode } from "@/domain/backup/codec";
import { MAX_IMPORT_BYTES, type BackupDoc, type Counts } from "@/domain/backup/model";
import { snapshotToDoc } from "@/domain/backup/rows";
import { blobToBase64 } from "./workerClient";

export interface BuiltBackup {
  blob: Blob;
  fileName: string;
  counts: Counts;
  /** Photos listed without their image because the file would have been too big to restore. */
  photosLeftOut: number;
}

/**
 * The biggest file this writer makes. Both apps refuse to read more than MAX_IMPORT_BYTES, so a
 * backup over it could never be restored; the margin covers the estimate being a little off.
 */
export const MAX_BACKUP_BYTES = MAX_IMPORT_BYTES - 2 * 1024 * 1024;

/** Bytes a stored photo takes in the file as base64. */
export const base64Size = (bytes: number): number => Math.ceil(bytes / 3) * 4;

/**
 * Which photos keep their image when not all fit in `budget` bytes: the newest first, and a
 * photo that does not fit is passed over so a smaller, older one still can. Pure.
 */
export function photosThatFit(photos: readonly { uid: string; createdAt: string; bytes: number }[], budget: number): Set<string> {
  const keep = new Set<string>();
  let left = budget;
  for (const p of [...photos].sort((a, b) => b.createdAt.localeCompare(a.createdAt))) {
    if (p.bytes > left) continue;
    left -= p.bytes;
    keep.add(p.uid);
  }
  return keep;
}

const pad2 = (n: number) => String(n).padStart(2, "0");

/** coffee-backup-YYYY-MM-DD.json, the local calendar date. */
export function backupFileName(now = Date.now()): string {
  const d = new Date(now);
  return `coffee-backup-${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}.json`;
}

function appInfo(): BackupDoc["app"] {
  return {
    platform: "web",
    appVersion: typeof __APP_VERSION__ === "string" ? __APP_VERSION__ : "2.0.0",
    packageName: typeof location === "undefined" ? "" : location.host,
    dbVersion: DB_VERSION,
  };
}

/** Builds the document with photo data still null, from one consistent snapshot. */
export async function snapshotDoc(o: { includePhotos: boolean; includeAnalyses: boolean; now?: number }): Promise<BackupDoc> {
  const snap = await db.transaction("r", [db.beans, db.grinders, db.brew_logs, db.saved_recipes, db.analyses, db.photos, db.kv], async () => {
    const [beans, grinders, brews, savedRecipes, analyses, photos, s, ai] = await Promise.all([
      db.beans.toArray(),
      db.grinders.toArray(),
      db.brew_logs.toArray(),
      db.saved_recipes.toArray(),
      db.analyses.toArray(),
      db.photos.toArray(),
      db.kv.get(KV.settings),
      db.kv.get(KV.ai),
    ]);
    return { beans, grinders, brews, savedRecipes, analyses, photos, settings: withSettingsDefaults(s?.value), aiPrefs: withAiDefaults(ai?.value) };
  });
  return snapshotToDoc(snap, {
    exportedAt: o.now ?? Date.now(),
    app: appInfo(),
    includesPhotos: o.includePhotos,
    includesAnalyses: o.includeAnalyses,
  });
}

const PHOTOS_EMPTY = '"photos": []';

/** Indents a pretty-printed record to sit inside the top-level "photos" array. */
function indent(json: string, by: string): string {
  return by + json.replace(/\n/g, "\n" + by);
}

export async function buildBackup(o: { includePhotos: boolean; includeAnalyses: boolean; now?: number; maxBytes?: number }): Promise<BuiltBackup> {
  const now = o.now ?? Date.now();
  const doc = await snapshotDoc({ ...o, now });
  const counts: Counts = {
    brews: doc.brews.length,
    beans: doc.beans.length,
    grinders: doc.grinders.length,
    recipes: doc.recipes.length,
    analyses: doc.analyses.length,
    photos: doc.photos.length,
  };
  const type = "application/json";
  if (!o.includePhotos || doc.photos.length === 0) {
    return { blob: new Blob([encode(doc)], { type }), fileName: backupFileName(now), counts, photosLeftOut: 0 };
  }
  const photos = doc.photos;
  const envelope = encode({ ...doc, photos: [] });
  const at = envelope.lastIndexOf(PHOTOS_EMPTY);
  const head = envelope.slice(0, at) + '"photos": [\n';
  const tail = "\n  ]" + envelope.slice(at + PHOTOS_EMPTY.length);

  // Blobs come back from IndexedDB as handles, so holding them all costs nothing until one is read.
  const stored = new Map<string, Blob>();
  const records = new Map<string, string>();
  for (const p of photos) {
    const row = await db.photo_blobs.get(p.uid);
    if (row) stored.set(p.uid, row.full);
    records.set(p.uid, indent(JSON.stringify({ ...p, data: null }, null, 2), "    "));
  }
  // Every record is written either way; only the images compete for the room that is left.
  const fixed = new Blob([head, tail, ...records.values()]).size + photos.length * 2;
  const keep = photosThatFit(
    photos.flatMap((p) => {
      const blob = stored.get(p.uid);
      return blob ? [{ uid: p.uid, createdAt: p.createdAt, bytes: base64Size(blob.size) }] : [];
    }),
    (o.maxBytes ?? MAX_BACKUP_BYTES) - fixed,
  );

  const parts: BlobPart[] = [head];
  let written = 0;
  for (const p of photos) {
    const blob = keep.has(p.uid) ? stored.get(p.uid) : undefined;
    // A row whose bytes are gone, or that no longer fits, is written with data null rather than dropped.
    const data = blob ? await blobToBase64(blob) : null;
    parts.push((written++ > 0 ? ",\n" : "") + (data == null ? records.get(p.uid)! : indent(JSON.stringify({ ...p, data }, null, 2), "    ")));
  }
  parts.push(tail);
  return { blob: new Blob(parts, { type }), fileName: backupFileName(now), counts, photosLeftOut: stored.size - keep.size };
}

// ---------------------------------------------------------------- size estimate

export interface SizeEstimate {
  withoutPhotos: number;
  withPhotos: number;
  photoCount: number;
}

/**
 * "About 14 MB with photos", shown before saving. The per-record sizes are the app's own rule of
 * thumb from pretty-printed sample records, not a measurement; photos are their stored bytes
 * times 4/3 for base64.
 */
export async function estimateBackupBytes(): Promise<SizeEstimate> {
  const [brews, beans, grinders, recipes, analyses, photos] = await Promise.all([
    db.brew_logs.count(), db.beans.count(), db.grinders.count(), db.saved_recipes.count(), db.analyses.toArray(), db.photos.toArray(),
  ]);
  const analysisBytes = analyses.reduce((sum, a) => sum + 900 + a.rawText.length * 1.1, 0);
  const base = 1500 + brews * 1400 + beans * 1000 + grinders * 600 + recipes * 2500 + analysisBytes + photos.length * 350;
  const photoBytes = photos.reduce((sum, p) => sum + Math.ceil(p.bytes / 3) * 4, 0);
  return { withoutPhotos: Math.round(base), withPhotos: Math.round(base + photoBytes), photoCount: photos.length };
}

/** "about 14 MB", "about 120 KB" (1 MB = 1,000,000 bytes, as phones show file sizes). */
export function aboutSize(bytes: number): string {
  if (bytes >= 1_000_000) return `about ${bytes >= 10_000_000 ? Math.round(bytes / 1_000_000) : (bytes / 1_000_000).toFixed(1).replace(/\.0$/, "")} MB`;
  return `about ${Math.max(1, Math.round(bytes / 1000))} KB`;
}
