/**
 * List-detail panes (v4 §2.3). A tab adopts panes by rendering its screen through `ListDetail`:
 * below expanded it renders just the list (today's screen, unchanged), and from expanded up it
 * shows the list pane beside a detail pane. The URL is the selection: a pane-able route at stack
 * index 1 above its own tab renders here (PaneHost) instead of as a full-window layer. The list
 * element keeps the same position in the tree in both modes, so typed search text and other list
 * state survive a resize across 840 (§2.10).
 */
import { animate } from "motion";
import { createContext, Suspense, useCallback, useContext, useLayoutEffect, useRef, type ReactNode, type RefObject } from "react";
import { effectsFast, spatialFast } from "@/design/motion";
import { paneRouteOf } from "./layerPlan";
import { registerPaneHost, useDetailPlan } from "./paneHosts";
import { DetailScreen } from "./registry";
import { afterNextPop, nav, router, useRouter, type RouterSnapshot } from "./router";
import { PANEABLE_KINDS, parentTab, parsePath, pathOf, type DetailRoute, type SharedFrom, type TabId } from "./routes";
import { prefersReducedMotion } from "./transitionState";
import { atLeast, useWindowClass } from "./windowClass";
import shell from "./shell.module.css";

// ---------------------------------------------------------------- usePane

export interface PaneInfo {
  /** True when this screen renders inside a detail pane (hide Back, no header flight, §2.3 rule 7). */
  readonly inPane: boolean;
  /** Leaves the selection: the pane goes back to its default. A no-op for the default itself. */
  readonly close: () => void;
}

const NOT_IN_PANE: PaneInfo = Object.freeze({ inPane: false, close: () => nav.back() });
const PaneContext = createContext<PaneInfo>(NOT_IN_PANE);

export function usePane(): PaneInfo {
  return useContext(PaneContext);
}

const SELECTED: PaneInfo = Object.freeze({ inPane: true, close: () => nav.back() });
const DEFAULT: PaneInfo = Object.freeze({ inPane: true, close: () => undefined });

// ---------------------------------------------------------------- selection (rule 2)

function isPaneRouteOf(r: DetailRoute | undefined, tab: TabId): boolean {
  return r != null && PANEABLE_KINDS.has(r.kind) && parentTab(r) === tab;
}

/**
 * Rule 2: from the tab's default (depth 0) a selection is pushed; over an existing pane selection
 * it replaces, so Back/Esc from any selection returns to the default in one step. "none" when the
 * path is already selected. Pure, exported for tests.
 */
export function selectMode(s: RouterSnapshot, path: string, tab: TabId): "push" | "replace" | "none" {
  const route = parsePath(path);
  if (!route || route.kind === "tab") return "push";
  if (s.depth >= 1 && pathOf(s.top) === path) return "none";
  if (s.depth === 1 && s.tab === tab && isPaneRouteOf(s.stack[1] as DetailRoute, tab) && isPaneRouteOf(route, tab)) return "replace";
  return "push";
}

/** Returns `select(path)`, which pushes or replaces per rule 2. Stable. */
export function useSelectInPane(tab: TabId): (path: string, opts?: { from?: SharedFrom }) => void {
  return useCallback(
    (path: string, opts?: { from?: SharedFrom }) => {
      const mode = selectMode(router.get(), path, tab);
      if (mode === "replace") nav.replace(path);
      else if (mode === "push") nav.push(path, opts);
    },
    [tab],
  );
}

/** True while `tab` shows list and detail side by side (>= expanded). */
export function usePaneMode(): boolean {
  return atLeast(useWindowClass().width, "expanded");
}

/** The path selected in `tab`'s pane (stack index 1, pane-able, this tab), else null. For aria-current. */
export function useSelectedPath(tab: TabId): string | null {
  return useRouter((s) => (s.tab === tab && isPaneRouteOf(s.stack[1] as DetailRoute | undefined, tab) ? pathOf(s.stack[1]!) : null), tab);
}

/**
 * Opens `path` with `tab` underneath it, from anywhere (Today's Recent row opening a brew in Log).
 * At expanded+ with a ListDetail in that tab it lands in the pane; otherwise it is a layer.
 */
export function openInTab(tab: TabId, path: string, opts?: { from?: SharedFrom }): void {
  const s = router.get();
  if (s.depth > 0) {
    nav.popToRoot(tab);
    afterNextPop(() => nav.push(path, opts));
    return;
  }
  if (s.tab !== tab) {
    if (tab === "today" && s.tabDepth === 1) {
      nav.switchTab(tab);
      afterNextPop(() => nav.push(path, opts));
      return;
    }
    nav.switchTab(tab);
  }
  nav.push(path, opts);
}

// ---------------------------------------------------------------- PaneHost

/** Renders a pane-able route through the registry, as a pane. */
export function PaneHost({ route }: { route: DetailRoute }) {
  return (
    <PaneContext.Provider value={SELECTED}>
      <Suspense fallback={null}>
        <DetailScreen route={route} from={undefined} />
      </Suspense>
    </PaneContext.Provider>
  );
}

/**
 * The incoming detail fades in with an 8 px drift (§2.9 rule 1); the outgoing one is already gone
 * (a new key unmounts it), so two heavy trees never overlap. The first content after the pane
 * appears (a resize, a tab first shown) does not animate.
 */
function PaneSwap({ contentKey, lastKey, lostFocus, children }: { contentKey: string; lastKey: RefObject<string | null>; lostFocus: RefObject<boolean>; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  // Layout cleanup runs before React removes this node, so focus is still inside it here. When it
  // is, the pane's owner puts it back on the list once the new content is in (A5).
  useLayoutEffect(() => {
    const el = box.current;
    return () => {
      if (el?.contains(document.activeElement)) lostFocus.current = true;
    };
  }, [lostFocus]);
  useLayoutEffect(() => {
    const el = box.current;
    // Runs before ListDetail's own layout effect records this key, so lastKey is still the old one.
    const animateIn = lastKey.current != null && lastKey.current !== contentKey;
    if (!el || !animateIn) return;
    const a = prefersReducedMotion()
      ? animate(el, { opacity: [0, 1] }, { duration: 0.1 })
      : animate(el, { opacity: [0, 1], transform: ["translateX(8px)", "translateX(0px)"] }, { opacity: effectsFast, transform: spatialFast });
    return () => a.stop();
    // Mount-only per key (the parent keys this element).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <div ref={box} className={shell.paneSwap} data-pane-key={contentKey}>
      {children}
    </div>
  );
}

// ---------------------------------------------------------------- ListDetail

export interface ListDetailProps {
  /** The tab this list lives in. */
  tab: TabId;
  /** The list (today's whole screen on compact and medium). */
  list: ReactNode;
  /** Accessible name of the list pane ("Recipes"). */
  listLabel: string;
  /** What the detail pane shows with nothing selected. Never empty (§2.3, D3). */
  defaultDetail: ReactNode;
  /**
   * In-tab selection instead of a route (Gear beans/grinders/settings): when given, it is shown
   * in the detail pane and the route is ignored. `detailKey` changes animate like a selection.
   */
  detail?: ReactNode;
  detailKey?: string;
  /** Accessible name of the detail pane; default "<listLabel> details". */
  detailLabel?: string;
}

export function ListDetail({ tab, list, listLabel, defaultDetail, detail, detailKey, detailLabel }: ListDetailProps) {
  const paneMode = usePaneMode();
  const plan = useDetailPlan();
  const listPane = useRef<HTMLDivElement>(null);
  const detailPane = useRef<HTMLDivElement>(null);
  const shownKey = useRef<string | null>(null);
  const lostFocus = useRef(false);
  const lastRow = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => registerPaneHost(tab), [tab]);

  // Widening with a selection: bring the selected row into view, without animation (rule 4).
  useLayoutEffect(() => {
    if (!paneMode) {
      shownKey.current = null;
      return;
    }
    listPane.current?.querySelector<HTMLElement>('[aria-current="true"], [aria-current="page"]')?.scrollIntoView?.({ block: "nearest" });
  }, [paneMode]);

  const routeDetail = paneMode ? paneRouteOf(plan) : null;
  const ownsRoute = routeDetail != null && parentTab(routeDetail.route) === tab;
  const key = detail !== undefined ? `in:${detailKey ?? ""}` : ownsRoute ? `r:${pathOf(routeDetail.route)}` : "default";

  useLayoutEffect(() => {
    if (!paneMode) return;
    if (shownKey.current != null && shownKey.current !== key) detailPane.current?.scrollTo?.({ top: 0 });
    shownKey.current = key;
    // Closing a selection from inside the pane (its x, or Escape after tabbing in) unmounted the
    // focused node: focus goes back to the row that was selected, else into the list.
    const row = lastRow.current;
    const list = listPane.current;
    // The focusable row itself (Recipes marks its <li> too).
    lastRow.current = list?.querySelector<HTMLElement>(':is(button, a, [tabindex])[aria-current="true"], :is(button, a, [tabindex])[aria-current="page"]') ?? null;
    if (!lostFocus.current) return;
    lostFocus.current = false;
    if (document.activeElement != null && document.activeElement !== document.body) return;
    const target = (row?.isConnected ? row : null) ?? lastRow.current ?? list?.querySelector<HTMLElement>("[data-roving-index], input, h1");
    if (target && target.tagName === "H1" && !target.hasAttribute("tabindex")) target.setAttribute("tabindex", "-1");
    target?.focus({ preventScroll: true });
  }, [paneMode, key]);

  let content: ReactNode;
  if (detail !== undefined) content = <PaneContext.Provider value={DEFAULT}>{detail}</PaneContext.Provider>;
  else if (ownsRoute) content = <PaneHost route={routeDetail.route} />;
  else content = <PaneContext.Provider value={DEFAULT}>{defaultDetail}</PaneContext.Provider>;

  return (
    <div className={shell.listDetail} data-panes={paneMode || undefined}>
      <div ref={listPane} className={shell.listPane} data-list-pane="" role={paneMode ? "region" : undefined} aria-label={paneMode ? `${listLabel} list` : undefined}>
        {list}
      </div>
      {paneMode && (
        <div ref={detailPane} className={shell.detailPane} data-detail-pane="" role="region" aria-label={detailLabel ?? `${listLabel} details`}>
          <PaneSwap key={key} contentKey={key} lastKey={shownKey} lostFocus={lostFocus}>
            {content}
          </PaneSwap>
        </div>
      )}
    </div>
  );
}
