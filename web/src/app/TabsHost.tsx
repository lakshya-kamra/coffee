/**
 * Keep-alive tabs with zero render on switch (web-spec §6.3). One fixed scroll layer per tab;
 * the active tab mounts at boot, the other four mount hidden in idle time, and then all five
 * stay mounted for the life of the page (state, scroll position and live subscriptions persist).
 * A switch re-renders no tab content: TabLayer is memo'd on its id, and the active state and the
 * Forge-style directional slide are written imperatively by transitions.ts.
 */
import { memo, Suspense, useEffect, useLayoutEffect, useRef, useState } from "react";
import { TabScreens, tabChunks } from "./registry";
import { useRouter } from "./router";
import { TAB_LABELS, TABS, type TabId } from "./routes";
import { shellElements } from "./transitionState";
import { showLayerNow, switchTabLayers } from "./transitions";
import { windowClass } from "./windowClass";
import shell from "./shell.module.css";

/** Gear's only prop is its sub-tab; this wrapper subscribes to just that slice. */
function GearRoot() {
  const gearTab = useRouter((s) => s.gearTab);
  return <TabScreens.gear tab={gearTab} />;
}

function TabContent({ id }: { id: TabId }) {
  switch (id) {
    case "today":
      return <TabScreens.today />;
    case "recipes":
      return <TabScreens.recipes />;
    case "log":
      return <TabScreens.log />;
    case "insights":
      return <TabScreens.insights />;
    case "gear":
      return <GearRoot />;
  }
}

const TabLayer = memo(function TabLayer({ id, mounted, register }: { id: TabId; mounted: boolean; register: (id: TabId, el: HTMLElement | null) => void }) {
  return (
    <section
      ref={(el: HTMLElement | null) => register(id, el)}
      className={shell.layer}
      data-tab={id}
      aria-label={TAB_LABELS[id]}
    >
      {mounted && (
        <Suspense fallback={null}>
          <TabContent id={id} />
        </Suspense>
      )}
    </section>
  );
});

function idle(cb: () => void): () => void {
  if (typeof requestIdleCallback === "function") {
    const h = requestIdleCallback(cb, { timeout: 2000 });
    return () => cancelIdleCallback(h);
  }
  const t = setTimeout(cb, 200);
  return () => clearTimeout(t);
}

const layers = new Map<TabId, HTMLElement>();
function register(id: TabId, el: HTMLElement | null) {
  if (el) layers.set(id, el);
  else layers.delete(id);
}

/** Re-tapping the active tab scrolls its layer to the top (smooth unless reduced motion). */
export function scrollTabToTop(id: TabId, smooth: boolean): void {
  layers.get(id)?.scrollTo({ top: 0, behavior: smooth ? "smooth" : "auto" });
}

export function TabsHost() {
  const tab = useRouter((s) => s.tab);
  const [mounted, setMounted] = useState<ReadonlySet<TabId>>(() => new Set([tab]));
  const shown = useRef<TabId | null>(null);
  const host = useRef<HTMLElement>(null);

  useLayoutEffect(() => {
    shellElements.tabs = host.current;
    return () => {
      shellElements.tabs = null;
    };
  }, []);

  // A tab opened before idle mounting reached it mounts in this same render (React's "adjust
  // state while rendering" pattern), so it is there before the switch starts.
  if (!mounted.has(tab)) setMounted(new Set([...mounted, tab]));

  // After first paint: fetch every tab's chunk and mount the rest, hidden.
  useEffect(() => {
    return idle(() => {
      for (const t of TABS) void tabChunks[t]().catch(() => undefined);
      setMounted(new Set(TABS));
    });
  }, []);

  useLayoutEffect(() => {
    const inc = layers.get(tab);
    if (!inc) return;
    const prev = shown.current;
    shown.current = tab;
    if (prev == null) {
      showLayerNow(inc, TABS.filter((t) => t !== tab).flatMap((t) => layers.get(t) ?? []));
      return;
    }
    if (prev === tab) return;
    const d = TABS.indexOf(tab) > TABS.indexOf(prev) ? 1 : -1;
    // The slide follows the nav: across with the bottom bar, down a rail or the sidebar. Read
    // from the window class store, so TabsHost never re-renders on a resize.
    void switchTabLayers(layers.get(prev) ?? null, inc, d, windowClass.get().width === "compact" ? "x" : "y");
  }, [tab]);

  return (
    // <main> (A5): when a layer covers the tabs they stop painting and leave the accessibility
    // tree, and the layer itself is the main landmark.
    <main ref={host} id="main" className={shell.tabs}>
      {TABS.map((id) => (
        <TabLayer key={id} id={id} mounted={mounted.has(id)} register={register} />
      ))}
    </main>
  );
}
