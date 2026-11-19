/**
 * Photo size maths (web-spec §13), shared by the worker and the main-thread fallback. Pure: no
 * DOM or worker globals, so both TypeScript configs can check it.
 */

/** Refuse inputs over this before decoding anything. */
export const MAX_INPUT_BYTES = 40 * 1024 * 1024;
/** Stored photos: long edge at most this many pixels (A§9.1). */
export const FULL_EDGE = 1024;
export const THUMB_EDGE = 256;
export const JPEG_QUALITY = 0.82;
/** Limits as Android (A§9.1). */
export const MAX_PER_BREW = 6;
export const MAX_PER_BEAN = 3;

export interface Size {
  readonly w: number;
  readonly h: number;
}

/** The size with its long edge at most `edge`, aspect kept, never upscaled, at least 1 × 1. */
export function targetSize(w: number, h: number, edge: number): Size {
  const long = Math.max(w, h);
  if (long <= edge || long <= 0) return { w: Math.max(1, Math.round(w)), h: Math.max(1, Math.round(h)) };
  const k = edge / long;
  return { w: Math.max(1, Math.round(w * k)), h: Math.max(1, Math.round(h * k)) };
}

/**
 * The draws that take `w × h` down to its target: halve repeatedly while the remaining ratio is
 * over 2 (a single big downscale aliases), then one final draw at the target. The last entry is
 * always the target; an image already small enough is drawn once at its own size (re-encoding
 * is what strips EXIF).
 */
export function downscaleSteps(w: number, h: number, edge: number): Size[] {
  const target = targetSize(w, h, edge);
  const steps: Size[] = [];
  let cw = w;
  let ch = h;
  while (cw / target.w > 2) {
    cw = Math.round(cw / 2);
    ch = Math.round(ch / 2);
    steps.push({ w: cw, h: ch });
  }
  steps.push(target);
  return steps;
}

/** Messages a person sees. */
export const PhotoErrors = {
  tooBig: "That photo is over 40 MB, too big to open here.",
  format: "This photo's format can't be opened in this browser. Try a JPEG or PNG, or take the photo from here.",
  brewFull: "That's the most for one brew",
  beanFull: "That's the most for one bean",
} as const;
