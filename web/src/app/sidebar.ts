/**
 * Sidebar collapse (v4 §2.2): per device in localStorage `coffee.sidebar` ("collapsed" or
 * "open"), mirrored to <html data-sidebar> so the --nav-w token follows in CSS. Only meaningful at
 * large and up; crossing a breakpoint snaps, it never animates width.
 */
import { useSyncExternalStore } from "react";
import { readDeviceKey, writeDeviceKey } from "./prefsMirror";

let collapsed = false;
let started = false;
const listeners = new Set<() => void>();

function mirror() {
  if (typeof document === "undefined") return;
  const el = document.documentElement;
  if (collapsed) el.dataset.sidebar = "collapsed";
  else delete el.dataset.sidebar;
}

/** Reads the stored choice once, before the first render (main.tsx). */
export function startSidebar(): void {
  if (started) return;
  started = true;
  collapsed = readDeviceKey("coffee.sidebar") === "collapsed";
  mirror();
}

export function isSidebarCollapsed(): boolean {
  return collapsed;
}

export function setSidebarCollapsed(next: boolean): void {
  if (next === collapsed) return;
  collapsed = next;
  writeDeviceKey("coffee.sidebar", next ? "collapsed" : "open");
  mirror();
  for (const l of listeners) l();
}

export function toggleSidebar(): void {
  setSidebarCollapsed(!collapsed);
}

const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => listeners.delete(fn);
};

export function useSidebarCollapsed(): boolean {
  return useSyncExternalStore(subscribe, isSidebarCollapsed, isSidebarCollapsed);
}
