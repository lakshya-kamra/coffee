/**
 * THE ONLY CODE THAT TOUCHES KEY STORAGE (web-spec §14.2). A key is bound to the host it was
 * saved for and lives in one of two places:
 *   - "Remember on this device" unticked (the default): this page's memory only. It is gone when
 *     the tab closes or reloads, and nothing is written to disk;
 *   - ticked: localStorage, under `coffee.ai.key.<providerId>`.
 * Saving removes the entry from the other place; Remove deletes both. Keys never go into
 * IndexedDB, a backup, a URL, an error message or a log (lint bans console here).
 *
 * Why memory and not sessionStorage (what §14.2 first said): browsers write sessionStorage to the
 * profile for session restore and hand it back on "Reopen closed tab", so a key "forgotten when
 * you close the tab" would come back for the next person at a shared computer. sessionStorage
 * is only swept here, for entries an earlier build left behind.
 */

export const KEY_PREFIX = "coffee.ai.key.";

interface KeyEntry {
  v: 1;
  host: string;
  key: string;
  hint: string;
  savedAt: number;
}

/** What the settings list may know about a saved key: never the key itself. */
export interface KeyBadge {
  readonly host: string;
  /** Last four characters, shown as "•••• 7f3a". */
  readonly hint: string;
  readonly remembered: boolean;
  /** When this key was saved: tells one saving from the next without holding any of the key. */
  readonly savedAt: number;
}

export type KeyCheck = { readonly ok: true } | { readonly ok: false; readonly message: string };

interface Stores {
  /** Never written: swept for keys an earlier build stored there. */
  session: Storage | null;
  local: Storage | null;
}

/** Keys saved without "Remember on this device": this page's memory, nowhere else. */
const memory = new Map<string, KeyEntry>();

function defaultStores(): Stores {
  // Either accessor can throw (blocked site data, private modes), so each is read on its own.
  let session: Storage | null = null;
  let local: Storage | null = null;
  try {
    session = typeof sessionStorage === "undefined" ? null : sessionStorage;
  } catch {
    session = null;
  }
  try {
    local = typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    local = null;
  }
  return { session, local };
}

function sweep(s: Storage | null) {
  if (!s) return;
  try {
    const keys: string[] = [];
    for (let i = 0; i < s.length; i++) {
      const k = s.key(i);
      if (k?.startsWith(KEY_PREFIX)) keys.push(k);
    }
    keys.forEach((k) => s.removeItem(k));
  } catch {
    /* storage unavailable: nothing to remove */
  }
}

let stores: Stores | null = null;
function getStores(): Stores {
  if (!stores) {
    stores = defaultStores();
    sweep(stores.session);
  }
  return stores;
}

let version = 0;
const listeners = new Set<() => void>();
function changed() {
  version++;
  for (const l of listeners) l();
}

// Another tab saving or removing a remembered key.
if (typeof window !== "undefined") {
  window.addEventListener("storage", (e) => {
    if (e.key == null || e.key.startsWith(KEY_PREFIX)) changed();
  });
}

function readEntry(s: Storage | null, providerId: string): KeyEntry | null {
  if (!s) return null;
  try {
    const raw = s.getItem(KEY_PREFIX + providerId);
    if (!raw) return null;
    const o = JSON.parse(raw) as Partial<KeyEntry>;
    if (o.v !== 1 || typeof o.key !== "string" || typeof o.host !== "string") return null;
    return { v: 1, host: o.host, key: o.key, hint: typeof o.hint === "string" ? o.hint : o.key.slice(-4), savedAt: typeof o.savedAt === "number" ? o.savedAt : 0 };
  } catch {
    return null;
  }
}

function entry(providerId: string): { e: KeyEntry; remembered: boolean } | null {
  const st = getStores();
  // The tab's own copy wins: it is the one typed most recently in this tab.
  const m = memory.get(providerId);
  if (m) return { e: m, remembered: false };
  const l = readEntry(st.local, providerId);
  return l ? { e: l, remembered: true } : null;
}

function remove(s: Storage | null, providerId: string) {
  try {
    s?.removeItem(KEY_PREFIX + providerId);
  } catch {
    /* nothing stored there */
  }
}

/** Same rules as AiSettingsStore.saveKey on Android. */
export function validateKey(raw: string, forHost: string): KeyCheck {
  const key = raw.trim();
  if (!key) return { ok: false, message: "Paste a key first." };
  if (!forHost.trim()) return { ok: false, message: "Save the address first." };
  if (/[^\x21-\x7E]/.test(key)) return { ok: false, message: "That doesn't look like an API key: it has spaces or line breaks in the middle." };
  if (/^https?:\/\//i.test(key)) return { ok: false, message: "That's a web address, not an API key." };
  if (key.length < 8) return { ok: false, message: "That's too short to be an API key." };
  if (key.length > 512) return { ok: false, message: "That's too long to be an API key." };
  return { ok: true };
}

export const keyStore = {
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
  version: (): number => version,

  badge: (providerId: string): KeyBadge | null => {
    const x = entry(providerId);
    return x ? { host: x.e.host, hint: x.e.hint, remembered: x.remembered, savedAt: x.e.savedAt } : null;
  },

  /** The key, only when it was saved for `forHost` (host binding, A§8.2). */
  read: (providerId: string, forHost: string): string | null => {
    const x = entry(providerId);
    return x && x.e.host === forHost ? x.e.key : null;
  },

  save: (providerId: string, raw: string, forHost: string, remember: boolean): KeyCheck => {
    const check = validateKey(raw, forHost);
    if (!check.ok) return check;
    const key = raw.trim();
    const st = getStores();
    const value: KeyEntry = { v: 1, host: forHost, key, hint: key.slice(-4), savedAt: Date.now() };
    if (remember) {
      if (!st.local) return { ok: false, message: "This browser won't let the site store anything. Allow site data, or use another browser." };
      try {
        st.local.setItem(KEY_PREFIX + providerId, JSON.stringify(value));
      } catch {
        return { ok: false, message: "This browser refused to store the key. Allow site data, or free some space." };
      }
      memory.delete(providerId);
    } else {
      memory.set(providerId, value);
      remove(st.local, providerId);
    }
    remove(st.session, providerId);
    changed();
    return { ok: true };
  },

  remove: (providerId: string): void => {
    const st = getStores();
    memory.delete(providerId);
    remove(st.session, providerId);
    remove(st.local, providerId);
    changed();
  },

  /** Every saved key, wherever it is (Delete everything). */
  removeAll: (): void => {
    const st = getStores();
    memory.clear();
    sweep(st.session);
    sweep(st.local);
    changed();
  },

  /** Test hook: in-memory storages, and a fresh page (nothing held in memory). */
  setStoragesForTests: (session: Storage | null, local: Storage | null): void => {
    stores = { session, local };
    memory.clear();
    sweep(session);
    changed();
  },
};
