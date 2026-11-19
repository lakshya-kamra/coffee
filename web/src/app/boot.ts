/**
 * Boot sequence (web-spec §6.8). First paint has real data: the database, the hot store and the
 * two catalogs Today needs (brewers, recipes) are loaded before React renders. Everything else
 * waits for idle time after first paint.
 */
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo } from "@/data/repo";
import { store } from "@/data/store";
import { storageStatus } from "@/data/storage";
import { startPrefsMirror } from "./prefsMirror";
import { startupTasks } from "./registry";
import { startRouter } from "./router";

/** Set when another tab upgraded the database; the shell then shows "Reload". */
export const versionChanged = (() => {
  let value = false;
  const listeners = new Set<() => void>();
  return {
    get: () => value,
    set: (v: boolean) => {
      value = v;
      for (const l of listeners) l();
    },
    subscribe: (fn: () => void) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
  };
})();

export type BootResult = { ok: true } | { ok: false; reason: "storage" };

/** True when some grinder row's scale lives in the catalog (grindScaleOf reads its entry). */
export function needsGrinderCatalog(grinders: readonly { catalogId: string | null }[]): boolean {
  return grinders.some((g) => g.catalogId != null && g.catalogId !== "");
}

export async function boot(): Promise<BootResult> {
  try {
    await db.open();
  } catch (e) {
    console.error("Could not open IndexedDB", e);
    return { ok: false, reason: "storage" };
  }
  db.on("versionchange", () => {
    store.stop();
    db.close();
    versionChanged.set(true);
    return false;
  });

  // Brewers first: saved recipes and recipes.json resolve their brewer ids against them.
  await catalog.load(["brewers", "recipes"]);
  await store.init();
  // A grinder picked from the list keeps its dial format and ranges in grinders.json. Brew setup,
  // the brew row and the analysis prompt read the scale once, not reactively, so the file is here
  // before anything can ask (a deep link to /brew/<id>, a tap in the first second). Browsers with
  // no linked grinder skip it; a returning one gets it from the service worker's cache.
  if (needsGrinderCatalog(store.get().grinders)) await catalog.load(["grinders"]);
  startPrefsMirror();

  // First-launch gate: a fresh browser with no data starts at /welcome. Existing data never sees onboarding.
  const fresh = !store.get().settings.onboardingDone && (await repo.isEmpty());
  startRouter(fresh ? { forcePath: "/welcome" } : {});
  return { ok: true };
}

/** After first paint (web-spec §6.8 step 5). The tabs mount themselves in idle time (TabsHost). */
export function afterFirstPaint(): void {
  const run = () => {
    void catalog.load();
    void storageStatus();
    for (const task of startupTasks) {
      task().catch((e: unknown) => console.warn("Startup task failed", e));
    }
  };
  if (typeof requestIdleCallback === "function") requestIdleCallback(run, { timeout: 3000 });
  else setTimeout(run, 200);
}
