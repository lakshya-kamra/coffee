/**
 * The only writer of the localStorage keys public/boot.js reads before first paint (web-spec
 * §5.6), and of the one sessionStorage flag the preload-error reload uses. Mirrors
 * settings.darkMode / settings.motion from IndexedDB and keeps data-theme live with the system.
 */
import { store } from "@/data/store";

const THEME_KEY = "coffee.theme";
const MOTION_KEY = "coffee.motion";
const RELOAD_FLAG = "coffee.preloadReloaded";

function safeLocal(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function safeSession(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

const darkQuery = typeof matchMedia === "function" ? matchMedia("(prefers-color-scheme: dark)") : null;

export function resolveTheme(mode: "system" | "light" | "dark"): "dark" | "light" {
  if (mode === "dark" || mode === "light") return mode;
  return darkQuery?.matches === false ? "light" : "dark";
}

let lastMode: "system" | "light" | "dark" | null = null;
let lastMotion: "system" | "reduced" | null = null;

function apply(mode: "system" | "light" | "dark", motion: "system" | "reduced") {
  const el = document.documentElement;
  const theme = resolveTheme(mode);
  if (el.dataset.theme !== theme) el.dataset.theme = theme;
  if (el.dataset.motion !== motion) el.dataset.motion = motion;
  // theme-color follows the resolved canvas (the brew screen overrides it while brewing).
  const bg = getComputedStyle(el).getPropertyValue("--bg").trim();
  if (bg) {
    for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) meta.content = bg;
  }
  if (mode !== lastMode || motion !== lastMotion) {
    const ls = safeLocal();
    try {
      ls?.setItem(THEME_KEY, mode);
      ls?.setItem(MOTION_KEY, motion);
    } catch {
      // Storage full or blocked: boot.js falls back to the system preference.
    }
    lastMode = mode;
    lastMotion = motion;
  }
}

/** Starts mirroring. Call once after store.init(). */
export function startPrefsMirror(): () => void {
  const sync = () => {
    const s = store.get().settings;
    apply(s.darkMode, s.motion);
  };
  sync();
  const unsub = store.subscribe(sync);
  darkQuery?.addEventListener("change", sync);
  return () => {
    unsub();
    darkQuery?.removeEventListener("change", sync);
  };
}

/** A stale hashed chunk failed to load (new deploy): reload once per session, never in a loop. */
export function reloadOnceForPreloadError(): boolean {
  const ss = safeSession();
  try {
    if (ss?.getItem(RELOAD_FLAG)) return false;
    ss?.setItem(RELOAD_FLAG, "1");
  } catch {
    return false;
  }
  location.reload();
  return true;
}

/**
 * Per-device shell conveniences (v4 §2.2 sidebar collapse, §2.8 palette recents). Never in
 * backups, never shared. Storage blocked or full: reads give null and writes are dropped.
 */
export type DeviceKey = "coffee.sidebar" | "coffee.paletteRecent";

export function readDeviceKey(key: DeviceKey): string | null {
  try {
    return safeLocal()?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

export function writeDeviceKey(key: DeviceKey, value: string): void {
  try {
    safeLocal()?.setItem(key, value);
  } catch {
    // Blocked or full: the preference lasts for this page only.
  }
}
