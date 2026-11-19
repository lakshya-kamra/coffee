/**
 * Routes (web-spec §6.1): URL ⇄ Route, pure and tested. URLs carry uids and recipe ids, never
 * numeric database ids. "new" is a reserved recipe id.
 */

export type TabId = "today" | "recipes" | "log" | "insights" | "gear";
export type GearTab = "beans" | "grinders" | "app";
export type SharedFrom = "home" | "next" | "recipes";

export type Route =
  | { kind: "tab"; tab: TabId; gearTab?: GearTab }
  | { kind: "recipe"; id: string }
  | { kind: "recipeEdit"; id: string | null; mode: "new" | "edit" | "duplicate" }
  | { kind: "brew"; recipeId: string }
  | { kind: "review"; brewUid: string }
  | { kind: "brewDetail"; brewUid: string }
  | { kind: "onboarding" };

export type DetailRoute = Exclude<Route, { kind: "tab" }>;

export const TABS: readonly TabId[] = ["today", "recipes", "log", "insights", "gear"];
/** Bottom-bar labels, as Android's CoffeeNav TABS. */
export const TAB_LABELS: Readonly<Record<TabId, string>> = { today: "Today", recipes: "Recipes", log: "Log", insights: "Insights", gear: "Gear" };
export const RESERVED_RECIPE_IDS: ReadonlySet<string> = new Set(["new"]);

const TAB_PATH: Record<TabId, string> = { today: "/", recipes: "/recipes", log: "/log", insights: "/insights", gear: "/gear" };

function seg(s: string | undefined): string | null {
  if (s == null || s === "") return null;
  try {
    return decodeURIComponent(s);
  } catch {
    return null;
  }
}

/** null for an unknown path (the shell renders Today and replaces the URL with "/"). */
export function parsePath(pathname: string): Route | null {
  const clean = pathname.replace(/\/+$/, "") || "/";
  if (clean === "/") return { kind: "tab", tab: "today" };
  const parts = clean.split("/").slice(1);
  const [a, b, c, ...rest] = parts;
  if (rest.length > 0) return null;
  switch (a) {
    case "recipes": {
      if (b == null) return c == null ? { kind: "tab", tab: "recipes" } : null;
      if (b === "new") return c == null ? { kind: "recipeEdit", id: null, mode: "new" } : null;
      const id = seg(b);
      if (!id) return null;
      if (c == null) return { kind: "recipe", id };
      if (c === "edit") return { kind: "recipeEdit", id, mode: "edit" };
      if (c === "duplicate") return { kind: "recipeEdit", id, mode: "duplicate" };
      return null;
    }
    case "log": {
      if (b == null) return { kind: "tab", tab: "log" };
      const uid = seg(b);
      return uid && c == null ? { kind: "brewDetail", brewUid: uid } : null;
    }
    case "insights":
      return b == null ? { kind: "tab", tab: "insights" } : null;
    case "gear":
      if (c != null) return null;
      if (b == null) return { kind: "tab", tab: "gear", gearTab: "beans" };
      if (b === "grinders" || b === "app" || b === "beans") return { kind: "tab", tab: "gear", gearTab: b };
      return null;
    case "brew": {
      const id = seg(b);
      return id && c == null ? { kind: "brew", recipeId: id } : null;
    }
    case "review": {
      const uid = seg(b);
      return uid && c == null ? { kind: "review", brewUid: uid } : null;
    }
    case "welcome":
      return b == null ? { kind: "onboarding" } : null;
    default:
      return null;
  }
}

const enc = encodeURIComponent;

export function pathOf(r: Route): string {
  switch (r.kind) {
    case "tab":
      if (r.tab === "gear" && r.gearTab && r.gearTab !== "beans") return `/gear/${r.gearTab}`;
      return TAB_PATH[r.tab];
    case "recipe":
      return `/recipes/${enc(r.id)}`;
    case "recipeEdit":
      return r.mode === "new" || r.id == null ? "/recipes/new" : `/recipes/${enc(r.id)}/${r.mode}`;
    case "brew":
      return `/brew/${enc(r.recipeId)}`;
    case "review":
      return `/review/${enc(r.brewUid)}`;
    case "brewDetail":
      return `/log/${enc(r.brewUid)}`;
    case "onboarding":
      return "/welcome";
  }
}

/** The tab a detail belongs to, for cold-loaded deep links (Back stays in the app). */
export function parentTab(r: Route): TabId {
  switch (r.kind) {
    case "tab":
      return r.tab;
    case "recipe":
    case "recipeEdit":
      return "recipes";
    case "brewDetail":
      return "log";
    case "brew":
    case "review":
    case "onboarding":
      return "today";
  }
}

export function tabRoute(tab: TabId, gearTab?: GearTab): Route {
  return tab === "gear" ? { kind: "tab", tab, gearTab: gearTab ?? "beans" } : { kind: "tab", tab };
}
