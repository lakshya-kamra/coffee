/**
 * Turns a picked or captured file into what the repo stores (web-spec §13): a ≤ 1024 px JPEG and
 * a 256 px thumbnail, EXIF stripped by re-encoding, `takenAt` from EXIF DateTimeOriginal when
 * present (else the file's lastModified, else now). The work runs in photo.worker.ts; browsers
 * without OffscreenCanvas get the same steps on the main thread.
 */
import { downscaleSteps, FULL_EDGE, JPEG_QUALITY, MAX_INPUT_BYTES, PhotoErrors, THUMB_EDGE } from "./photoMath";
import { EXIF_SCAN_BYTES, exifDateTimeOriginal } from "./exifDate";

export interface ProcessedPhoto {
  full: Blob;
  thumb: Blob;
  width: number;
  height: number;
  takenAt: number;
}

export class PhotoError extends Error {}

interface WorkerReply {
  id: number;
  ok: boolean;
  full?: Blob;
  thumb?: Blob;
  width?: number;
  height?: number;
  decode?: boolean;
  error?: string;
}

let worker: Worker | null = null;
let workerBroken = false;
let seq = 0;
const pending = new Map<number, (r: WorkerReply) => void>();

function getWorker(): Worker | null {
  if (workerBroken || typeof Worker === "undefined" || typeof OffscreenCanvas === "undefined") return null;
  if (worker) return worker;
  try {
    worker = new Worker(new URL("./photo.worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (e: MessageEvent<WorkerReply>) => {
      const cb = pending.get(e.data.id);
      pending.delete(e.data.id);
      cb?.(e.data);
    };
    worker.onerror = () => {
      // A worker that cannot start (old browser, CSP) hands everything to the main thread.
      workerBroken = true;
      worker = null;
      for (const [id, cb] of pending) cb({ id, ok: false, error: "worker failed" });
      pending.clear();
    };
    return worker;
  } catch {
    workerBroken = true;
    return null;
  }
}

function viaWorker(w: Worker, file: Blob): Promise<WorkerReply> {
  const id = ++seq;
  return new Promise((resolve) => {
    pending.set(id, resolve);
    w.postMessage({ id, file });
  });
}

async function mainThreadEncode(src: ImageBitmap | HTMLImageElement, sw: number, sh: number, edge: number) {
  const steps = downscaleSteps(sw, sh, edge);
  let current: CanvasImageSource = src;
  let canvas: HTMLCanvasElement | null = null;
  for (const [i, s] of steps.entries()) {
    const c = document.createElement("canvas");
    c.width = s.w;
    c.height = s.h;
    const ctx = c.getContext("2d");
    if (!ctx) throw new Error("no 2d context");
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    if (i === steps.length - 1) {
      ctx.fillStyle = "#fff";
      ctx.fillRect(0, 0, s.w, s.h);
    }
    ctx.drawImage(current, 0, 0, s.w, s.h);
    current = c;
    canvas = c;
  }
  const last = steps[steps.length - 1]!;
  const blob = await new Promise<Blob>((res, rej) => canvas!.toBlob((b) => (b ? res(b) : rej(new Error("encode failed"))), "image/jpeg", JPEG_QUALITY));
  return { blob, w: last.w, h: last.h };
}

async function onMainThread(file: Blob): Promise<WorkerReply> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  } catch {
    return { id: 0, ok: false, decode: true };
  }
  try {
    const full = await mainThreadEncode(bitmap, bitmap.width, bitmap.height, FULL_EDGE);
    const thumb = await mainThreadEncode(bitmap, bitmap.width, bitmap.height, THUMB_EDGE);
    return { id: 0, ok: true, full: full.blob, thumb: thumb.blob, width: full.w, height: full.h };
  } finally {
    bitmap.close();
  }
}

/** EXIF DateTimeOriginal (read before re-encoding drops it), else lastModified, else now. */
export async function takenAtOf(file: Blob & { lastModified?: number }): Promise<number> {
  try {
    const head = new Uint8Array(await file.slice(0, EXIF_SCAN_BYTES).arrayBuffer());
    const t = exifDateTimeOriginal(head);
    if (t != null) return t;
  } catch {
    // Unreadable header: fall through.
  }
  return file.lastModified && file.lastModified > 0 ? file.lastModified : Date.now();
}

/** Throws PhotoError with a message a person can read. */
export async function processPhoto(file: Blob & { lastModified?: number }): Promise<ProcessedPhoto> {
  if (file.size > MAX_INPUT_BYTES) throw new PhotoError(PhotoErrors.tooBig);
  const takenAt = await takenAtOf(file);
  const w = getWorker();
  let r = w ? await viaWorker(w, file) : await onMainThread(file);
  if (!r.ok && !r.decode && w) r = await onMainThread(file);
  if (!r.ok || !r.full || !r.thumb || !r.width || !r.height) throw new PhotoError(PhotoErrors.format);
  return { full: r.full, thumb: r.thumb, width: r.width, height: r.height, takenAt };
}
