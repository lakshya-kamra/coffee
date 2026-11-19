/**
 * A§8.6 stale reasons with the web wording (web-spec §14.5). Order matters: a row from the other
 * platform can never be compared by hash (prompts differ by platform), and an older prompt
 * version always differs, so those two are named before "edited".
 */
import { isCurrentHash, VERSION } from "./analysisPrompt";

export type StaleReason = "other-device" | "older-prompt" | "edited";

export const WEB_ORIGIN = "web";

export function staleReason(row: { origin: string; promptVersion: number; inputHash: string }, currentHash: string | null): StaleReason | null {
  if (row.origin !== WEB_ORIGIN) return "other-device";
  if (row.promptVersion < VERSION) return "older-prompt";
  // So does a hash an earlier build made from row ids: it differs after any restore, whether or
  // not the brew was touched.
  if (!isCurrentHash(row.inputHash)) return "older-prompt";
  // Only a real preview can say the brew moved on; a failed or blocked one says nothing.
  if (currentHash != null && row.inputHash !== currentHash) return "edited";
  return null;
}

export function staleText(reason: StaleReason, edited = "Brew edited since · re-run for a fresh read"): string {
  switch (reason) {
    case "other-device": return "Made on another device · re-run here for a fresh read";
    case "older-prompt": return "Made by an older version of the analysis · re-run to use your grinder's scale";
    case "edited": return edited;
  }
}
