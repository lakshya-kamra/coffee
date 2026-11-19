// Public surface of features/brew (web-spec §12, §20.4).
export { BrewScreen } from "./BrewScreen";
export { BrewEngine, type EngineState, type EngineSnapshot } from "./engine";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return Promise.resolve();
}
