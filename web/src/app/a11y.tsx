/**
 * Route-change accessibility (v4 A5): the document title per screen, focus on the new screen's
 * first h1 (or its layer when it has none) after a push or a tab switch, focus back on whatever
 * opened a layer when it closes (per layer, so a stack of three unwinds correctly), and the
 * "Skip to content" link. Pane selections are not route changes here: focus stays in the list.
 */
import { useEffect, useLayoutEffect, useRef } from "react";
import { useStore } from "@/data/store";
import { layerLabel } from "./DetailHost";
import { layersOf, paneRouteOf, type PlannedDetail } from "./layerPlan";
import { useDetailPlan } from "./paneHosts";
import { useRoute } from "./router";
import { TAB_LABELS, type DetailRoute, type GearTab, type TabId } from "./routes";

const GEAR_TITLES: Readonly<Record<GearTab, string>> = { beans: "Beans", grinders: "Grinders", app: "Settings" };

/** "<screen> · Coffee". Pure; `recipeName` resolves recipe ids for recipe and brew screens. */
export function titleFor(top: DetailRoute | null, tab: TabId, gearTab: GearTab, recipeName: (id: string) => string | null): string {
  let screen: string;
  if (!top) screen = tab === "gear" ? GEAR_TITLES[gearTab] : TAB_LABELS[tab];
  else if (top.kind === "recipe") screen = recipeName(top.id) ?? "Recipe";
  else if (top.kind === "brew") {
    const n = recipeName(top.recipeId);
    screen = n ? `Brew ${n}` : "Brew";
  } else screen = layerLabel(top);
  return `${screen} · Coffee`;
}

/** The route the user is looking at: the top layer, else the pane selection, else null (the tab). */
export function visibleDetail(plan: readonly PlannedDetail[]): DetailRoute | null {
  const layers = layersOf(plan);
  return layers[layers.length - 1]?.route ?? paneRouteOf(plan)?.route ?? null;
}

export function useDocumentTitle(): void {
  const r = useRoute();
  const plan = useDetailPlan();
  const top = visibleDetail(plan);
  const id = top?.kind === "recipe" ? top.id : top?.kind === "brew" ? top.recipeId : "";
  const name = useStore((s) => (id ? (s.recipes.find((x) => x.id === id)?.name ?? null) : null), Object.is, id);
  const title = titleFor(top, r.tab, r.gearTab, () => name);
  useEffect(() => {
    document.title = title;
  }, [title]);
}

// ---------------------------------------------------------------- focus

/** The element that holds the screen now on top: the top layer, else the active tab layer. */
export function activeContainer(): HTMLElement | null {
  const layers = document.querySelectorAll<HTMLElement>("[data-layer-key]");
  const top = layers[layers.length - 1];
  if (top && top.dataset.covered !== "true") return top;
  return document.querySelector<HTMLElement>('[data-tab][data-state="active"]');
}

function headingIn(container: HTMLElement): HTMLElement | null {
  // In list-detail, the detail pane's heading is "the active pane's" (§2.6 skip link).
  return container.querySelector<HTMLElement>("[data-detail-pane] h1") ?? container.querySelector<HTMLElement>("h1");
}

function focusHeading(h: HTMLElement) {
  if (!h.hasAttribute("tabindex")) h.setAttribute("tabindex", "-1");
  h.focus({ preventScroll: true });
}

/**
 * Focuses the first h1 in `container`, waiting up to 1.5 s for a lazily loaded screen to render
 * one; falls back to the container itself. Returns a cancel function.
 */
export function focusScreen(container: HTMLElement): () => void {
  const now = headingIn(container);
  if (now) {
    focusHeading(now);
    return () => undefined;
  }
  let done = false;
  const mo = new MutationObserver(() => {
    const h = headingIn(container);
    if (h && !done) {
      done = true;
      mo.disconnect();
      clearTimeout(t);
      focusHeading(h);
    }
  });
  mo.observe(container, { childList: true, subtree: true });
  const t = setTimeout(() => {
    if (done) return;
    done = true;
    mo.disconnect();
    if (!container.hasAttribute("tabindex")) container.setAttribute("tabindex", "-1");
    container.focus({ preventScroll: true });
  }, 1500);
  return () => {
    done = true;
    mo.disconnect();
    clearTimeout(t);
  };
}

/** Moves focus on pushes, pops and tab switches (A5). Mount once, after DetailHost. */
export function useRouteFocus(): void {
  const r = useRoute();
  const plan = useDetailPlan();
  const layerKeys = layersOf(plan).map((d) => d.key);
  const sig = `${r.tab}|${layerKeys.join(",")}`;
  const prev = useRef<{ tab: TabId; keys: string[] } | null>(null);
  const triggers = useRef(new Map<string, HTMLElement>());
  const lastActive = useRef<Element | null>(null);

  // What had focus just before this navigation rendered (the trigger), read before React commits.
  useLayoutEffect(() => {
    const onFocus = () => {
      lastActive.current = document.activeElement;
    };
    document.addEventListener("focusin", onFocus);
    return () => document.removeEventListener("focusin", onFocus);
  }, []);

  useEffect(() => {
    const before = prev.current;
    prev.current = { tab: r.tab, keys: layerKeys };
    if (!before) return; // first render: the page's natural focus
    let cancel: () => void = () => undefined;
    const added = layerKeys.filter((k) => !before.keys.includes(k));
    const removed = before.keys.filter((k) => !layerKeys.includes(k));
    if (added.length > 0) {
      const key = added[added.length - 1]!;
      const t = lastActive.current;
      if (t instanceof HTMLElement && t !== document.body) triggers.current.set(key, t);
      const el = document.querySelector<HTMLElement>(`[data-layer-key="${CSS.escape(key)}"]`);
      if (el) cancel = focusScreen(el);
    } else if (removed.length > 0) {
      // Back/Escape: the trigger of the lowest removed layer, if it is still on screen.
      const key = removed[0]!;
      const t = triggers.current.get(key);
      for (const k of removed) triggers.current.delete(k);
      if (t?.isConnected && !t.closest("[inert], [aria-hidden='true']")) t.focus({ preventScroll: true });
      else {
        const c = activeContainer();
        if (c) cancel = focusScreen(c);
      }
    } else if (before.tab !== r.tab && layerKeys.length === 0) {
      // A tab switch: keep focus on the nav item when it was clicked there (the user may go on).
      const inNav = document.activeElement?.closest("nav");
      if (!inNav) {
        const c = document.querySelector<HTMLElement>(`[data-tab="${r.tab}"]`);
        if (c) cancel = focusScreen(c);
      }
    }
    return cancel;
    // `sig` covers tab and layer keys.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig]);
}

/** "Skip to content": the first Tab stop; focuses the active pane's h1. */
export function SkipLink() {
  return (
    <a
      href="#main"
      className="skip-link"
      onClick={(e) => {
        e.preventDefault();
        const c = activeContainer();
        if (c) focusScreen(c);
      }}
    >
      Skip to content
    </a>
  );
}
