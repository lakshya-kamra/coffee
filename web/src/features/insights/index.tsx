// The Insights tab (web-spec §11, WEB-3). Keep the exported signatures (§20.4).
import { catalog } from "@/catalog/catalog";

export { InsightsScreen } from "./InsightsScreen";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return catalog.load(["brewers", "grinders", "providers"]);
}
