/**
 * What the bottom bar, rail and sidebar share: icons, tooltips' digits, the live brew they show
 * when the timer is not on screen (the only crema outside it, research §1.2) and Start brew.
 */
import { useEffect, useState, type MouseEvent } from "react";
import type { IconName } from "@/design/icons";
import { useActiveBrew, useRecipe, useSettings } from "@/data/store";
import { brewElapsedMs } from "@/domain/core/activeBrew";
import { clock } from "@/domain/core/format";
import { noScaleText } from "@/domain/core/noScale";
import { runCommand } from "./palette/commands";
import { prefetchers } from "./registry";
import { nav, useRouter } from "./router";
import type { TabId } from "./routes";

export const TAB_ICON: Readonly<Record<TabId, IconName>> = { today: "Coffee", recipes: "MenuBook", log: "History", insights: "Insights", gear: "Tune" };

/** Start brew everywhere (rail, sidebar, `b`): Today's next brew when registered, else Recipes. */
export function startBrew(): void {
  if (!runCommand("brew.next")) nav.switchTab("recipes");
}

/** A plain left click navigates in-app; modified clicks keep the browser's own behaviour (new tab). */
export function isPlainClick(e: MouseEvent): boolean {
  return !(e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0);
}

export function prefetchTab(id: TabId): void {
  void prefetchers[id]().catch(() => undefined);
}

export interface LiveBrew {
  readonly recipeId: string;
  readonly clock: string;
  readonly running: boolean;
  readonly stepTitle: string;
  readonly resume: () => void;
}

/**
 * The brew in progress while the timer is not on screen, ticking once a second; null otherwise.
 * The interval runs only while a running brew is shown here.
 */
export function useLiveBrew(): LiveBrew | null {
  const active = useActiveBrew();
  const recipe = useRecipe(active?.recipeId ?? "");
  const brewShown = useRouter((s) => s.stack.some((r) => r.kind === "brew"));
  const hasScale = useSettings().hasScale;
  const visible = active != null && !brewShown;
  const running = visible && active.running;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    const tick = () => setNow(Date.now());
    const first = setTimeout(tick, 0);
    const id = setInterval(tick, 1000);
    return () => {
      clearTimeout(first);
      clearInterval(id);
    };
  }, [running]);
  if (!visible) return null;
  const sec = Math.floor(brewElapsedMs(active, now) / 1000);
  const step = recipe?.steps[active.stepIndex];
  return {
    recipeId: active.recipeId,
    clock: clock(sec),
    running: active.running,
    // Without a scale the step reads in ml here too, as on the timer (A1).
    stepTitle: step ? (hasScale ? step.title : noScaleText(step.title)) : (recipe?.name ?? ""),
    resume: () => nav.push(`/brew/${encodeURIComponent(active.recipeId)}`),
  };
}
