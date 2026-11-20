/**
 * The shell's global bindings (v4 §2.7): Ctrl/Cmd+K, `/`, 1-5, G chords, `[` and Ctrl/Cmd+\, `?`,
 * B and the Escape order (close the top popover or dialog -> clear the list search -> leave the
 * detail; dialogs and popovers handle their own Escape before it gets here).
 */
import { useMemo } from "react";
import { navCovered } from "./layerPlan";
import { startBrew } from "./navShared";
import { useDetailPlan } from "./paneHosts";
import { nav, router } from "./router";
import { TAB_LABELS, TABS, type GearTab, type TabId } from "./routes";
import { activeContainer } from "./a11y";
import { openPalette, openShortcuts } from "./shellUi";
import { isTypingTarget, useShortcuts, type Binding } from "./shortcuts";
import { toggleSidebar } from "./sidebar";
import { atLeast, useWindowClass } from "./windowClass";

/** Focuses the active list's search field (`[data-list-search]`); false when there is none. */
export function focusListSearch(): boolean {
  const c = activeContainer();
  const field = c?.querySelector<HTMLInputElement>("[data-list-pane] [data-list-search]") ?? c?.querySelector<HTMLInputElement>("[data-list-search]");
  if (!field) return false;
  field.focus();
  field.select?.();
  return true;
}

/** Empties a React-controlled input the way typing would (its onChange runs). */
export function clearInput(el: HTMLInputElement): void {
  // The prototype's setter, so React's value tracker sees a change and onChange runs.
  // eslint-disable-next-line @typescript-eslint/unbound-method -- called with .call(el)
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
  setter?.call(el, "");
  el.dispatchEvent(new Event("input", { bubbles: true }));
}

function onEscape(e: KeyboardEvent): void {
  const t = e.target as HTMLElement | null;
  if (t && isTypingTarget(t)) {
    if (t instanceof HTMLInputElement && t.matches("[data-list-search]") && t.value !== "") clearInput(t);
    else t.blur();
    return;
  }
  if (router.get().depth > 0) nav.back();
}

const CHORDS: readonly { key: string; tab: TabId; gear?: GearTab; label: string }[] = [
  { key: "t", tab: "today", label: "Go to Today" },
  { key: "r", tab: "recipes", label: "Go to Recipes" },
  { key: "l", tab: "log", label: "Go to Log" },
  { key: "i", tab: "insights", label: "Go to Insights" },
  { key: "s", tab: "gear", gear: "app", label: "Go to Settings" },
];

export function useShellShortcuts(): void {
  const wc = useWindowClass();
  const plan = useDetailPlan();
  const covered = navCovered(plan, wc.width);
  const large = atLeast(wc.width, "large");
  const bindings = useMemo<Binding[]>(() => {
    const b: Binding[] = [
      { keys: "mod+k", label: "Search or jump", group: "General", run: () => openPalette() },
      { keys: "/", label: "Search this list, or jump", group: "General", run: () => void (focusListSearch() || openPalette()) },
      { keys: "?", label: "Keyboard shortcuts", group: "General", run: openShortcuts },
      { keys: "escape", label: "Back, or close", group: "General", run: onEscape },
    ];
    if (!covered) {
      b.push({ keys: "b", label: "Start brew", group: "General", run: startBrew });
      TABS.forEach((t, i) => {
        b.push({ keys: String(i + 1), label: `Go to ${TAB_LABELS[t]}`, group: "Navigation", run: () => nav.switchTab(t), hidden: i > 0, ...(i === 0 ? { label: "Go to a section (Today … Gear)", display: "1-5" } : {}) });
      });
      for (const c of CHORDS) b.push({ keys: `g ${c.key}`, label: c.label, group: "Navigation", run: () => nav.switchTab(c.tab, c.gear) });
    }
    if (large) {
      b.push({ keys: "[", label: "Collapse or expand the sidebar", group: "Navigation", run: toggleSidebar });
      b.push({ keys: "mod+\\", label: "Collapse or expand the sidebar", group: "Navigation", run: toggleSidebar, hidden: true });
    }
    return b;
  }, [covered, large]);
  useShortcuts("shell", bindings);
}
