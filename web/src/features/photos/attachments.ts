/**
 * The AI hook (A§9.3, web-spec §13): a brew's bed and cup photos that are ticked "Send with
 * analyses", newest 3, as base64 of the stored full JPEG (already ≤ 1024 px), dropping the
 * oldest while the total base64 is over 4 MB. Labels read "bed, 08:14".
 */
import { repo } from "@/data/repo";
import type { PhotoRow } from "@/data/schema";
import { timeLabel } from "@/domain/core/format";

/** One image for an analysis: base64 of the stored JPEG and a label like "bed, 08:14". */
export interface AiImage {
  mediaType: "image/jpeg";
  base64: string;
  label: string;
}

export const MAX_ATTACHMENTS = 3;
export const MAX_BASE64_CHARS = 4 * 1024 * 1024;

/** Which photos qualify, newest first, at most three. */
export function eligiblePhotos(photos: readonly PhotoRow[]): PhotoRow[] {
  return photos
    .filter((p) => p.sendToAi && (p.kind === "bed" || p.kind === "cup"))
    .sort((a, b) => b.takenAt - a.takenAt || b.createdAt - a.createdAt)
    .slice(0, MAX_ATTACHMENTS);
}

/** Drops the oldest until the total fits. `items` newest first; returns oldest first (as taken). */
export function trimToBudget<T extends { takenAt: number; base64: string }>(items: readonly T[], budget = MAX_BASE64_CHARS): T[] {
  const kept = [...items].sort((a, b) => b.takenAt - a.takenAt);
  let total = kept.reduce((n, x) => n + x.base64.length, 0);
  while (kept.length > 0 && total > budget) {
    const oldest = kept.pop()!;
    total -= oldest.base64.length;
  }
  return kept.reverse();
}

export function attachmentLabel(p: Pick<PhotoRow, "kind" | "takenAt">): string {
  return `${p.kind}, ${timeLabel(p.takenAt)}`;
}

function base64Of(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => {
      const s = typeof r.result === "string" ? r.result : "";
      resolve(s.slice(s.indexOf(",") + 1));
    };
    r.onerror = () => reject(r.error ?? new Error("read failed"));
    r.readAsDataURL(blob);
  });
}

export async function photoAttachmentsForBrew(brewId: number): Promise<AiImage[]> {
  const chosen = eligiblePhotos(await repo.photosForBrew(brewId));
  const loaded: { takenAt: number; base64: string; label: string }[] = [];
  for (const p of chosen) {
    const blob = await repo.photoBlob(p.uid, "full");
    if (!blob) continue;
    loaded.push({ takenAt: p.takenAt, base64: await base64Of(blob), label: attachmentLabel(p) });
  }
  return trimToBudget(loaded).map((x) => ({ mediaType: "image/jpeg", base64: x.base64, label: x.label }));
}
