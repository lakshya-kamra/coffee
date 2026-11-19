/**
 * The per-target state the cards draw (BrewAnalysisViewModel / TrendAnalysisViewModel): settings,
 * the latest stored row, the live run, and a local preview rebuilt whenever the data it reads
 * changes (an edit in Review refreshes the log check and staleness). Nothing here starts a request.
 */
import { useLiveQuery } from "dexie-react-hooks";
import { useEffect, useState, useSyncExternalStore } from "react";
import { catalog } from "@/catalog/catalog";
import { db } from "@/data/db";
import { useStore } from "@/data/store";
import { costMicroUsd, formatUsd, staleReason, type AiError } from "@/domain/ai";
import { useAiSettings, type AiSettings } from "./aiSettings";
import { attachesPhotos, estimatePromptTokens, preview, PROMPT_CATALOGS, useRun, type AnalysisPreview, type Target } from "./runner";
import { useLatestAnalysis, type StoredAnalysis } from "./stored";

export interface AnalysisUi {
  /** The first local read has not finished. */
  readonly loading: boolean;
  readonly settings: AiSettings;
  readonly preview: AnalysisPreview | null;
  readonly stored: StoredAnalysis | null;
  readonly staleReason: ReturnType<typeof staleReason>;
  readonly runningSince: number | null;
  readonly error: AiError | null;
  readonly estimateLabel: string | null;
  readonly blocker: AiError | null;
  /** Usable for Analyse: configured, tested where needed, nothing blocking. */
  readonly canRun: boolean;
}

/** Re-render when catalog files land (labels, scales and principles feed the prompt). */
function useCatalogVersion(): number {
  useEffect(() => {
    void catalog.load(PROMPT_CATALOGS);
  }, []);
  return useSyncExternalStore(catalog.subscribe, catalog.version, catalog.version);
}

export function useAnalysis(target: Target): AnalysisUi {
  const settings = useAiSettings();
  const stored = useLatestAnalysis(target);
  const run = useRun(target);
  const catVersion = useCatalogVersion();
  // The slices the prompt reads; any change rebuilds the preview.
  const brews = useStore((s) => s.brews);
  const beans = useStore((s) => s.beans);
  const grinders = useStore((s) => s.grinders);
  const recipes = useStore((s) => s.recipes);
  const units = useStore((s) => s.settings.tempUnit);
  const brewId = target.kind === "brew" ? target.brewId : -1;
  const photoSig = useLiveQuery(
    async () => (brewId >= 0 ? (await db.photos.where("brewLogId").equals(brewId).toArray()).map((p) => `${p.uid}:${p.kind}:${p.sendToAi}:${p.bytes}`).join("|") : ""),
    [brewId],
  );
  const attach = attachesPhotos(settings);

  const [pv, setPv] = useState<{ key: string; value: AnalysisPreview } | null>(null);
  useEffect(() => {
    let alive = true;
    const t: Target = target.kind === "brew" ? { kind: "brew", brewId } : { kind: "trend" };
    // Built off the critical path: after paint, never inside a transition frame.
    const id = setTimeout(() => {
      void preview(t, settings).then((value) => {
        if (alive) setPv({ key: String(brewId), value });
      });
    }, 0);
    return () => {
      alive = false;
      clearTimeout(id);
    };
    // Settings matter only through `attach` (photos) for the hash; the rest is read at run time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brews, beans, grinders, recipes, units, catVersion, photoSig, attach, target.kind, brewId]);

  const p = pv && pv.key === String(brewId) ? pv.value : null;
  const usable = p && !p.blocker ? p : null;
  const estimate =
    usable && settings.price
      ? "≈" + formatUsd(costMicroUsd(settings.price, estimatePromptTokens(usable.approxPromptTokens, attach ? usable.photoCount : 0), usable.expectedCompletionTokens))
      : null;
  const st = stored ?? null;
  return {
    loading: stored === undefined || p == null,
    settings,
    preview: p,
    stored: st,
    staleReason: st ? staleReason(st, usable?.inputHash ?? null) : null,
    runningSince: run.runningSince,
    error: run.error,
    estimateLabel: estimate,
    blocker: p?.blocker ?? null,
    canRun: run.runningSince == null && settings.ready && !settings.needsTest && p?.blocker == null,
  };
}
