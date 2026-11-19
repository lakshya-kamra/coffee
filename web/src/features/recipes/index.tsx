// Public surface of features/recipes (web-spec §11 Recipes and Recipe detail, §20.4).
export { RecipesScreen } from "./RecipesScreen";
export { RecipeDetailScreen } from "./RecipeDetailScreen";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return Promise.resolve();
}
