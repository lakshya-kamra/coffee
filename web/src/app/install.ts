/**
 * "Install Coffee" (web-spec §11). Chrome fires `beforeinstallprompt` once, early, and only to
 * listeners that already exist; main.tsx imports this module so the event is never missed while
 * the Gear chunk is still loading. Gear › App › ABOUT reads it through `useInstallPrompt`.
 */
import { useSyncExternalStore } from "react";

export interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

let deferred: InstallPromptEvent | null = null;
const listeners = new Set<() => void>();
const emit = () => {
  for (const l of listeners) l();
};

if (typeof window !== "undefined") {
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault(); // no browser mini-infobar; the app offers it in ABOUT
    deferred = e as InstallPromptEvent;
    emit();
  });
  window.addEventListener("appinstalled", () => {
    deferred = null;
    emit();
  });
}

const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => listeners.delete(fn);
};
const get = () => deferred;

/** The saved prompt, or null when the browser has not offered one (or it was used). */
export function useInstallPrompt(): InstallPromptEvent | null {
  return useSyncExternalStore(subscribe, get, get);
}

/** Shows the browser's install dialog. A prompt can be used once, so it is dropped afterwards. */
export async function promptInstall(): Promise<void> {
  const e = deferred;
  if (!e) return;
  try {
    await e.prompt();
  } finally {
    deferred = null;
    emit();
  }
}
