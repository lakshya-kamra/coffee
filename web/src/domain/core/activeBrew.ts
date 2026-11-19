/**
 * Reading the stored "brew in progress" record (web-spec §12.5). A brew started by mistake, or
 * walked away from, stays in kv until it is finished or discarded; these say when it has stopped
 * being a brew someone is making, so the app can offer to discard it and stop holding things
 * back on its account. Pure.
 */

/** The clock fields of a stored brew (data/repo's ActiveBrew has them). */
export interface BrewClock {
  /** Wall time of the last discrete change (start, pause, step, seek). */
  readonly startedAtWall: number;
  /** Elapsed at that change. */
  readonly accumulatedMs: number;
  readonly running: boolean;
}

/** Past this long over the plan, or paused this long, nobody is still brewing. */
export const ABANDONED_AFTER_MS = 30 * 60_000;
/** A plan to measure against when the recipe is gone. */
const FALLBACK_PLAN_SEC = 600;

/** The brew clock's reading at `now`. */
export function brewElapsedMs(a: BrewClock, now: number): number {
  return a.running ? a.accumulatedMs + Math.max(0, now - a.startedAtWall) : a.accumulatedMs;
}

/** True once a running brew is far past its recipe's length, or a paused one has been left alone. */
export function isAbandoned(a: BrewClock, planSec: number | null, now: number): boolean {
  if (!a.running) return now - a.startedAtWall > ABANDONED_AFTER_MS;
  return brewElapsedMs(a, now) > (planSec ?? FALLBACK_PLAN_SEC) * 1000 + ABANDONED_AFTER_MS;
}

/**
 * How long a waiting app update stays out of the way: only while a brew is running and still
 * plausibly being made. 0 when the prompt may show now.
 */
export function updateHoldMs(a: BrewClock | null, planSec: number | null, now: number): number {
  if (!a || !a.running) return 0;
  const until = (planSec ?? FALLBACK_PLAN_SEC) * 1000 + ABANDONED_AFTER_MS - brewElapsedMs(a, now);
  return Math.max(0, until);
}
