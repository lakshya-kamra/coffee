/**
 * Words for YOUR DATA and the import sheet (web-spec §15.4, A§10.2). Plain, second person,
 * British spelling, no exclamation marks. Pure, so tests pin the exact sentences.
 */
import { groupedInt } from "@/domain/core/format";
import { MAX_IMPORT_BYTES, type Counts } from "@/domain/backup/model";
import type { Status } from "@/domain/core/status";
import type { ReadError, ReadPreview } from "@/domain/backup/read";
import type { ImportResult } from "./apply";

const NOUNS: readonly [keyof Counts, string, string][] = [
  ["brews", "brew", "brews"],
  ["beans", "coffee", "coffees"],
  ["grinders", "grinder", "grinders"],
  ["recipes", "recipe", "recipes"],
  ["analyses", "analysis", "analyses"],
  ["photos", "photo", "photos"],
];

/** "52 brews, 4 coffees, 1 grinder" — zero counts left out. */
export function countList(c: Counts): string {
  return NOUNS.filter(([k]) => c[k] > 0)
    .map(([k, one, many]) => `${groupedInt(c[k])} ${c[k] === 1 ? one : many}`)
    .join(", ");
}

const total = (c: Counts) => c.brews + c.beans + c.grinders + c.recipes + c.analyses + c.photos;

const DATE = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });

/** "17 Nov 2026" from an ISO instant, in this browser's time zone. */
export function shortDate(isoInstant: string): string {
  const ms = Date.parse(isoInstant);
  return Number.isNaN(ms) ? "" : DATE.format(ms);
}

function madeOn(p: ReadPreview): string {
  if (p.kind === "legacy1") return "a 1.x export from Android";
  if (p.platform === "web") return p.packageName ? `made on the web (${p.packageName})` : "made on the web";
  if (p.platform === "android") return p.packageName ? `made on Android (${p.packageName})` : "made on Android";
  return p.platform ? `made on ${p.platform}` : "made by Coffee";
}

/** "Found 52 brews, 4 coffees, 1 grinder · made on Android (com.laksh.coffee.debug) on 17 Nov 2026" */
export function previewLine(p: ReadPreview): string {
  const found = total(p.counts) > 0 ? `Found ${countList(p.counts)}` : "This backup is empty";
  const date = shortDate(p.exportedAt);
  return `${found} · ${madeOn(p)}${date ? ` on ${date}` : ""}`;
}

/** Notes under the preview: skipped records and photos saved without their images. */
export function previewNotes(p: ReadPreview): string[] {
  const notes: string[] = [];
  const missing = p.counts.photos - p.photosWithData;
  if (missing > 0 && p.photosWithData === 0) notes.push("This backup was saved without photos, so none will come across.");
  else if (missing > 0) notes.push(`${groupedInt(missing)} ${missing === 1 ? "photo isn't" : "photos aren't"} in the file and will be left out.`);
  if (p.problems.length > 0) {
    const n = p.problems.length;
    notes.push(`${groupedInt(n)} ${n === 1 ? "record" : "records"} in the file couldn't be read and will be left out.`);
  }
  if (p.kind === "legacy1") notes.push("Older exports have no saved analyses, recipe edits or photos. Everything else comes across.");
  return notes;
}

/** "Added 52 brews · 0 already here" (plus "· 3 updated" when newer copies replaced older ones). */
export function resultLine(r: ImportResult): string {
  const added = total(r.added);
  const updated = total(r.replaced);
  const kept = total(r.kept);
  const parts = [added > 0 ? `Added ${countList(r.added)}` : "Nothing new"];
  if (updated > 0) parts.push(`${groupedInt(updated)} updated`);
  parts.push(`${groupedInt(kept)} already here`);
  return parts.join(" · ");
}

export function readErrorText(e: ReadError): string {
  switch (e) {
    case "notJson":
      return "This file isn't a backup. Pick the .json file Coffee saved.";
    case "notBackup":
      return "This file isn't a Coffee backup.";
    case "tooNew":
      return "This backup was made by a newer version of Coffee. Update Coffee and try again.";
    case "tooBig":
      return "Too big to import here. Save the backup without photos and try again.";
    case "failed":
      return "This backup couldn't be read. Nothing was changed.";
  }
}

const LIMIT_MB = Math.round(MAX_IMPORT_BYTES / (1024 * 1024));

/** Under "Back up everything" when the photos cannot all go in one restorable file. */
export const PHOTOS_WONT_ALL_FIT = "More photos than one file can hold · the newest that fit are saved";

/** What a finished save says: the file, what is in it, and anything that had to stay out. */
export function savedMessage(
  fileName: string,
  counts: Counts,
  photosLeftOut: number,
  fileBytes: number,
): { status: Status; lead: string; rest: string } {
  if (fileBytes > MAX_IMPORT_BYTES) {
    return { status: "fault", lead: "Too big to restore", rest: `${fileName} was saved, but at over ${LIMIT_MB} MB Coffee can't read it back in.` };
  }
  const what = countList({ ...counts, photos: counts.photos - photosLeftOut });
  const rest = `${fileName}${what ? ` · ${what}` : ""}`;
  if (photosLeftOut <= 0) return { status: "good", lead: "Saved", rest };
  const n = groupedInt(photosLeftOut);
  return {
    status: "attention",
    lead: "Saved",
    rest: `${rest} · ${n} older ${photosLeftOut === 1 ? "photo" : "photos"} left out: a backup over ${LIMIT_MB} MB can't be restored`,
  };
}

export const STORAGE_FULL = "This browser is out of space for Coffee.";
export const IMPORT_FAILED = "The import didn't finish, so nothing was changed. Try again.";

export const DELETE_ALL_CONFIRM =
  "Delete every brew, coffee, grinder, recipe, analysis and photo in this browser? This can't be undone. Save a backup first if you might want them.";

export const REPLACE_CONFIRM =
  "Replace everything in this browser with this backup? Every brew, coffee, grinder, recipe, analysis and photo here now is deleted first. This can't be undone.";
