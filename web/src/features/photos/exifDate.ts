/**
 * A small JPEG APP1 (Exif) reader for one tag: DateTimeOriginal (0x9003), so a photo keeps the
 * time it was taken after re-encoding drops every EXIF field (location, camera). Pure; reads the
 * first 128 KB at most. Also answers "does this JPEG still carry an APP1 segment?" for the check
 * that stored photos are stripped.
 */

const SOI = 0xffd8;
const APP1 = 0xffe1;
const SOS = 0xffda;
const TAG_EXIF_IFD = 0x8769;
const TAG_DATE_ORIGINAL = 0x9003;
const TYPE_ASCII = 2;

/** Bytes worth reading from a file: APP1 sits at the front and is at most 64 KB. */
export const EXIF_SCAN_BYTES = 128 * 1024;

function u16(d: DataView, o: number, le: boolean): number {
  return d.getUint16(o, le);
}
function u32(d: DataView, o: number, le: boolean): number {
  return d.getUint32(o, le);
}

/** Every marker segment before the image data: [marker, payloadStart, payloadLength]. */
function* segments(bytes: Uint8Array): Generator<[number, number, number]> {
  const d = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes.length < 4 || d.getUint16(0) !== SOI) return;
  let o = 2;
  while (o + 4 <= bytes.length) {
    if (bytes[o] !== 0xff) return;
    const marker = d.getUint16(o);
    // Fill bytes and standalone markers (RSTn, TEM) carry no length.
    if (marker === 0xffff) {
      o += 1;
      continue;
    }
    if (marker === SOS || marker === 0xffd9) return;
    const len = d.getUint16(o + 2);
    if (len < 2) return;
    yield [marker, o + 4, len - 2];
    o += 2 + len;
  }
}

/** True when the JPEG has an APP1 segment (Exif or XMP) before its image data. */
export function hasApp1(bytes: Uint8Array): boolean {
  for (const [marker] of segments(bytes)) if (marker === APP1) return true;
  return false;
}

/** "2026:11:17 08:14:05" → local epoch ms, or null. */
export function parseExifDateTime(s: string): number | null {
  const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(s.trim());
  if (!m) return null;
  const [y, mo, d, h, mi, se] = m.slice(1).map(Number) as [number, number, number, number, number, number];
  if (y < 1900 || mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || se > 60) return null;
  const t = new Date(y, mo - 1, d, h, mi, se).getTime();
  return Number.isNaN(t) ? null : t;
}

/** DateTimeOriginal as local epoch ms, or null when absent or unreadable. Never throws. */
export function exifDateTimeOriginal(bytes: Uint8Array): number | null {
  try {
    for (const [marker, start, length] of segments(bytes)) {
      if (marker !== APP1 || length < 14) continue;
      // "Exif\0\0"
      if (bytes[start] !== 0x45 || bytes[start + 1] !== 0x78 || bytes[start + 2] !== 0x69 || bytes[start + 3] !== 0x66) continue;
      const tiff = start + 6;
      const end = Math.min(bytes.length, start + length);
      const d = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      const order = d.getUint16(tiff);
      const le = order === 0x4949;
      if (!le && order !== 0x4d4d) return null;
      if (u16(d, tiff + 2, le) !== 42) return null;
      const ifd0 = tiff + u32(d, tiff + 4, le);
      const exifIfd = findTag(d, tiff, ifd0, end, TAG_EXIF_IFD, le);
      if (exifIfd == null) return null;
      const entry = findEntry(d, tiff + exifIfd.value, end, TAG_DATE_ORIGINAL, le);
      if (!entry || entry.type !== TYPE_ASCII || entry.count < 19) return null;
      const at = entry.count <= 4 ? entry.at + 8 : tiff + u32(d, entry.at + 8, le);
      if (at + 19 > end) return null;
      let s = "";
      for (let i = 0; i < 19; i++) s += String.fromCharCode(bytes[at + i]!);
      return parseExifDateTime(s);
    }
  } catch {
    // A malformed header is the same as no date.
  }
  return null;
}

interface Entry {
  at: number;
  type: number;
  count: number;
}

function findEntry(d: DataView, ifd: number, end: number, tag: number, le: boolean): Entry | null {
  if (ifd + 2 > end) return null;
  const n = u16(d, ifd, le);
  for (let i = 0; i < n; i++) {
    const at = ifd + 2 + i * 12;
    if (at + 12 > end) return null;
    if (u16(d, at, le) === tag) return { at, type: u16(d, at + 2, le), count: u32(d, at + 4, le) };
  }
  return null;
}

function findTag(d: DataView, _tiff: number, ifd: number, end: number, tag: number, le: boolean): { value: number } | null {
  const e = findEntry(d, ifd, end, tag, le);
  return e ? { value: u32(d, e.at + 8, le) } : null;
}
