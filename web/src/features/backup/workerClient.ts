/**
 * Talks to backup.worker.ts. Where module workers are missing (tests, very old browsers) the
 * same pure functions run on the main thread, so behaviour never differs, only smoothness.
 */
import { catalog } from "@/catalog/catalog";
import { bytesToBase64, readBackupText, type ReadResult } from "@/domain/backup/read";
import type { RecipeRef } from "@/domain/backup/legacy";
import { MAX_IMPORT_BYTES } from "@/domain/backup/model";

type Pending = { resolve: (v: unknown) => void; reject: (e: unknown) => void };

let worker: Worker | null = null;
let broken = false;
let seq = 0;
const pending = new Map<number, Pending>();

function getWorker(): Worker | null {
  if (broken || typeof Worker === "undefined") return null;
  if (worker) return worker;
  try {
    const w = new Worker(new URL("./backup.worker.ts", import.meta.url), { type: "module" });
    w.onmessage = (e: MessageEvent<{ id: number; ok: boolean; result?: unknown; error?: string }>) => {
      const p = pending.get(e.data.id);
      if (!p) return;
      pending.delete(e.data.id);
      if (e.data.ok) p.resolve(e.data.result);
      else p.reject(new Error(e.data.error ?? "worker failed"));
    };
    w.onerror = (e) => {
      // A worker that cannot start fails every call; later calls run on the main thread.
      e.preventDefault();
      broken = true;
      worker = null;
      for (const p of pending.values()) p.reject(new Error("worker failed"));
      pending.clear();
      w.terminate();
    };
    worker = w;
    return w;
  } catch {
    broken = true;
    return null;
  }
}

function call<T>(msg: Record<string, unknown>): Promise<T> | null {
  const w = getWorker();
  if (!w) return null;
  const id = ++seq;
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
    w.postMessage({ ...msg, id });
  });
}

function builtInRefs(): RecipeRef[] {
  return catalog.recipes.map((r) => ({ id: r.id, name: r.name, author: r.author }));
}

/** Reads a picked file. Files over 64 MB are refused before a byte is read. */
export async function readBackupFile(file: Blob): Promise<ReadResult> {
  if (file.size > MAX_IMPORT_BYTES) return { ok: false, error: "tooBig" };
  await catalog.load(["recipes"]);
  const builtIns = builtInRefs();
  const viaWorker = call<ReadResult>({ type: "read", file, builtIns });
  if (viaWorker) {
    try {
      return await viaWorker;
    } catch {
      // Fall through to the main thread.
    }
  }
  try {
    return await readBackupText(await file.text(), builtIns);
  } catch {
    return { ok: false, error: "failed" };
  }
}

export async function blobToBase64(blob: Blob): Promise<string> {
  const viaWorker = call<string>({ type: "base64", blob });
  if (viaWorker) {
    try {
      return await viaWorker;
    } catch {
      // Fall through.
    }
  }
  return bytesToBase64(new Uint8Array(await blob.arrayBuffer()));
}
