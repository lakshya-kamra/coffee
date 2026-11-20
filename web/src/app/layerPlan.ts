/**
 * Where each detail on the stack renders (v4 §2.3 rule 1, D4). Pure, so the DetailHost decision
 * and its resize behaviour are tested without a DOM:
 * - "pane": inside its own tab's detail pane. Only the detail at stack index 1, only when it is
 *   pane-able, belongs to the tab underneath, that tab has a mounted ListDetail, and the window is
 *   expanded or wider.
 * - "focus": brew, review, onboarding and the recipe editor cover everything, the nav included.
 * - "layer": everything else, today's full-content layer (beside the rail at medium and up).
 */
import { FOCUS_KINDS, PANEABLE_KINDS, parentTab, pathOf, type DetailRoute, type Route, type TabId } from "./routes";
import { atLeast, type WidthClass } from "./windowClass";

export type Placement = "pane" | "layer" | "focus";

export interface PlannedDetail {
  readonly route: DetailRoute;
  /** Index in stack.slice(1) (0 = the first detail above the tab). */
  readonly index: number;
  /** `${index}:${path}`: the same key whichever way it renders, so a resize keeps React state above it. */
  readonly key: string;
  readonly placement: Placement;
}

export function placementFor(route: DetailRoute, index: number, tab: TabId, width: WidthClass, paneHosts: ReadonlySet<TabId>): Placement {
  if (FOCUS_KINDS.has(route.kind)) return "focus";
  if (index === 0 && PANEABLE_KINDS.has(route.kind) && parentTab(route) === tab && paneHosts.has(tab) && atLeast(width, "expanded")) return "pane";
  return "layer";
}

export function planDetails(stack: readonly Route[], tab: TabId, width: WidthClass, paneHosts: ReadonlySet<TabId>): PlannedDetail[] {
  const details = stack.slice(1) as DetailRoute[];
  return details.map((route, index) => ({ route, index, key: `${index}:${pathOf(route)}`, placement: placementFor(route, index, tab, width, paneHosts) }));
}

/** The nav is covered (inert, hidden from AT) under a focus layer, or under any layer on compact. */
export function navCovered(plan: readonly PlannedDetail[], width: WidthClass): boolean {
  return plan.some((d) => d.placement === "focus" || (d.placement === "layer" && width === "compact"));
}

/** Real layers only (what DetailHost mounts as fixed sections). */
export function layersOf(plan: readonly PlannedDetail[]): PlannedDetail[] {
  return plan.filter((d) => d.placement !== "pane");
}

/** The pane-hosted detail of `tab`, if any. */
export function paneRouteOf(plan: readonly PlannedDetail[]): PlannedDetail | null {
  return plan.find((d) => d.placement === "pane") ?? null;
}

/**
 * Keys whose placement flipped between two plans (a resize across 840, or a ListDetail mounting):
 * those enter or leave without animation (§2.9 rule 2, §2.10).
 */
export function flippedKeys(prev: readonly PlannedDetail[], next: readonly PlannedDetail[]): Set<string> {
  const before = new Map(prev.map((d) => [d.key, d.placement === "pane"]));
  const out = new Set<string>();
  for (const d of next) {
    const was = before.get(d.key);
    if (was != null && was !== (d.placement === "pane")) out.add(d.key);
  }
  return out;
}
