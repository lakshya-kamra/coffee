/**
 * THE EXTENSION POINT for WEB-2..5. Every screen is reached through this file, one lazily loaded
 * chunk per feature folder (src/features/<name>/index.tsx). A package normally only edits its
 * own folder; when it needs the shell to do more, it APPENDS here (a new detail kind needs a
 * routes.ts entry too, which WEB-6 coordinates):
 *   - `startupTasks`: idle-time work after first paint (WEB-5 appends nothing: the linker is
 *     already wired below through features/backup).
 *   - `prefetchers`: chunk + data warm-up per route kind.
 * Do not import feature internals here; only each folder's public index.
 */
import { lazy, type ComponentType, type LazyExoticComponent } from "react";
import type { DetailRoute, GearTab, SharedFrom, TabId } from "./routes";

type Lazy<P> = LazyExoticComponent<ComponentType<P>>;

function screen<M, P>(load: () => Promise<M>, pick: (m: M) => ComponentType<P>): Lazy<P> {
  return lazy(() => load().then((m) => ({ default: pick(m) })));
}

const loadToday = () => import("@/features/today");
const loadRecipes = () => import("@/features/recipes");
const loadLog = () => import("@/features/log");
const loadInsights = () => import("@/features/insights");
const loadGear = () => import("@/features/gear");
const loadBrew = () => import("@/features/brew");
const loadReview = () => import("@/features/review");
const loadOnboarding = () => import("@/features/onboarding");
const loadRecipeEditor = () => import("@/features/recipe-editor");

export const TabScreens: { [K in Exclude<TabId, "gear">]: Lazy<object> } & { gear: Lazy<{ tab: GearTab }> } = {
  today: screen(loadToday, (m) => m.TodayScreen),
  recipes: screen(loadRecipes, (m) => m.RecipesScreen),
  log: screen(loadLog, (m) => m.LogScreen),
  insights: screen(loadInsights, (m) => m.InsightsScreen),
  gear: screen(loadGear, (m) => m.GearScreen),
};

export const DetailScreens = {
  recipe: screen(loadRecipes, (m) => m.RecipeDetailScreen),
  recipeEdit: screen(loadRecipeEditor, (m) => m.RecipeEditorScreen),
  brew: screen(loadBrew, (m) => m.BrewScreen),
  review: screen(loadReview, (m) => m.ReviewScreen),
  brewDetail: screen(loadLog, (m) => m.BrewDetailScreen),
  onboarding: screen(loadOnboarding, (m) => m.OnboardingScreen),
};

/** Renders the screen for a detail route. `from` is the shared-element prefix for recipes. */
export function DetailScreen({ route, from }: { route: DetailRoute; from: SharedFrom | undefined }) {
  switch (route.kind) {
    case "recipe":
      return <DetailScreens.recipe id={route.id} from={from ?? "recipes"} />;
    case "recipeEdit":
      return <DetailScreens.recipeEdit id={route.id} mode={route.mode} />;
    case "brew":
      return <DetailScreens.brew recipeId={route.recipeId} />;
    case "review":
      return <DetailScreens.review brewUid={route.brewUid} />;
    case "brewDetail":
      return <DetailScreens.brewDetail brewUid={route.brewUid} />;
    case "onboarding":
      return <DetailScreens.onboarding />;
  }
}

/** Module loaders by tab, so idle time can fetch every tab's chunk before it is opened. */
export const tabChunks: Record<TabId, () => Promise<unknown>> = {
  today: loadToday,
  recipes: loadRecipes,
  log: loadLog,
  insights: loadInsights,
  gear: loadGear,
};

/** Chunk + data warm-up per route kind, called on pointerdown of anything that navigates there. */
export const prefetchers: Record<DetailRoute["kind"] | TabId, () => Promise<unknown>> = {
  today: () => loadToday().then((m) => m.prefetch()),
  recipes: () => loadRecipes().then((m) => m.prefetch()),
  log: () => loadLog().then((m) => m.prefetch()),
  insights: () => loadInsights().then((m) => m.prefetch()),
  gear: () => loadGear().then((m) => m.prefetch()),
  recipe: () => loadRecipes().then((m) => m.prefetch()),
  recipeEdit: () => loadRecipeEditor().then((m) => m.prefetch()),
  brew: () => loadBrew().then((m) => m.prefetch()),
  review: () => loadReview().then((m) => m.prefetch()),
  brewDetail: () => loadLog().then((m) => m.prefetch()),
  onboarding: () => loadOnboarding().then((m) => m.prefetch()),
};

/** Idle-time work after first paint (web-spec §6.8 step 5). Each task must be idempotent. */
export const startupTasks: (() => Promise<void>)[] = [
  // WEB-5: catalog linking for imported rows (A§6 CatalogLinker).
  () => import("@/features/backup").then((m) => m.runStartupLinking()),
];
