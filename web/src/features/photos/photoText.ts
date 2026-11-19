/** Words for photos, shared by the strip and the viewer. */
import type { PhotoKind, PhotoRow } from "@/data/schema";
import { timeLabel } from "@/domain/core/format";

export const KIND_LABELS: Readonly<Record<PhotoKind, string>> = { bed: "Bed", cup: "Cup", bag: "Bag", other: "Other" };

/** "Bed photo, 08:14" (+ caption). */
export function photoAlt(p: Pick<PhotoRow, "kind" | "takenAt" | "caption">): string {
  const base = `${KIND_LABELS[p.kind]} photo, ${timeLabel(p.takenAt)}`;
  return p.caption.trim() ? `${base}. ${p.caption.trim()}` : base;
}
