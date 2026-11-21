/**
 * The seek glide: after ±5 s or Next step the clock digits jump to the new time at once, while
 * the ring and the journey line ease there over GLIDE_MS. The glide is only an offset added to
 * what is drawn; the engine's elapsed time (steps, cues, the log) is never touched by it.
 *
 * It used to be a Motion spring on a MotionValue: `seek.set(-5000)` then `animate(seek, 0)`.
 * The jump in set() reads to Motion as a velocity of about -170 000 per second (5000 over its
 * 30 ms velocity window), the spring inherits it, and the ring ran seconds BACKWARD before
 * turning forward; rapid taps compounded it (worst seen: 21 s back). A tween from a known offset
 * has no velocity to inherit, and on an emphasized-decelerate curve (monotonic, no overshoot)
 * the drawn time only ever moves the way the seek went.
 */
import { cubicBezier } from "motion";
import { EASE_DECEL } from "@/design/motion";

/** Long enough to read as travel, short enough to feel like the tap did it. */
export const GLIDE_MS = 300;

/** Motion.kt's emphasized decelerate: leaves fast (the tap answers at once), lands softly. */
export const glideEase: (t: number) => number = cubicBezier(...EASE_DECEL);

/** How far the drawing sits from the true clock; the ring and journey line read `get()` per frame. */
export interface SeekOffset {
  get(): number;
}

export class SeekGlide implements SeekOffset {
  private from = 0;
  private startedAt = 0;
  private duration = 0;

  constructor(private readonly now: () => number = () => performance.now()) {}

  /** The drawn-minus-true offset in ms at `at` (performance.now() time). Zero once settled. */
  offsetAt(at: number): number {
    if (this.duration <= 0) return 0;
    const k = (at - this.startedAt) / this.duration;
    if (k >= 1) return 0;
    return this.from * (1 - glideEase(Math.max(0, k)));
  }

  get(): number {
    return this.offsetAt(this.now());
  }

  /** True while a glide is still under way. */
  active(): boolean {
    return this.offsetAt(this.now()) !== 0;
  }

  /** Starts a glide from `offsetMs` back to zero; reduced motion snaps (no glide at all). */
  retarget(offsetMs: number, reduced = false): void {
    if (reduced || !Number.isFinite(offsetMs) || Math.abs(offsetMs) < 0.5) {
      this.duration = 0;
      this.from = 0;
      return;
    }
    this.from = offsetMs;
    this.startedAt = this.now();
    this.duration = GLIDE_MS;
  }
}

/**
 * Moves the clock with `move` (an engine seek) and glides the drawing from wherever it was drawn
 * a moment ago, so a tap during a glide carries on from the drawn position instead of jumping.
 */
export function glideSeek(clock: { elapsedMs(): number }, glide: SeekGlide, move: () => void, reduced: boolean): void {
  const drawn = clock.elapsedMs() + glide.get();
  move();
  glide.retarget(drawn - clock.elapsedMs(), reduced);
}
