// Public surface of the AI feature (web-spec §20.4). Other packages import only from here.
export { AiSettingsSection, AiSettingsSheet } from "./AiSettingsSection";
export { BrewAnalysisSection, useBrewVerdict, type VerdictSummary } from "./BrewAnalysisSection";
export { FirstReadSection } from "./FirstReadSection";
export { TrendAnalysisSection } from "./TrendAnalysisSection";
export { useAiSettings, type AiSettings } from "./aiSettings";
import { runner } from "./runner";
export { runner as analysisRunner, type Target as AnalysisTarget } from "./runner";
export { useAnalysedBrewIds } from "./stored";
import { keyStore } from "./keyStore";
import { registerAiCommands } from "./commands";
export { keyStore };

registerAiCommands();

/** Review's "Save & analyse": starts the brew analysis that Brew detail then shows running. */
export function startBrewAnalysis(brewId: number): boolean {
  return runner.start({ kind: "brew", brewId });
}

/** "Delete everything" (features/backup): forget every saved key, in both storages. */
export function removeAllKeys(): void {
  keyStore.removeAll();
}
