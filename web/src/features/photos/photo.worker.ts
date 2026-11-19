/// <reference lib="webworker" />
/**
 * Photo pipeline off the main thread (web-spec §13, rule 11): decode with EXIF orientation,
 * downscale to a 1024 px long edge (halving first), encode JPEG 0.82, and a 256 px thumbnail the
 * same way. Re-encoding drops every EXIF field. Module worker; OffscreenCanvas required (the
 * caller falls back to the main thread when it is missing).
 */
import { downscaleSteps, FULL_EDGE, JPEG_QUALITY, THUMB_EDGE, type Size } from "./photoMath";

interface Request {
  id: number;
  file: Blob;
}

async function encode(src: ImageBitmap, edge: number): Promise<{ blob: Blob; size: Size }> {
  const steps = downscaleSteps(src.width, src.height, edge);
  let current: CanvasImageSource = src;
  let canvas: OffscreenCanvas | null = null;
  for (const [i, s] of steps.entries()) {
    const c = new OffscreenCanvas(s.w, s.h);
    const ctx = c.getContext("2d");
    if (!ctx) throw new Error("no 2d context");
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    // JPEG has no alpha: a transparent PNG lands on white, not black.
    if (i === steps.length - 1) {
      ctx.fillStyle = "#fff";
      ctx.fillRect(0, 0, s.w, s.h);
    }
    ctx.drawImage(current, 0, 0, s.w, s.h);
    current = c;
    canvas = c;
  }
  const last = steps[steps.length - 1]!;
  const blob = await canvas!.convertToBlob({ type: "image/jpeg", quality: JPEG_QUALITY });
  return { blob, size: last };
}

self.onmessage = async (e: MessageEvent<Request>) => {
  const { id, file } = e.data;
  let bitmap: ImageBitmap | null = null;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    const full = await encode(bitmap, FULL_EDGE);
    const thumb = await encode(bitmap, THUMB_EDGE);
    self.postMessage({ id, ok: true, full: full.blob, thumb: thumb.blob, width: full.size.w, height: full.size.h });
  } catch (err) {
    self.postMessage({ id, ok: false, decode: bitmap == null, error: err instanceof Error ? err.message : String(err) });
  } finally {
    bitmap?.close();
  }
};
