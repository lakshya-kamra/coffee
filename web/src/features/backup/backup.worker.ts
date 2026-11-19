/**
 * Backup worker (web-spec §15.2, §15.4): reads and decodes a picked file off the main thread
 * (file.text(), NaN sanitising, JSON.parse, validation, photo base64 -> Blob plus a thumbnail),
 * and base64-encodes one photo at a time for the exporter. Blobs cross by reference.
 *
 * A photo in the file that is not already a clean ≤ 1024 px JPEG is drawn again here the way the
 * photo pipeline does it (same sizes and quality), which also drops any EXIF it carried.
 */
import { bytesToBase64, readBackupText, type PhotoBytes, type ReadResult } from "@/domain/backup/read";
import type { RecipeRef } from "@/domain/backup/legacy";
import { downscaleSteps, FULL_EDGE, JPEG_QUALITY, targetSize, THUMB_EDGE } from "@/features/photos/photoMath";

type Req = { id: number; type: "read"; file: Blob; builtIns: RecipeRef[] } | { id: number; type: "base64"; blob: Blob };

type Res = { id: number; ok: true; result: ReadResult | string } | { id: number; ok: false; error: string };

const scope = self as unknown as DedicatedWorkerGlobalScope;

const canDraw = () => typeof OffscreenCanvas !== "undefined" && typeof createImageBitmap === "function";

/** The bitmap as a JPEG with its long edge at most `edge` (halving first, as photo.worker.ts). */
async function encode(src: ImageBitmap, edge: number): Promise<Blob | null> {
  let current: CanvasImageSource = src;
  let canvas: OffscreenCanvas | null = null;
  const steps = downscaleSteps(src.width, src.height, edge);
  for (const [i, s] of steps.entries()) {
    const c = new OffscreenCanvas(s.w, s.h);
    const ctx = c.getContext("2d");
    if (!ctx) return null;
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    // JPEG has no alpha: a transparent image lands on white, not black.
    if (i === steps.length - 1) {
      ctx.fillStyle = "#fff";
      ctx.fillRect(0, 0, s.w, s.h);
    }
    ctx.drawImage(current, 0, 0, s.w, s.h);
    current = c;
    canvas = c;
  }
  return canvas ? canvas.convertToBlob({ type: "image/jpeg", quality: JPEG_QUALITY }) : null;
}

/** A 256 px JPEG like WEB-2's photo pipeline makes; null when this browser can't draw off-screen. */
async function makeThumb(full: Blob): Promise<Blob | null> {
  if (!canDraw()) return null;
  const bitmap = await createImageBitmap(full);
  try {
    return await encode(bitmap, THUMB_EDGE);
  } finally {
    bitmap.close();
  }
}

/** Any image this browser can decode, written again as a stored photo; null when it is not one. */
async function reencode(original: Blob): Promise<PhotoBytes | null> {
  if (!canDraw()) return null;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(original, { imageOrientation: "from-image" });
  } catch {
    return null; // not an image
  }
  try {
    const full = await encode(bitmap, FULL_EDGE);
    if (!full) return null;
    const size = targetSize(bitmap.width, bitmap.height, FULL_EDGE);
    return { full, thumb: await encode(bitmap, THUMB_EDGE), size: { widthPx: size.w, heightPx: size.h } };
  } finally {
    bitmap.close();
  }
}

scope.onmessage = (e: MessageEvent<Req>) => {
  const req = e.data;
  const reply = (res: Res) => scope.postMessage(res);
  const run = async (): Promise<ReadResult | string> => {
    if (req.type === "read") return readBackupText(await req.file.text(), req.builtIns, { makeThumb, reencode });
    return bytesToBase64(new Uint8Array(await req.blob.arrayBuffer()));
  };
  run().then(
    (result) => reply({ id: req.id, ok: true, result }),
    (err: unknown) => reply({ id: req.id, ok: false, error: err instanceof Error ? err.message : String(err) }),
  );
};
