/**
 * Re-render when catalog files finish loading, and ask for the named ones. Screens that need a
 * lazily loaded file (grinders, roasters…) call this; until it lands the lists are empty.
 *
 * The React Compiler caches what a component computes from its props and state, and the catalog
 * is neither. A component that reads the catalog while rendering must take what it reads from
 * one of these hooks (the ready flag, the entry), or it keeps showing the answer from before the
 * file loaded.
 */
import { useEffect, useSyncExternalStore } from "react";
import { catalog } from "./catalog";
import type { CatalogGrinder, CatalogName } from "./types";

export function useCatalog(names: readonly CatalogName[]): boolean {
  const key = names.join(",");
  useEffect(() => {
    void catalog.load(key ? (key.split(",") as CatalogName[]) : []);
  }, [key]);
  const version = useSyncExternalStore(catalog.subscribe, catalog.version, catalog.version);
  // The version is an input on purpose. The compiler caches this on the inputs it can see, and a
  // caller's array literal never changes identity, so without it `ready` stayed false for good
  // on any screen that mounted before its files landed (a cold load of /gear/app).
  return allLoaded(key, version);
}

function allLoaded(key: string, _version: number): boolean {
  return key === "" || (key.split(",") as CatalogName[]).every((n) => catalog.isLoaded(n));
}

/**
 * True when one of the named files could not be fetched (and has not loaded since). Use it next
 * to useCatalog to say "couldn't load" with a Retry instead of showing an empty list.
 */
export function useCatalogFailed(names: readonly CatalogName[]): boolean {
  const key = names.join(",");
  const get = () => key !== "" && (key.split(",") as CatalogName[]).some((n) => catalog.failed(n));
  return useSyncExternalStore(catalog.subscribe, get, get);
}

/** Tries the named files again (they are skipped when already loaded). */
export function retryCatalog(names: readonly CatalogName[]): void {
  void catalog.load(names);
}

/**
 * The catalog entry a grinder row is linked to, null until grinders.json has loaded (or when the
 * row is not linked). Re-renders the caller when the file lands.
 */
export function useCatalogGrinder(catalogId: string | null | undefined): CatalogGrinder | null {
  const linked = catalogId != null && catalogId !== "";
  useEffect(() => {
    if (linked) void catalog.load(["grinders"]);
  }, [linked]);
  const get = () => catalog.grinder(catalogId);
  return useSyncExternalStore(catalog.subscribe, get, get);
}
