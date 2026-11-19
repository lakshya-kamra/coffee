// Public surface of features/today (web-spec §11 Today, §20.4).
export { TodayScreen } from "./TodayScreen";

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return Promise.resolve();
}
