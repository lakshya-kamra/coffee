/**
 * Shared-element keys (web-spec §6.5), the same strings as Android's SharedKeys. Keep-alive
 * means the Home carousel, the Home next-brew pass and the Recipes list are all mounted at once,
 * so the prefix is required, exactly as on Android.
 */
import type { SharedFrom } from "./routes";

/**
 * Recipe card → detail header flight (app/sharedFlight.ts). If the flight mis-measures on any
 * target browser, set this to false: details then use the container-transform fallback
 * (scale from the tapped card's centre + fade). Reduced motion forces the fallback's fade.
 */
export const SHARED_ELEMENT = true;

export const SharedKeys = {
  recipeHeader: (prefix: SharedFrom, id: string): string => `${prefix}/recipe-header/${id}`,
} as const;
