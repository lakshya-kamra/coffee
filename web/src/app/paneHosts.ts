/**
 * Which tabs currently have a mounted ListDetail (v4 §2.3 rule 1), and the live detail plan that
 * DetailHost, the nav and the shortcuts all read. A tab that has not adopted ListDetail is never
 * in this set, so its details keep today's full-layer behaviour.
 */
import { useMemo, useSyncExternalStore } from "react";
import { planDetails, type PlannedDetail } from "./layerPlan";
import { router, useRoute } from "./router";
import type { TabId } from "./routes";
import { useWindowClass, windowClass } from "./windowClass";

let hosts: ReadonlySet<TabId> = new Set();
const counts = new Map<TabId, number>();
const listeners = new Set<() => void>();

function publish() {
  hosts = new Set([...counts.entries()].filter(([, n]) => n > 0).map(([t]) => t));
  for (const l of listeners) l();
}

/** Called by ListDetail while mounted. Counted, so a StrictMode double mount stays correct. */
export function registerPaneHost(tab: TabId): () => void {
  counts.set(tab, (counts.get(tab) ?? 0) + 1);
  publish();
  return () => {
    counts.set(tab, Math.max(0, (counts.get(tab) ?? 1) - 1));
    publish();
  };
}

export const paneHosts = {
  get: (): ReadonlySet<TabId> => hosts,
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

export function usePaneHosts(): ReadonlySet<TabId> {
  return useSyncExternalStore(paneHosts.subscribe, paneHosts.get, paneHosts.get);
}

/** The placement of every detail on the stack, live. */
export function useDetailPlan(): PlannedDetail[] {
  const r = useRoute();
  const wc = useWindowClass();
  const h = usePaneHosts();
  return useMemo(() => planDetails(r.stack, r.tab, wc.width, h), [r, wc.width, h]);
}

/** The same plan, read once outside React (shortcut handlers). */
export function currentPlan(): PlannedDetail[] {
  const r = router.get();
  return planDetails(r.stack, r.tab, windowClass.get().width, hosts);
}

/** Test hook. */
export function resetPaneHostsForTests(): void {
  counts.clear();
  publish();
}
