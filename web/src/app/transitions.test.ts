// @vitest-environment jsdom
// The tab switch must give its transition token back when a newer switch interrupts it. Motion's
// stopped animations never settle their promise (checked against the installed 14.14), which the
// fake below copies: only finish() resolves, stop() does nothing more.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

interface Fake {
  el: HTMLElement;
  stopped: boolean;
  finish: () => void;
}
const fakes: Fake[] = [];

vi.mock("motion", () => ({
  animate: (el: HTMLElement) => {
    let resolve!: () => void;
    const finished = new Promise<void>((r) => (resolve = r));
    const fake: Fake = { el, stopped: false, finish: () => resolve() };
    fakes.push(fake);
    return {
      stop: () => {
        fake.stopped = true;
      },
      then: (ok: () => void, bad: () => void) => finished.then(ok, bad),
    };
  },
}));

import { afterTransition, beginTransition, isTransitioning, MAX_TRANSITION_WAIT_MS, resetTransitionsForTests } from "./transitionState";
import { showLayerNow, switchTabLayers } from "./transitions";

const layer = () => document.createElement("div");

beforeEach(() => {
  vi.useFakeTimers();
  fakes.length = 0;
  resetTransitionsForTests();
});
afterEach(() => vi.useRealTimers());

describe("switchTabLayers", () => {
  it("ends its transition when both animations finish", async () => {
    const [today, log] = [layer(), layer()];
    showLayerNow(today, [log]);
    const done = switchTabLayers(today, log, 1);
    expect(isTransitioning()).toBe(true);
    fakes.forEach((f) => f.finish());
    await done;
    expect(isTransitioning()).toBe(false);
    expect(today.dataset.state).toBe("hidden");
    expect(log.dataset.state).toBe("active");
  });

  it("ends an interrupted switch's transition although its animations never settle", async () => {
    const [today, log] = [layer(), layer()];
    showLayerNow(today, [log]);
    const first = switchTabLayers(today, log, 1);
    const firstRuns = [...fakes];
    // Tapped back before the first switch settled.
    const second = switchTabLayers(log, today, -1);
    expect(firstRuns.every((f) => f.stopped)).toBe(true);
    await first; // resolves on supersede, not only on a natural finish
    expect(isTransitioning()).toBe(true); // the second switch is still running

    const saved = vi.fn();
    afterTransition(saved);
    fakes.filter((f) => !f.stopped).forEach((f) => f.finish());
    await second;
    expect(isTransitioning()).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(saved).toHaveBeenCalledTimes(1);
    expect(today.dataset.state).toBe("active");
    expect(log.dataset.state).toBe("hidden");
  });

  it("three quick switches leave nothing running", async () => {
    const [today, log, insights] = [layer(), layer(), layer()];
    showLayerNow(today, [log, insights]);
    void switchTabLayers(today, log, 1);
    void switchTabLayers(log, insights, 1);
    const last = switchTabLayers(insights, today, -1);
    fakes.filter((f) => !f.stopped).forEach((f) => f.finish());
    await last;
    await vi.advanceTimersByTimeAsync(1);
    expect(isTransitioning()).toBe(false);
  });

  it("showLayerNow releases a switch it cuts short", async () => {
    const [today, log] = [layer(), layer()];
    showLayerNow(today, [log]);
    const done = switchTabLayers(today, log, 1);
    showLayerNow(today, [log]);
    await done;
    expect(isTransitioning()).toBe(false);
  });
});

describe("afterTransition", () => {
  it("waits for the running transition, then runs on the next task", async () => {
    const end = beginTransition();
    const cb = vi.fn();
    afterTransition(cb);
    await vi.advanceTimersByTimeAsync(50);
    expect(cb).not.toHaveBeenCalled();
    end();
    await vi.advanceTimersByTimeAsync(1);
    expect(cb).toHaveBeenCalledTimes(1);
  });

  it("runs anyway once a transition has been stuck for the maximum wait", async () => {
    beginTransition(); // never ended
    const cb = vi.fn();
    afterTransition(cb);
    await vi.advanceTimersByTimeAsync(MAX_TRANSITION_WAIT_MS - 1);
    expect(cb).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2);
    expect(cb).toHaveBeenCalledTimes(1);
    // A later callback gets its own deadline.
    const later = vi.fn();
    afterTransition(later);
    await vi.advanceTimersByTimeAsync(MAX_TRANSITION_WAIT_MS + 1);
    expect(later).toHaveBeenCalledTimes(1);
  });
});
