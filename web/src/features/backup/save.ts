/**
 * Hands a built backup to the person (web-spec §15.4): the system save dialog where the browser
 * has one (Chromium), a share sheet in an installed iPhone/iPad app (where a download link goes
 * nowhere useful), else an ordinary download.
 */
import { isStandalone } from "@/data/storage";
import { MAX_IMPORT_BYTES } from "@/domain/backup/model";
import { backupFileName, buildBackup, type BuiltBackup } from "./exporter";
import { markBackupSaved } from "./meta";

interface FileHandle {
  createWritable(): Promise<{ write(data: Blob): Promise<void>; close(): Promise<void> }>;
}
type SavePicker = (o: { suggestedName: string; types: { description: string; accept: Record<string, string[]> }[] }) => Promise<FileHandle>;

export type SaveOutcome = { saved: true; built: BuiltBackup } | { saved: false };

/**
 * Counts as the last backup only when the file can be restored: one over the import limit (a
 * log too big even without its photos) must not quieten the "save a backup" reminder.
 */
async function markIfRestorable(built: BuiltBackup): Promise<void> {
  if (built.blob.size <= MAX_IMPORT_BYTES) await markBackupSaved();
}

function isAbort(e: unknown): boolean {
  return (e as { name?: unknown } | null)?.name === "AbortError";
}

function download(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.rel = "noopener";
  document.body.append(a);
  a.click();
  a.remove();
  // The download has started by now; give slow browsers a while before the URL goes.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function isAppleMobile(): boolean {
  return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
}

/**
 * Must be called from the tap itself: the save dialog needs that gesture, so it opens first and
 * the file is built while it is up.
 */
export async function saveBackup(o: { includePhotos: boolean; includeAnalyses: boolean }): Promise<SaveOutcome> {
  const picker = (window as unknown as { showSaveFilePicker?: SavePicker }).showSaveFilePicker;
  if (typeof picker === "function") {
    let handle: FileHandle | null = null;
    try {
      handle = await picker.call(window, {
        suggestedName: backupFileName(),
        types: [{ description: "Coffee backup", accept: { "application/json": [".json"] } }],
      });
    } catch (e) {
      if (isAbort(e)) return { saved: false };
      handle = null; // Blocked (e.g. a sandboxed frame): fall back to a download.
    }
    if (handle) {
      const built = await buildBackup(o);
      const w = await handle.createWritable();
      await w.write(built.blob);
      await w.close();
      await markIfRestorable(built);
      return { saved: true, built };
    }
  }

  const built = await buildBackup(o);
  if (isStandalone() && isAppleMobile() && typeof navigator.canShare === "function") {
    const file = new File([built.blob], built.fileName, { type: "application/json" });
    if (navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ files: [file] });
        await markIfRestorable(built);
        return { saved: true, built };
      } catch (e) {
        if (isAbort(e)) return { saved: false };
        // NotAllowedError (the gesture expired while building): a download still works.
      }
    }
  }
  download(built.blob, built.fileName);
  await markIfRestorable(built);
  return { saved: true, built };
}
