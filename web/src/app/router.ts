/**
 * The router (web-spec §6.2): a tiny store over the History API, read with
 * useSyncExternalStore. No library. Each history entry carries the whole stack of paths, so a
 * reload, Back and Forward all restore exactly; tabs mirror Android's bottom-nav back behaviour
 * (Today ↔ another tab keeps at most one tab entry above Today).
 */
import { useMemo, useSyncExternalStore } from "react";
import { parentTab, parsePath, pathOf, tabRoute, type DetailRoute, type GearTab, type Route, type SharedFrom, type TabId } from "./routes";

export interface HistoryState {
  v: 1;
  tab: TabId;
  tabDepth: 0 | 1;
  depth: number;
  from?: SharedFrom;
  /** paths[0] is the tab root ("/gear/grinders"), then one path per detail. */
  paths: string[];
  /** `from` per detail, parallel to paths.slice(1). */
  froms: (SharedFrom | null)[];
  /** Set by CoffeeSheet's own entry; the route is unchanged under it. */
  sheet?: boolean;
  /** One id per open full sheet, bottom first (a picker over an editor). */
  sheets?: string[];
}

export interface RouterSnapshot {
  readonly tab: TabId;
  readonly gearTab: GearTab;
  readonly tabDepth: 0 | 1;
  /** stack[0] is the tab root; the rest are details, top last. */
  readonly stack: readonly Route[];
  readonly top: Route;
  readonly from?: SharedFrom;
  /** `from` per detail (parallel to stack.slice(1)). */
  readonly froms: readonly (SharedFrom | null)[];
  readonly depth: number;
}

/** Property syntax (not methods), so `onBack={nav.back}` is safe to pass around unbound. */
export interface Nav {
  push: (path: string, opts?: { from?: SharedFrom }) => void;
  replace: (path: string) => void;
  /** history.back() when anything is above the tab root, else Today. */
  back: () => void;
  /** history.go(-depth), then switch tab if asked. */
  popToRoot: (tab?: TabId) => void;
  switchTab: (tab: TabId, gearTab?: GearTab) => void;
}

let snap: RouterSnapshot = makeSnapshot(["/"], [], 0);
const listeners = new Set<() => void>();
let pendingAfterPop: (() => void) | null = null;
let started = false;

function makeSnapshot(paths: readonly string[], froms: readonly (SharedFrom | null)[], tabDepth: 0 | 1): RouterSnapshot {
  const root = parsePath(paths[0] ?? "/");
  const rootRoute: Route = root && root.kind === "tab" ? root : tabRoute("today");
  const details = paths.slice(1).map((p) => parsePath(p)).filter((r): r is DetailRoute => r != null && r.kind !== "tab");
  const stack: Route[] = [rootRoute, ...details];
  const tab = rootRoute.kind === "tab" ? rootRoute.tab : "today";
  const gearTab = rootRoute.kind === "tab" && rootRoute.gearTab ? rootRoute.gearTab : "beans";
  const fr = details.map((_, i) => froms[i] ?? null);
  const from = details.length ? (fr[details.length - 1] ?? undefined) : undefined;
  return Object.freeze({ tab, gearTab, tabDepth, stack, top: stack[stack.length - 1]!, from, froms: fr, depth: stack.length - 1 });
}

function stateOf(s: RouterSnapshot): HistoryState {
  return { v: 1, tab: s.tab, tabDepth: s.tabDepth, depth: s.depth, from: s.from, paths: s.stack.map(pathOf), froms: [...s.froms] };
}

function set(next: RouterSnapshot) {
  snap = next;
  for (const l of listeners) l();
}

function write(mode: "push" | "replace", next: RouterSnapshot) {
  const st = stateOf(next);
  if (mode === "push") history.pushState(st, "", pathOf(next.top));
  else history.replaceState(st, "", pathOf(next.top));
  set(next);
}

function isState(v: unknown): v is HistoryState {
  return v != null && typeof v === "object" && (v as HistoryState).v === 1 && Array.isArray((v as HistoryState).paths);
}

function fromState(st: HistoryState): RouterSnapshot {
  return makeSnapshot(st.paths, st.froms ?? [], st.tabDepth === 1 ? 1 : 0);
}

/** Builds the history for a cold load so Back stays in the app: Today, the parent tab, the detail. */
function synthesise(path: string) {
  const route = parsePath(path);
  if (!route) {
    write("replace", makeSnapshot(["/"], [], 0));
    return;
  }
  const tab = parentTab(route);
  write("replace", makeSnapshot(["/"], [], 0));
  const root = route.kind === "tab" ? pathOf(route) : pathOf(tabRoute(tab));
  if (tab !== "today") write("push", makeSnapshot([root], [], 1));
  if (route.kind !== "tab") write("push", makeSnapshot([root, pathOf(route)], [null], tab === "today" ? 0 : 1));
}

function onPopState(e: PopStateEvent) {
  if (isState(e.state)) {
    const next = fromState(e.state);
    // A sheet's #sheet entry and the one below it share a route: nothing to do.
    if (next.stack.map(pathOf).join("|") !== snap.stack.map(pathOf).join("|") || next.tabDepth !== snap.tabDepth) set(next);
  } else {
    synthesise(location.pathname);
  }
  const after = pendingAfterPop;
  pendingAfterPop = null;
  after?.();
}

/**
 * Starts the router. `forcePath` is the first-launch gate's "/welcome". A reload restores the
 * stack saved in history.state; anything else is synthesised from the URL.
 */
export function startRouter(opts: { forcePath?: string } = {}): void {
  if (!started) {
    started = true;
    window.addEventListener("popstate", onPopState);
    if ("scrollRestoration" in history) history.scrollRestoration = "manual";
  }
  const path = opts.forcePath ?? location.pathname;
  const st = history.state as unknown;
  if (!opts.forcePath && isState(st) && st.paths[st.paths.length - 1] === pathOf(parsePath(path) ?? tabRoute("today"))) {
    set(fromState(st));
    return;
  }
  synthesise(path);
}

/** Test hook. */
export function stopRouterForTests(): void {
  window.removeEventListener("popstate", onPopState);
  started = false;
  pendingAfterPop = null;
  snap = makeSnapshot(["/"], [], 0);
}

function switchTab(tab: TabId, gearTab?: GearTab) {
  if (snap.depth > 0) {
    popToRoot(tab);
    if (gearTab) pendingAfterPopChain(() => switchTab(tab, gearTab));
    return;
  }
  if (tab === snap.tab) {
    if (tab === "gear" && gearTab && gearTab !== snap.gearTab) write("replace", makeSnapshot([pathOf(tabRoute("gear", gearTab))], [], snap.tabDepth));
    return;
  }
  if (tab === "today") {
    if (snap.tabDepth === 1) history.back();
    else write("replace", makeSnapshot(["/"], [], 0));
    return;
  }
  const root = pathOf(tabRoute(tab, gearTab));
  if (snap.tab === "today") write("push", makeSnapshot([root], [], 1));
  else write("replace", makeSnapshot([root], [], snap.tabDepth));
}

function pendingAfterPopChain(fn: () => void) {
  const prev = pendingAfterPop;
  pendingAfterPop = () => {
    prev?.();
    fn();
  };
}

function popToRoot(tab?: TabId) {
  if (snap.depth > 0) {
    if (tab) pendingAfterPopChain(() => switchTab(tab));
    history.go(-snap.depth);
    return;
  }
  if (tab) switchTab(tab);
}

export const nav: Nav = Object.freeze({
  push: (path: string, opts: { from?: SharedFrom } = {}) => {
    const route = parsePath(path);
    if (!route) return;
    if (route.kind === "tab") return switchTab(route.tab, route.gearTab);
    const paths = [...snap.stack.map(pathOf), pathOf(route)];
    const froms = [...snap.froms, opts.from ?? null];
    write("push", makeSnapshot(paths, froms, snap.tabDepth));
  },
  replace: (path: string) => {
    const route = parsePath(path);
    if (!route) return;
    if (route.kind === "tab") {
      if (snap.depth > 0) return popToRoot(route.tab);
      return switchTab(route.tab, route.gearTab);
    }
    const paths = snap.depth > 0 ? [...snap.stack.slice(0, -1).map(pathOf), pathOf(route)] : [...snap.stack.map(pathOf), pathOf(route)];
    const froms = [...snap.froms.slice(0, Math.max(0, snap.depth - 1)), null];
    write(snap.depth > 0 ? "replace" : "push", makeSnapshot(paths, froms, snap.tabDepth));
  },
  back: () => {
    if (snap.depth > 0 || snap.tabDepth === 1) history.back();
    else if (snap.tab !== "today") switchTab("today");
  },
  popToRoot,
  switchTab,
});

export const router = {
  get: (): RouterSnapshot => snap,
  subscribe: (fn: () => void): (() => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },
};

export function useRoute(): RouterSnapshot {
  return useSyncExternalStore(router.subscribe, router.get, router.get);
}

/**
 * Subscribe to one slice (e.g. the tab) so unrelated navigation does not re-render. The selector
 * is rebuilt only when `key` changes (pass the parameter an inline selector closes over).
 */
export function useRouter<T>(selector: (s: RouterSnapshot) => T, key: unknown = null): T {
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const get = useMemo(() => cachedSelector(selector), [key]);
  return useSyncExternalStore(router.subscribe, get, get);
}

function cachedSelector<T>(selector: (s: RouterSnapshot) => T): () => T {
  let last: { s: RouterSnapshot; v: T } | null = null;
  return () => {
    if (last && last.s === snap) return last.v;
    const v = selector(snap);
    last = { s: snap, v: last && Object.is(last.v, v) ? last.v : v };
    return last.v;
  };
}

/** Stable object; never causes renders. */
export function useNav(): Nav {
  return nav;
}
