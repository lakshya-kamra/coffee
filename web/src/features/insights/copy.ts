/** Insights copy that tests pin (v4 §4.12, §4.16). */

/** Insights need this many brews before the charts mean anything (§4.12). */
export const INSIGHTS_MIN_BREWS = 3;

/** "Insights start after 3 brews. 2 to go." */
export function insightsEmptyText(brews: number): string {
  return `Insights start after ${INSIGHTS_MIN_BREWS} brews. ${Math.max(0, INSIGHTS_MIN_BREWS - brews)} to go.`;
}

/** Plain export labels (§4.16). */
export const EXPORT_LABELS = {
  lastBrew: "Copy as text for an AI chat (this brew and the 8 before it)",
  backup: "Save a backup file",
} as const;
