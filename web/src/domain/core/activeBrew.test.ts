import { describe, expect, it } from "vitest";
import { ABANDONED_AFTER_MS, brewElapsedMs, isAbandoned, updateHoldMs, type BrewClock } from "./activeBrew";

const T0 = 1_800_000_000_000;
const MIN = 60_000;
const running = (startedMinAgo: number, now = T0): BrewClock => ({ startedAtWall: now - startedMinAgo * MIN, accumulatedMs: 0, running: true });
const paused = (atMs: number, pausedMinAgo: number, now = T0): BrewClock => ({ startedAtWall: now - pausedMinAgo * MIN, accumulatedMs: atMs, running: false });

describe("a stored brew in progress", () => {
  it("reads the clock: running counts on from its anchor, paused stands still", () => {
    expect(brewElapsedMs(running(2), T0)).toBe(2 * MIN);
    expect(brewElapsedMs(paused(72_000, 500), T0)).toBe(72_000);
    // A clock set back never runs the brew backwards.
    expect(brewElapsedMs({ startedAtWall: T0 + 5000, accumulatedMs: 1000, running: true }, T0)).toBe(1000);
  });

  it("is abandoned once it runs half an hour past its recipe, or sits paused that long", () => {
    const plan = 210; // 3:30
    expect(isAbandoned(running(3), plan, T0)).toBe(false);
    expect(isAbandoned(running(33), plan, T0)).toBe(false);
    expect(isAbandoned(running(34), plan, T0)).toBe(true);
    expect(isAbandoned(running(14 * 60), plan, T0)).toBe(true);
    expect(isAbandoned(paused(72_000, 5), plan, T0)).toBe(false);
    expect(isAbandoned(paused(72_000, 31), plan, T0)).toBe(true);
    // A long recipe (cold brew steep) is measured against its own length.
    expect(isAbandoned(running(60), 12 * 3600, T0)).toBe(false);
    // No recipe to measure against: ten minutes stand in.
    expect(isAbandoned(running(39), null, T0)).toBe(false);
    expect(isAbandoned(running(41), null, T0)).toBe(true);
  });

  it("holds an app update back only for a brew that is running and still plausible", () => {
    const plan = 210;
    expect(updateHoldMs(null, plan, T0)).toBe(0);
    expect(updateHoldMs(running(1), plan, T0)).toBe(plan * 1000 + ABANDONED_AFTER_MS - MIN);
    expect(updateHoldMs(running(14 * 60), plan, T0)).toBe(0); // started by mistake last night
    expect(updateHoldMs(paused(72_000, 1), plan, T0)).toBe(0); // a reload resumes a paused brew as it was
  });
});
