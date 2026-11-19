/**
 * Port of A§3.4 `DataQuality.grindLooksTyped` (Kotlin `domain/ai/DataQuality.kt`): does a
 * logged click count look like a dial reading typed into the clicks field (so it should not be
 * trusted)? TRUE means "distrust", exactly as Kotlin. Used to pick the last trusted grind and by
 * the AI data checks. It replaces 1.x's "under one turn is finer than espresso" with
 * per-method ranges (lead decision 2):
 * - GRIND_UNDER_ONE_TURN only for legacy-turn scales (cpr 1..40, no microns), exactly as 1.x;
 * - GRIND_TOO_FINE_FOR_METHOD: a filter brew finer than this grinder's espresso starting range.
 */
import { isFilterStyle, type BrewMethod } from "@/domain/core/brewer";
import { espressoFloor, moves, usesClicks, type GrindScale } from "./grindScale";

/** 1.x rule, legacy-turn scales only: a click count under one full turn. */
export function underOneLegacyTurn(clicks: number, scale: GrindScale | null | undefined, method: BrewMethod | null | undefined): boolean {
  if (!scale) return false;
  const cpr = scale.clicksPerRotation;
  if (cpr == null) return false;
  return usesClicks(scale) && moves(scale, method).basis === "legacy-turn" && clicks >= 1 && clicks < cpr;
}

/** New rule: finer than the start of this grinder's espresso range. */
export function finerThanEspresso(clicks: number, scale: GrindScale): boolean {
  const floor = espressoFloor(scale);
  return floor != null && usesClicks(scale) && clicks > 0 && clicks < floor;
}

/**
 * True when `clicks` on a filter-style brew (or an unknown brewer) is almost certainly a dial
 * reading typed the wrong way. Espresso and other non-filter methods are never flagged.
 */
export function grindLooksTyped(clicks: number, scale: GrindScale | null | undefined, method: BrewMethod | null | undefined): boolean {
  if (method != null && !isFilterStyle(method)) return false;
  return underOneLegacyTurn(clicks, scale, method) || (scale != null && finerThanEspresso(clicks, scale));
}
