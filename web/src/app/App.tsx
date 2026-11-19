/**
 * The shell: keep-alive tabs, the bottom bar, the detail layer and the update prompt, inside
 * LazyMotion (domMax, strict: only `m.*` components). No LayoutGroup: nothing uses Motion's
 * layout projection (the recipe header's flight is app/sharedFlight.ts).
 */
import { LazyMotion, MotionConfig } from "motion/react";
import { useEffect, useSyncExternalStore } from "react";
import { useSettings } from "@/data/store";
import { BottomBar } from "./BottomBar";
import { DetailHost } from "./DetailHost";
import { nav, router } from "./router";
import { TABS } from "./routes";
import { TabsHost } from "./TabsHost";
import { rememberPointer } from "./transitionState";
import { UpdateToast, VersionChangeNotice } from "./UpdateToast";
import { versionChanged } from "./boot";

// domMax is about a quarter of Motion. It downloads next to the entry chunk (started here, at
// module load, while boot() opens the database) and `m.*` components pick it up when it lands;
// until then they render their final styles, so first paint never waits for it.
const motionFeatures = import("./motionFeatures").then((mod) => mod.default);
const loadMotionFeatures = () => motionFeatures;

function isTyping(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  if (!el) return false;
  return el.isContentEditable || el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT";
}

/** 1–5 switch tabs, Escape goes back (dialogs handle their own Escape). */
function useShellKeys() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target)) return;
      if (document.querySelector("dialog[open]")) return;
      if (e.key >= "1" && e.key <= "5" && router.get().depth === 0) {
        const tab = TABS[Number(e.key) - 1];
        if (tab) nav.switchTab(tab);
      } else if (e.key === "Escape" && router.get().depth > 0) {
        nav.back();
      }
    };
    const onPointer = (e: PointerEvent) => rememberPointer(e.clientX, e.clientY);
    window.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPointer, { capture: true, passive: true });
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPointer, { capture: true });
    };
  }, []);
}

export function App() {
  const motionPref = useSettings().motion;
  const dbReplaced = useSyncExternalStore(versionChanged.subscribe, versionChanged.get, versionChanged.get);
  useShellKeys();
  return (
    <LazyMotion features={loadMotionFeatures} strict>
      <MotionConfig reducedMotion={motionPref === "reduced" ? "always" : "user"}>
        <TabsHost />
        <BottomBar />
        <DetailHost />
        <UpdateToast />
        {dbReplaced && <VersionChangeNotice />}
      </MotionConfig>
    </LazyMotion>
  );
}
