/**
 * Text in, a decoded document out (web-spec §15.2). The backup worker runs this off the main
 * thread; tests and browsers without module workers call it directly. Photo base64 is turned
 * into Blobs here and dropped from the document, so the main thread never holds the big strings.
 *
 * A photo is stored only as what the apps themselves make: a JPEG of at most 1024 px with no
 * EXIF. Bytes already in that shape are kept exactly (a backup round-trips bit for bit); anything
 * else goes to the `reencode` step the worker supplies, and is left out when that cannot make a
 * picture of it. Nothing from a file reaches photo storage, or an AI service, unchecked.
 */
import { decodeV2, detectValue, parseBackupText } from "./codec";
import { isStoredPhotoShape } from "./jpeg";
import { readLegacy, type RecipeRef } from "./legacy";
import { countsOf, type BackupDoc, type Counts } from "./model";

export type ReadError = "notJson" | "notBackup" | "tooNew" | "tooBig" | "failed";

export interface ReadPreview {
  kind: "v2" | "legacy1";
  counts: Counts;
  platform: string;
  packageName: string;
  appVersion: string;
  /** ISO instant, "" when the file had none. */
  exportedAt: string;
  /** Photos whose bytes are in the file. */
  photosWithData: number;
  problems: string[];
}

export interface PhotoBytes {
  full: Blob;
  /** Null when this browser could not draw one; the full image stands in. */
  thumb: Blob | null;
  /** Set when the photo was drawn again on the way in: its size now, not what the file said. */
  size?: { widthPx: number; heightPx: number };
}

export type ReadResult =
  | { ok: true; doc: BackupDoc; photoBytes: (PhotoBytes | null)[]; preview: ReadPreview }
  | { ok: false; error: ReadError };

export function base64ToBytes(b64: string): Uint8Array<ArrayBuffer> | null {
  try {
    const bin = atob(b64.replace(/\s+/g, ""));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

/** Chunked so a 1 MB photo never builds a 1-million-argument call. */
export function bytesToBase64(bytes: Uint8Array): string {
  const chunks: string[] = [];
  const size = 0x8000;
  for (let i = 0; i < bytes.length; i += size) {
    chunks.push(String.fromCharCode(...bytes.subarray(i, i + size)));
  }
  return btoa(chunks.join(""));
}

/** The canvas work only a worker (or a browser) can do; both default to "can't". */
export interface PhotoTools {
  /** A 256 px thumbnail of an already clean photo; null when it cannot be drawn. */
  makeThumb?: (full: Blob) => Promise<Blob | null>;
  /**
   * Decodes whatever image this is and writes it again as the apps store photos (≤ 1024 px JPEG,
   * which drops every EXIF field). Null when it is not a picture this browser can open.
   */
  reencode?: (original: Blob) => Promise<PhotoBytes | null>;
}

export async function readBackupText(text: string, builtIns: readonly RecipeRef[], tools: PhotoTools = {}): Promise<ReadResult> {
  const makeThumb = tools.makeThumb ?? (() => Promise.resolve(null));
  const reencode = tools.reencode ?? (() => Promise.resolve(null));
  const parsed = parseBackupText(text);
  if (!parsed.ok) return { ok: false, error: "notJson" };
  const kind = detectValue(parsed.value);
  if (kind === "tooNew") return { ok: false, error: "tooNew" };
  if (kind === "unknown") return { ok: false, error: "notBackup" };
  const { doc, problems } = kind === "v2" ? decodeV2(parsed.value) : await readLegacy(parsed.value, builtIns);

  const photoBytes: (PhotoBytes | null)[] = [];
  for (const [i, p] of doc.photos.entries()) {
    if (p.data == null) {
      photoBytes.push(null);
      continue;
    }
    const bytes = base64ToBytes(p.data);
    p.data = null;
    if (!bytes || bytes.length === 0) {
      problems.push(`photos[${i}]: unreadable image data`);
      photoBytes.push(null);
      continue;
    }
    if (isStoredPhotoShape(bytes)) {
      const full = new Blob([bytes], { type: "image/jpeg" });
      photoBytes.push({ full, thumb: await makeThumb(full).catch(() => null) });
      continue;
    }
    // Too big, carrying EXIF, or not a JPEG at all: store a clean copy or nothing.
    const clean = await reencode(new Blob([bytes])).catch(() => null);
    if (!clean) problems.push(`photos[${i}]: not a photo this app can store`);
    photoBytes.push(clean);
  }

  return {
    ok: true,
    doc,
    photoBytes,
    preview: {
      kind,
      counts: countsOf(doc),
      platform: doc.app.platform,
      packageName: doc.app.packageName,
      appVersion: doc.app.appVersion,
      exportedAt: doc.exportedAt,
      photosWithData: photoBytes.filter((b) => b != null).length,
      problems,
    },
  };
}
