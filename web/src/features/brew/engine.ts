/**
 * The brew timer engine (web-spec §12.1): the web form of Android's TimerAnchor + BrewViewModel
 * timer. Framework-free and driven by an injectable clock, so tests run on fake time.
 *
 * Elapsed time is always derived from performance.now() anchors; nothing accumulates per tick.
 * Listeners hear only discrete changes (running, step, done, bed exposed, seeks), never the
 * clock itself: the ring and digits read elapsedMs() in the frame loop.
 */
import { totalSec, type Recipe } from "@/domain/core/recipe";

export interface Clock {
  perf(): number;
  wall(): number;
}

export const realClock: Clock = {
  perf: () => performance.now(),
  wall: () => Date.now(),
};

export interface Anchor {
  accumulatedMs: number;
  startedAtPerf: number;
  startedAtWall: number;
  running: boolean;
}

export const elapsedAt = (a: Anchor, nowPerf: number): number => (a.running ? a.accumulatedMs + Math.max(0, nowPerf - a.startedAtPerf) : a.accumulatedMs);

export interface EngineState {
  /** Setup has ended (Start was pressed); false again after reset(). */
  readonly started: boolean;
  readonly running: boolean;
  readonly stepIndex: number;
  /** The step the clock is in; differs from stepIndex only while auto-advance is off. */
  readonly clockStepIndex: number;
  readonly done: boolean;
  /** Planned end of the last pour step left behind, for the drawdown number (Android onTick). */
  readonly lastPourEndSec: number | null;
  readonly bedExposedSec: number | null;
  /** Elapsed at the last discrete change ("Paused at 1:12"). */
  readonly accumulatedMs: number;
  /** Bumps on every seek (±5 s, skip, re-anchor) so the ring can glide. */
  readonly seekSerial: number;
}

/** What a reload needs (web-spec §12.5); stored by the screen as kv activeBrew. */
export interface EngineSnapshot {
  startedAtWall: number;
  accumulatedMs: number;
  running: boolean;
  stepIndex: number;
  lastPourEndSec: number | null;
  bedExposedSec: number | null;
}

export interface BrewResult {
  totalTimeSec: number;
  drawdownSec: number | null;
}

/** Index of the step the clock is in: the last one whose start has passed (0 before any). */
export function stepIndexAt(r: Recipe, sec: number): number {
  let index = -1;
  r.steps.forEach((s, i) => {
    if (sec >= s.startSec) index = i;
  });
  return Math.max(0, index);
}

export class BrewEngine {
  private recipe: Recipe;
  private readonly clock: Clock;
  private autoAdvance: boolean;
  private anchor: Anchor;
  private st: EngineState;
  private readonly listeners = new Set<(s: EngineState) => void>();
  private readonly stepListeners = new Set<(index: number) => void>();

  constructor(recipe: Recipe, opts: { clock?: Clock; autoAdvance?: boolean } = {}) {
    this.recipe = recipe;
    this.clock = opts.clock ?? realClock;
    this.autoAdvance = opts.autoAdvance ?? true;
    this.anchor = { accumulatedMs: 0, startedAtPerf: 0, startedAtWall: 0, running: false };
    this.st = { started: false, running: false, stepIndex: 0, clockStepIndex: 0, done: false, lastPourEndSec: null, bedExposedSec: null, accumulatedMs: 0, seekSerial: 0 };
  }

  // ------------------------------------------------------------------ reading

  get state(): EngineState {
    return this.st;
  }

  getRecipe(): Recipe {
    return this.recipe;
  }

  elapsedMs(): number {
    return elapsedAt(this.anchor, this.clock.perf());
  }

  totalMs(): number {
    return Math.max(1, totalSec(this.recipe)) * 1000;
  }

  /** ms until the next step boundary (or the end of the plan), null when nothing is ahead. */
  msToNextBoundary(): number | null {
    const e = this.elapsedMs();
    const ahead = [...this.recipe.steps.map((s) => s.startSec * 1000), this.totalMs()].filter((t) => t > e);
    return ahead.length ? Math.min(...ahead) - e : null;
  }

  snapshot(): EngineSnapshot {
    // The wall-clock anchor of "now", so a reload can resume from Date.now() alone.
    const now = this.clock.wall();
    const e = this.elapsedMs();
    return {
      startedAtWall: this.anchor.running ? now - (e - this.anchor.accumulatedMs) : now,
      accumulatedMs: this.anchor.running ? this.anchor.accumulatedMs : e,
      running: this.anchor.running,
      stepIndex: this.st.stepIndex,
      lastPourEndSec: this.st.lastPourEndSec,
      bedExposedSec: this.st.bedExposedSec,
    };
  }

  // ------------------------------------------------------------------ listening

  subscribe(fn: (s: EngineState) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /** Fires once per step change while running forward (the cue: vibration, sound, announcement). */
  onStep(fn: (index: number) => void): () => void {
    this.stepListeners.add(fn);
    return () => this.stepListeners.delete(fn);
  }

  private set(patch: Partial<EngineState>) {
    const next = { ...this.st, ...patch };
    const keys = Object.keys(patch) as (keyof EngineState)[];
    if (keys.every((k) => Object.is(next[k], this.st[k]))) return;
    this.st = next;
    for (const l of this.listeners) l(next);
  }

  // ------------------------------------------------------------------ setup

  /** Dose changes rescale the recipe in setup; the timings never change. */
  setRecipe(r: Recipe): void {
    this.recipe = r;
  }

  setAutoAdvance(on: boolean): void {
    this.autoAdvance = on;
    this.tick();
  }

  /** Resumes a brew saved before a reload, from the wall clock. */
  restore(s: EngineSnapshot): void {
    const nowWall = this.clock.wall();
    const acc = s.running ? s.accumulatedMs + Math.max(0, nowWall - s.startedAtWall) : s.accumulatedMs;
    this.anchor = { accumulatedMs: acc, startedAtPerf: this.clock.perf(), startedAtWall: nowWall, running: s.running };
    const clockIdx = stepIndexAt(this.recipe, Math.floor(acc / 1000));
    this.set({
      started: true,
      running: s.running,
      stepIndex: this.autoAdvance ? clockIdx : Math.min(s.stepIndex, this.recipe.steps.length - 1),
      clockStepIndex: clockIdx,
      lastPourEndSec: s.lastPourEndSec,
      bedExposedSec: s.bedExposedSec,
      accumulatedMs: acc,
      done: acc / 1000 >= totalSec(this.recipe),
    });
  }

  // ------------------------------------------------------------------ timer

  start(): void {
    this.set({ started: true });
    this.resume();
  }

  resume(): void {
    if (this.anchor.running) return;
    this.anchor = { ...this.anchor, startedAtPerf: this.clock.perf(), startedAtWall: this.clock.wall(), running: true };
    this.set({ running: true, accumulatedMs: this.anchor.accumulatedMs });
    this.tick();
  }

  pause(): void {
    if (!this.anchor.running) return;
    const e = this.elapsedMs();
    this.anchor = { ...this.anchor, accumulatedMs: e, running: false };
    this.set({ running: false, accumulatedMs: e });
  }

  toggle(): void {
    if (this.anchor.running) this.pause();
    else this.resume();
  }

  private seekTo(ms: number) {
    this.anchor = { accumulatedMs: Math.max(0, ms), startedAtPerf: this.clock.perf(), startedAtWall: this.clock.wall(), running: this.anchor.running };
    this.set({ seekSerial: this.st.seekSerial + 1, accumulatedMs: this.anchor.accumulatedMs });
    this.tick();
  }

  /** ±5 s. Starts from the true elapsed time, not the stored offset. */
  addSeconds(delta: number): void {
    this.seekTo(this.elapsedMs() + delta * 1000);
  }

  /** Jumps the clock to the next step's start (and the step card with it). */
  skipToNextStep(): void {
    const next = this.recipe.steps[this.st.stepIndex + 1];
    if (!next) return;
    if (!this.autoAdvance) this.advanceTo(this.st.stepIndex + 1);
    this.seekTo(Math.max(this.elapsedMs(), next.startSec * 1000));
  }

  /** Auto-advance off: the step card waits for this tap. The clock is not moved. */
  advanceStep(): void {
    if (this.st.stepIndex + 1 >= this.recipe.steps.length) return;
    this.advanceTo(this.st.stepIndex + 1);
  }

  private advanceTo(index: number) {
    const prev = this.recipe.steps[this.st.stepIndex];
    this.set({ stepIndex: index, lastPourEndSec: prev?.targetWaterG != null ? prev.endSec : this.st.lastPourEndSec });
    if (this.anchor.running) for (const l of this.stepListeners) l(index);
  }

  /** Tapped when the bed is first exposed. This is the honest drawdown number. */
  markBedExposed(): void {
    if (this.st.bedExposedSec != null) return;
    this.set({ bedExposedSec: Math.floor(this.elapsedMs() / 1000) });
  }

  reset(): void {
    this.anchor = { accumulatedMs: 0, startedAtPerf: this.clock.perf(), startedAtWall: this.clock.wall(), running: false };
    this.set({ started: false, running: false, stepIndex: 0, clockStepIndex: 0, done: false, lastPourEndSec: null, bedExposedSec: null, accumulatedMs: 0 });
  }

  /** Stops the clock and returns what the brew row needs (A§2.5 finish()). */
  finish(): BrewResult {
    const totalTimeSec = Math.floor(this.elapsedMs() / 1000);
    this.pause();
    const { bedExposedSec: bed, lastPourEndSec: pourEnd } = this.st;
    return { totalTimeSec, drawdownSec: bed != null && pourEnd != null ? Math.max(0, bed - pourEnd) : null };
  }

  /**
   * Step detection, run every frame while visible and from a timer at the next boundary while
   * hidden. Crossing a boundary updates the step once and fires the cue (Android onTick).
   */
  tick(): void {
    const sec = Math.floor(this.elapsedMs() / 1000);
    const clockIdx = stepIndexAt(this.recipe, sec);
    const done = sec >= totalSec(this.recipe);
    if (!this.autoAdvance) {
      this.set({ clockStepIndex: clockIdx, done });
      return;
    }
    if (clockIdx !== this.st.stepIndex) {
      const prev = this.recipe.steps[this.st.stepIndex];
      const lastPourEnd = prev?.targetWaterG != null ? prev.endSec : this.st.lastPourEndSec;
      this.set({ stepIndex: clockIdx, clockStepIndex: clockIdx, lastPourEndSec: lastPourEnd, done });
      if (this.anchor.running) for (const l of this.stepListeners) l(clockIdx);
    } else {
      this.set({ clockStepIndex: clockIdx, done });
    }
  }

  /**
   * performance.now() may not advance while a device sleeps. On becoming visible again, compare
   * the wall clock with perf; if they differ by more than a second, re-anchor to the wall clock.
   * Keeps a 12-hour cold brew honest. Returns true when it re-anchored.
   */
  reanchorToWall(): boolean {
    if (!this.anchor.running) return false;
    const wallElapsed = this.anchor.accumulatedMs + Math.max(0, this.clock.wall() - this.anchor.startedAtWall);
    const perfElapsed = this.elapsedMs();
    if (Math.abs(wallElapsed - perfElapsed) <= 1000) return false;
    this.seekTo(wallElapsed);
    return true;
  }
}
