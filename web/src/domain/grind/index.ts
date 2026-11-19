// Public surface of the grind domain, plus the catalog-bound `scaleFor` (A§3.2 `GrindScale.of`
// with its default catalog argument).
import { catalog } from "@/catalog/catalog";
import { grindScaleOf, type GrinderScaleInput, type GrindScale } from "./grindScale";

export * from "./dialFormat";
export * from "./grindScale";
export * from "./moveSizes";
export * from "./grindLooksTyped";
export { NUMBERED_DIAL_IDS } from "./numberedDials";

/** The scale for a grinder row, using its linked catalog entry when grinders.json is loaded. */
export function scaleFor(g: GrinderScaleInput): GrindScale {
  return grindScaleOf(g, catalog.grinder(g.catalogId));
}
