/**
 * Storage durability (web-spec §8.5). Browsers may clear a site's storage; persist() asks them
 * not to. Safari can delete script-written storage for a site unopened for 7 days unless it is
 * on the Home Screen, so the UI says so plainly.
 */

let persistAsked = false;

/** Ask once per page, after the first saved brew or photo. */
export async function persistOnce(): Promise<boolean> {
  if (persistAsked) return false;
  persistAsked = true;
  try {
    if (!navigator.storage?.persist) return false;
    if (await navigator.storage.persisted()) return true;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}

export async function requestPersist(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}

export interface StorageStatus {
  readonly usageBytes: number | null;
  readonly quotaBytes: number | null;
  readonly persisted: boolean;
}

export async function storageStatus(): Promise<StorageStatus> {
  try {
    const [est, persisted] = await Promise.all([
      navigator.storage?.estimate?.() ?? Promise.resolve(undefined),
      navigator.storage?.persisted?.() ?? Promise.resolve(false),
    ]);
    return { usageBytes: est?.usage ?? null, quotaBytes: est?.quota ?? null, persisted };
  } catch {
    return { usageBytes: null, quotaBytes: null, persisted: false };
  }
}

export function isStandalone(): boolean {
  return typeof matchMedia === "function" && matchMedia("(display-mode: standalone)").matches;
}

/** Safari (macOS or iOS), not Chrome/Firefox/Edge on iOS which also say "Safari" in the UA. */
export function isSafari(ua: string = typeof navigator === "undefined" ? "" : navigator.userAgent): boolean {
  return /Safari\//.test(ua) && !/(Chrome|Chromium|CriOS|FxiOS|EdgiOS|Edg|Android)\//.test(ua);
}

export const SAFARI_EVICTION_NOTE =
  "Safari can clear this site's data if you don't open it for a week. Add Coffee to your Home Screen, or save a backup now and then.";

/** The Safari note shows when not installed and not persisted. */
export function shouldShowSafariNote(status: StorageStatus): boolean {
  return isSafari() && !isStandalone() && !status.persisted;
}

/** Backup nudge on Today: ≥ 10 brews, no backup in 30 days, storage not persisted, once per 30 days. */
export function shouldNudgeBackup(p: { brewCount: number; lastBackupAt: number | null; lastNudgeAt: number | null; persisted: boolean; now?: number }): boolean {
  const now = p.now ?? Date.now();
  const days30 = 30 * 86_400_000;
  if (p.brewCount < 10 || p.persisted) return false;
  if (p.lastBackupAt != null && now - p.lastBackupAt < days30) return false;
  if (p.lastNudgeAt != null && now - p.lastNudgeAt < days30) return false;
  return true;
}
