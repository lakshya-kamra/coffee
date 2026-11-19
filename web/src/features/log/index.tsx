// The Log tab and Brew detail (web-spec §11, WEB-3). Keep the exported signatures (§20.4).
import { catalog } from "@/catalog/catalog";

export { LogScreen } from "./LogScreen";
export { BrewDetailScreen } from "./BrewDetailScreen";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return catalog.load(["brewers", "grinders", "beans", "water"]);
}
