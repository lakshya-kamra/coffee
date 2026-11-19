/**
 * When this browser last saved a backup, for the "last saved" line and Today's backup nudge
 * (web-spec §8.5). Kept in the kv table under its own key, so "Delete everything" clears it too.
 */
import { db } from "@/data/db";

const KEY = "backup";

interface BackupMeta {
  lastBackupAt: number | null;
}

export async function lastBackupAt(): Promise<number | null> {
  const v = (await db.kv.get(KEY))?.value as Partial<BackupMeta> | undefined;
  return typeof v?.lastBackupAt === "number" ? v.lastBackupAt : null;
}

export async function markBackupSaved(at = Date.now()): Promise<void> {
  await db.kv.put({ key: KEY, value: { lastBackupAt: at } satisfies BackupMeta });
}
