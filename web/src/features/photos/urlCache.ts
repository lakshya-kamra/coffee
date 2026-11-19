/**
 * Object URLs for stored photo blobs (web-spec §13): an LRU of 64 entries, revoked on eviction
 * and when a photo is deleted, so thumbnails never leak memory and never re-read IndexedDB while
 * they are in use.
 */
import { useEffect, useState } from "react";
import { repo } from "@/data/repo";

const MAX = 64;
const urls = new Map<string, string>();
const inflight = new Map<string, Promise<string | null>>();

const keyOf = (uid: string, which: "full" | "thumb") => `${uid}:${which}`;

function touch(key: string, url: string) {
  urls.delete(key);
  urls.set(key, url);
  while (urls.size > MAX) {
    const [oldKey, oldUrl] = urls.entries().next().value as [string, string];
    urls.delete(oldKey);
    URL.revokeObjectURL(oldUrl);
  }
}

export function cachedPhotoUrl(uid: string, which: "full" | "thumb"): string | null {
  const key = keyOf(uid, which);
  const u = urls.get(key);
  if (u) touch(key, u);
  return u ?? null;
}

export function photoUrl(uid: string, which: "full" | "thumb"): Promise<string | null> {
  const key = keyOf(uid, which);
  const hit = cachedPhotoUrl(uid, which);
  if (hit) return Promise.resolve(hit);
  let p = inflight.get(key);
  if (!p) {
    p = repo
      .photoBlob(uid, which)
      .then((blob) => {
        if (!blob) return null;
        const url = URL.createObjectURL(blob);
        touch(key, url);
        return url;
      })
      .catch(() => null)
      .finally(() => inflight.delete(key));
    inflight.set(key, p);
  }
  return p;
}

/** Drops both sizes of a deleted photo. */
export function forgetPhoto(uid: string): void {
  for (const which of ["full", "thumb"] as const) {
    const key = keyOf(uid, which);
    const u = urls.get(key);
    if (u) URL.revokeObjectURL(u);
    urls.delete(key);
  }
}

/** The object URL for a photo, null until loaded (or when the bytes are missing). */
export function usePhotoUrl(uid: string, which: "full" | "thumb"): string | null {
  const [state, setState] = useState<{ key: string; url: string | null }>(() => ({ key: keyOf(uid, which), url: cachedPhotoUrl(uid, which) }));
  const key = keyOf(uid, which);
  useEffect(() => {
    let live = true;
    void photoUrl(uid, which).then((url) => {
      if (live) setState({ key, url });
    });
    return () => {
      live = false;
    };
  }, [uid, which, key]);
  return state.key === key ? state.url : cachedPhotoUrl(uid, which);
}
