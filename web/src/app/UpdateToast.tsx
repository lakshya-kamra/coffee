/**
 * Service-worker update prompt (web-spec §17). registerType "prompt": a waiting update shows
 * "A new version is ready · Reload", held back while a brew is really being made and offered
 * once it finishes. A brew left running long past its recipe (started by mistake, walked away
 * from) no longer holds it back, or an installed app could sit on an old build for good.
 * Never auto-reloads. Registration goes through the external virtual module (CSP-safe).
 */
import { useEffect, useState } from "react";
import { useRegisterSW } from "virtual:pwa-register/react";
import { useActiveBrew, useRecipe } from "@/data/store";
import { type BrewClock, updateHoldMs } from "@/domain/core/activeBrew";
import { totalSec } from "@/domain/core/recipe";
import shell from "./shell.module.css";

/**
 * True while the prompt should wait for a brew; turns false by itself when the wait is over.
 * `now` only moves when the timer fires, which can make the wait look longer, never shorter.
 */
function useBrewHold(active: BrewClock | null, planSec: number | null): boolean {
  const [now, setNow] = useState(() => Date.now());
  const hold = updateHoldMs(active, planSec, now);
  useEffect(() => {
    if (hold <= 0) return;
    const id = setTimeout(() => setNow(Date.now()), hold + 50);
    return () => clearTimeout(id);
  }, [hold]);
  return hold > 0;
}

export function UpdateToast() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisterError(error: unknown) {
      console.warn("Service worker registration failed", error);
    },
  });
  // Mounted only once an update waits, so the hold's clock starts fresh at that moment.
  if (!needRefresh) return null;
  return <UpdatePrompt onReload={() => void updateServiceWorker(true)} onLater={() => setNeedRefresh(false)} />;
}

function UpdatePrompt({ onReload, onLater }: { onReload: () => void; onLater: () => void }) {
  const active = useActiveBrew();
  const recipe = useRecipe(active?.recipeId ?? "");
  const brewing = useBrewHold(active, recipe ? totalSec(recipe) : null);
  if (brewing) return null;
  return (
    <div className={shell.toast} role="status">
      <div className={shell.toastBody}>
        <span className={`t-body-m ${shell.toastText}`}>A new version is ready</span>
        <button type="button" className={`${shell.toastAction} t-label-l`} onClick={onReload}>
          Reload
        </button>
        <button type="button" className={`${shell.toastAction} t-label-l`} onClick={onLater}>
          Later
        </button>
      </div>
    </div>
  );
}

/** "Coffee was updated in another tab · Reload" (db versionchange, web-spec §6.8 step 6). */
export function VersionChangeNotice() {
  return (
    <div className={shell.toast} role="status">
      <div className={shell.toastBody}>
        <span className={`t-body-m ${shell.toastText}`}>Coffee was updated in another tab</span>
        <button type="button" className={`${shell.toastAction} t-label-l`} onClick={() => location.reload()}>
          Reload
        </button>
      </div>
    </div>
  );
}
