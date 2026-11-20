/**
 * The shell: skip link, the navigation for the window class (bar, rail or sidebar), keep-alive
 * tabs (with list-detail panes inside them), the detail layer, toasts, tooltips, the command
 * palette and the shortcuts dialog, inside LazyMotion (domMax, strict: only `m.*` components). No
 * LayoutGroup: nothing uses Motion's layout projection (the recipe header's flight is
 * app/sharedFlight.ts). See ADAPTIVE.md for the adaptive APIs the screens use.
 */
import { LazyMotion, MotionConfig } from "motion/react";
import { lazy, Suspense, useEffect, useSyncExternalStore } from "react";
import { useSettings } from "@/data/store";
import { TooltipLayer } from "@/design/components/Tooltip";
import { SkipLink, useDocumentTitle, useRouteFocus } from "./a11y";
import { DetailHost } from "./DetailHost";
import { Navigation } from "./Nav";
import { closePalette, closeShortcuts, useShellUi } from "./shellUi";
import { useShellShortcuts } from "./shellKeys";
import { startShortcuts } from "./shortcuts";
import { TabsHost } from "./TabsHost";
import { ToastHost } from "./toast";
import { rememberPointer } from "./transitionState";
import { UpdateToast, VersionChangeNotice } from "./UpdateToast";
import { versionChanged } from "./boot";

// domMax is about a quarter of Motion. It downloads next to the entry chunk (started here, at
// module load, while boot() opens the database) and `m.*` components pick it up when it lands;
// until then they render their final styles, so first paint never waits for it.
const motionFeatures = import("./motionFeatures").then((mod) => mod.default);
const loadMotionFeatures = () => motionFeatures;

// The palette and the shortcuts dialog load the first time they open.
const Palette = lazy(() => import("./palette/Palette"));
const ShortcutsDialog = lazy(() => import("./ShortcutsDialog"));

function useShellEvents() {
  useEffect(() => {
    const stop = startShortcuts();
    const onPointer = (e: PointerEvent) => rememberPointer(e.clientX, e.clientY);
    document.addEventListener("pointerdown", onPointer, { capture: true, passive: true });
    // The shell's own palette commands (palette/builtins.ts) load with the palette itself.
    return () => {
      stop();
      document.removeEventListener("pointerdown", onPointer, { capture: true });
    };
  }, []);
}

/**
 * Route-driven shell work in its own component, so a navigation re-renders this (which renders
 * nothing) rather than App and everything under it.
 */
function ShellEffects() {
  useShellShortcuts();
  useDocumentTitle();
  useRouteFocus();
  return null;
}

function Overlays() {
  const ui = useShellUi();
  return (
    <Suspense fallback={null}>
      {ui.palette && <Palette initialQuery={ui.paletteQuery} onClose={closePalette} />}
      {ui.shortcuts && <ShortcutsDialog onClose={closeShortcuts} />}
    </Suspense>
  );
}

export function App() {
  const motionPref = useSettings().motion;
  const dbReplaced = useSyncExternalStore(versionChanged.subscribe, versionChanged.get, versionChanged.get);
  useShellEvents();
  return (
    <LazyMotion features={loadMotionFeatures} strict>
      <MotionConfig reducedMotion={motionPref === "reduced" ? "always" : "user"}>
        <SkipLink />
        <Navigation />
        <TabsHost />
        <DetailHost />
        <ToastHost />
        <UpdateToast />
        {dbReplaced && <VersionChangeNotice />}
        <Overlays />
        <TooltipLayer />
        <ShellEffects />
      </MotionConfig>
    </LazyMotion>
  );
}
