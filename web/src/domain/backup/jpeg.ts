/**
 * What a photo arriving in a backup really is. Both apps store photos as JPEGs with a long edge
 * of at most 1024 px and no EXIF (A§9.1, web-spec §13), and analyses send the stored bytes
 * as they are. A backup file is only text, though: it may have been merged by hand or written by
 * something else, so its photos are looked at before they are stored. Pure.
 */

/** Long edge of a stored photo, as both capture pipelines make it. */
export const STORED_EDGE_PX = 1024;
/** Far above any 1024 px JPEG at quality 0.82 (those are a few hundred KB). */
export const MAX_STORED_PHOTO_BYTES = 4 * 1024 * 1024;

export interface JpegInfo {
  width: number;
  height: number;
  /** An APP1 (Exif, XMP) or APP13 (IPTC) segment: where location and camera details live. */
  hasMetadata: boolean;
}

const APP1 = 0xe1;
const APP13 = 0xed;
const SOS = 0xda;
const EOI = 0xd9;

/** Start-of-frame markers carry the size: C0–CF except DHT (C4), JPG (C8) and DAC (CC). */
const isSof = (m: number) => m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc;

/** Reads a JPEG's header segments; null when the bytes are not a JPEG with a frame header. */
export function inspectJpeg(bytes: Uint8Array): JpegInfo | null {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  let hasMetadata = false;
  let o = 2;
  while (o + 4 <= bytes.length) {
    if (bytes[o] !== 0xff) return null;
    const marker = bytes[o + 1]!;
    // Fill bytes before a marker.
    if (marker === 0xff) {
      o += 1;
      continue;
    }
    if (marker === SOS || marker === EOI) return null; // image data before any frame header
    const len = (bytes[o + 2]! << 8) | bytes[o + 3]!;
    if (len < 2) return null;
    if (marker === APP1 || marker === APP13) hasMetadata = true;
    if (isSof(marker)) {
      if (o + 9 > bytes.length) return null;
      const height = (bytes[o + 5]! << 8) | bytes[o + 6]!;
      const width = (bytes[o + 7]! << 8) | bytes[o + 8]!;
      if (width <= 0 || height <= 0) return null;
      // Metadata segments come before the frame header in every writer; one after it still counts.
      return { width, height, hasMetadata: hasMetadata || metadataAfter(bytes, o + 2 + len) };
    }
    o += 2 + len;
  }
  return null;
}

function metadataAfter(bytes: Uint8Array, from: number): boolean {
  let o = from;
  while (o + 4 <= bytes.length && bytes[o] === 0xff) {
    const marker = bytes[o + 1]!;
    if (marker === 0xff) {
      o += 1;
      continue;
    }
    if (marker === SOS || marker === EOI) return false;
    if (marker === APP1 || marker === APP13) return true;
    const len = (bytes[o + 2]! << 8) | bytes[o + 3]!;
    if (len < 2) return false;
    o += 2 + len;
  }
  return false;
}

/**
 * True when the bytes can be stored exactly as they are: a JPEG no bigger than the apps make,
 * with nothing in it that says where or with what it was taken.
 */
export function isStoredPhotoShape(bytes: Uint8Array): boolean {
  if (bytes.length > MAX_STORED_PHOTO_BYTES) return false;
  const info = inspectJpeg(bytes);
  return info != null && !info.hasMetadata && Math.max(info.width, info.height) <= STORED_EDGE_PX;
}
