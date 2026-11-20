/**
 * Open/closed state of the shell's own overlays (command palette, shortcuts dialog). A tiny
 * store, so a button anywhere (a compact header's search icon, the rail) can open them without
 * props, and the overlays themselves load lazily only when first opened.
 */
import { useSyncExternalStore } from "react";

export interface ShellUi {
  readonly palette: boolean;
  /** Text to start the palette with ("" for empty). */
  readonly paletteQuery: string;
  readonly shortcuts: boolean;
}

let state: ShellUi = Object.freeze({ palette: false, paletteQuery: "", shortcuts: false });
const listeners = new Set<() => void>();

function set(patch: Partial<ShellUi>) {
  state = Object.freeze({ ...state, ...patch });
  for (const l of listeners) l();
}

/** Opens the command palette (Ctrl/Cmd+K, the rail/sidebar search, a header search icon). */
export function openPalette(query = ""): void {
  set({ palette: true, paletteQuery: query, shortcuts: false });
}

export function closePalette(): void {
  if (state.palette) set({ palette: false, paletteQuery: "" });
}

/** Opens the keyboard shortcuts dialog (`?`). */
export function openShortcuts(): void {
  set({ shortcuts: true, palette: false });
}

export function closeShortcuts(): void {
  if (state.shortcuts) set({ shortcuts: false });
}

export const shellUi = {
  get: (): ShellUi => state,
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

export function useShellUi(): ShellUi {
  return useSyncExternalStore(shellUi.subscribe, shellUi.get, shellUi.get);
}
