// Public surface of features/review (web-spec §11 Review, §20.4).
export { ReviewScreen } from "./ReviewScreen";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return Promise.resolve();
}
