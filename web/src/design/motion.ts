/**
 * Motion tokens (web-spec §5.4). Compose `spring(dampingRatio ζ, stiffness k)` has mass 1, so
 * Motion's equivalent is { stiffness: k, damping: 2·ζ·√k, mass: 1 }. The (ζ, k) table is copied
 * from Android's ui/theme/Motion.kt (and the 120 Hz set from the jank audit §3.3); motion.test.ts
 * checks it against Motion.kt so a PERF change there fails until this table follows.
 */

export interface SpringSource {
  /** Damping ratio ζ. Spatial movement always uses ζ ≥ 0.9 so overshoot stays under a pixel. */
  readonly zeta: number;
  /** Stiffness k. */
  readonly k: number;
  /** Names this token has in Motion.kt (function or val), used by the parity test. */
  readonly kotlin: readonly string[];
  /** Pixel-valued (0.5 px rest) or a 0..1 quantity such as opacity (≈ 1/255). */
  readonly kind: "px" | "unit";
}

export const SPRINGS = {
  /** Motion.kt settle() (PERF's 120 Hz retune): small things settling (chips, toggle knobs). */
  settle: { zeta: 0.9, k: 500, kotlin: ["settle"], kind: "px" },
  /** Motion.kt bounce(): the tab icon scale only. */
  bounce: { zeta: 0.55, k: 320, kotlin: ["bounce"], kind: "unit" },
  /** Motion.kt heavy(): ring pour-pulse amount, mesh intensity. */
  heavy: { zeta: 0.9, k: 180, kotlin: ["heavy"], kind: "unit" },
  /** Motion.kt quick(): press states when JS-driven. */
  quick: { zeta: 1, k: 900, kotlin: ["quick"], kind: "unit" },
  /** Audit §3.3 SpatialFast: outgoing tab drift, sheet close. */
  spatialFast: { zeta: 0.9, k: 1400, kotlin: ["SpatialFast", "spatialFast"], kind: "px" },
  /** Audit §3.3 SpatialDefault: anything that crosses the screen. */
  spatialDefault: { zeta: 0.9, k: 700, kotlin: ["SpatialDefault", "spatialDefault"], kind: "px" },
  /** Audit §3.3 SpatialSlow: rare large moves. */
  spatialSlow: { zeta: 0.9, k: 300, kotlin: ["SpatialSlow", "spatialSlow"], kind: "px" },
  /** Audit §3.3 EffectsFast: outgoing opacity. */
  effectsFast: { zeta: 1, k: 3800, kotlin: ["EffectsFast", "effectsFast"], kind: "unit" },
  /** Audit §3.3 EffectsDefault: incoming opacity, scrims. */
  effectsDefault: { zeta: 1, k: 1600, kotlin: ["EffectsDefault", "effectsDefault"], kind: "unit" },
  /** Audit §3.3 EffectsSlow: mood crossfades. */
  effectsSlow: { zeta: 1, k: 800, kotlin: ["EffectsSlow", "effectsSlow"], kind: "unit" },
  /** Audit §3.3 TabSwitch: a 0..1 fade-through progress. */
  tabSwitch: { zeta: 1, k: 700, kotlin: ["TabSwitch", "tabSwitch"], kind: "unit" },
  /** Audit Bounds: the shared-element flight. */
  bounds: { zeta: 0.9, k: 700, kotlin: ["Bounds"], kind: "px" },
} as const satisfies Record<string, SpringSource>;

export type SpringName = keyof typeof SPRINGS;

export interface SpringTransition {
  readonly type: "spring";
  readonly stiffness: number;
  readonly damping: number;
  readonly mass: 1;
  readonly restDelta: number;
  readonly restSpeed: number;
}

/** Motion damping for a Compose (ζ, k) pair at mass 1. */
export function dampingFor(zeta: number, k: number): number {
  return 2 * zeta * Math.sqrt(k);
}

function toTransition(s: SpringSource): SpringTransition {
  const px = s.kind === "px";
  return Object.freeze({
    type: "spring",
    stiffness: s.k,
    damping: dampingFor(s.zeta, s.k),
    mass: 1,
    // Finite rest thresholds so animations end and the page can idle.
    restDelta: px ? 0.5 : 0.004,
    restSpeed: px ? 2 : 0.02,
  });
}

export const settle = toTransition(SPRINGS.settle);
export const bounce = toTransition(SPRINGS.bounce);
export const heavy = toTransition(SPRINGS.heavy);
export const quick = toTransition(SPRINGS.quick);
export const spatialFast = toTransition(SPRINGS.spatialFast);
export const spatialDefault = toTransition(SPRINGS.spatialDefault);
export const spatialSlow = toTransition(SPRINGS.spatialSlow);
export const effectsFast = toTransition(SPRINGS.effectsFast);
export const effectsDefault = toTransition(SPRINGS.effectsDefault);
export const effectsSlow = toTransition(SPRINGS.effectsSlow);
export const tabSwitch = toTransition(SPRINGS.tabSwitch);
export const bounds = toTransition(SPRINGS.bounds);

/** A spring token with a delay in seconds, for staggered choreography. */
export function delayed(t: SpringTransition, delaySec: number): SpringTransition & { readonly delay: number } {
  return { ...t, delay: delaySec };
}

// Easing curves from Motion.kt, for CSS and choreographed tweens that cannot be interrupted.
export const EASE_EMPHASIZED = [0.2, 0, 0, 1] as const;
export const EASE_DECEL = [0.05, 0.7, 0.1, 1] as const;
export const EASE_ACCEL = [0.3, 0, 0.8, 0.15] as const;

export function emphasized(ms = 420) {
  return { type: "tween", duration: ms / 1000, ease: EASE_EMPHASIZED } as const;
}

export function decel(ms = 360) {
  return { type: "tween", duration: ms / 1000, ease: EASE_DECEL } as const;
}

/** Stagger delay for list entrances, capped so long lists do not crawl in (Motion.staggerDelay). */
export function staggerDelay(index: number, perItem = 45, max = 320): number {
  return Math.min(index * perItem, max);
}
