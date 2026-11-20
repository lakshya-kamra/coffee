/**
 * The navigation for each window class (v4 §2.2, D2): the floating bar on compact, an 80-wide
 * rail on medium and expanded (and on large when the sidebar is collapsed), a 248-wide labelled
 * sidebar on large and XL. Crossing a breakpoint swaps them without animation. Under a focus mode
 * (brew, review, onboarding, editor), or under any layer on compact, the nav is inert (A3).
 */
import { memo, useRef, type MouseEvent } from "react";
import { Icon } from "@/design/components/Icon";
import { Kbd } from "@/design/components/Kbd";
import { tooltipProps } from "@/design/components/Tooltip";
import { useSlidingIndicator } from "@/design/useSlidingIndicator";
import { BottomBar, useIconBounce } from "./BottomBar";
import { navCovered } from "./layerPlan";
import { isPlainClick, prefetchTab, startBrew, TAB_ICON, useLiveBrew } from "./navShared";
import { useDetailPlan } from "./paneHosts";
import { nav, router, useRouter } from "./router";
import { pathOf, TAB_LABELS, TABS, tabRoute, type GearTab, type TabId } from "./routes";
import { openPalette, openShortcuts } from "./shellUi";
import { toggleSidebar, useSidebarCollapsed } from "./sidebar";
import { scrollTabToTop } from "./TabsHost";
import { prefersReducedMotion } from "./transitionState";
import { atLeast, useWindowClass } from "./windowClass";
import shell from "./shell.module.css";

export function Navigation() {
  const wc = useWindowClass();
  const collapsed = useSidebarCollapsed();
  const plan = useDetailPlan();
  const covered = navCovered(plan, wc.width);
  if (wc.width === "compact") return <BottomBar covered={covered} />;
  if (atLeast(wc.width, "large") && !collapsed) return <Sidebar covered={covered} />;
  return <Rail covered={covered} short={wc.short} canExpand={atLeast(wc.width, "large")} />;
}

function onTabClick(e: MouseEvent<HTMLAnchorElement>, id: TabId, selected: boolean, gearTab?: GearTab) {
  if (!isPlainClick(e)) return;
  e.preventDefault();
  if (gearTab) nav.switchTab("gear", gearTab);
  // The current tab again: at its root, scroll to the top; under a detail, go back to the root.
  else if (selected && router.get().depth === 0) scrollTabToTop(id, !prefersReducedMotion());
  else nav.switchTab(id);
}

// ---------------------------------------------------------------- rail

const RailItem = memo(function RailItem({ id, selected }: { id: TabId; selected: boolean }) {
  const icon = useIconBounce(selected);
  return (
    <a
      href={pathOf(tabRoute(id))}
      className={shell.railItem}
      aria-current={selected ? "page" : undefined}
      onClick={(e) => onTabClick(e, id, selected)}
      onPointerDown={() => prefetchTab(id)}
      {...tooltipProps(TAB_LABELS[id], String(TABS.indexOf(id) + 1), "right")}
    >
      <span ref={icon} className={shell.barIcon}>
        <Icon name={TAB_ICON[id]} size={24} />
      </span>
      <span className={`t-label-s ${shell.railLabel}`}>{TAB_LABELS[id]}</span>
    </a>
  );
});

function Rail({ covered, short, canExpand }: { covered: boolean; short: boolean; canExpand: boolean }) {
  const tab = useRouter((s) => s.tab);
  const track = useRef<HTMLDivElement>(null);
  const pill = useRef<HTMLDivElement>(null);
  useSlidingIndicator(track, pill, TABS.indexOf(tab), TABS.length, { axis: "y", liquid: true });
  const live = useLiveBrew();
  return (
    <nav className={shell.rail} aria-label="Sections" data-short={short || undefined} inert={covered}>
      <div className={shell.railTop}>
        <button type="button" className={shell.railSearch} aria-label="Search or jump" onClick={() => openPalette()} {...tooltipProps("Search or jump", "mod+k", "right")}>
          <Icon name="Search" size={22} />
        </button>
        <button type="button" className={shell.railStart} aria-label="Start brew" onClick={startBrew} {...tooltipProps("Start brew", "b", "right")}>
          <Icon name="Play" size={short ? 22 : 26} />
        </button>
      </div>
      <div className={shell.railItems}>
        <div ref={track} className={shell.railTrack} aria-hidden="true">
          <div ref={pill} className={shell.railPill} />
        </div>
        {TABS.map((id) => (
          <RailItem key={id} id={id} selected={id === tab} />
        ))}
      </div>
      <div className={shell.railBottom}>
        {live && (
          // Named by its text, not aria-label: the clock is hidden from the name so it does not
          // change every second (and the visible text never contradicts the name).
          <button type="button" className={shell.livePill} onClick={live.resume} {...tooltipProps("Back to the brew", undefined, "right")}>
            <span className={shell.liveDot} data-running={live.running} aria-hidden="true" />
            <span className="visually-hidden">Brewing. Back to the brew</span>
            <span className="num-inline" aria-hidden="true">
              {live.clock}
            </span>
          </button>
        )}
        {canExpand && (
          <button type="button" className={shell.railIconBtn} aria-label="Expand sidebar" onClick={toggleSidebar} {...tooltipProps("Expand sidebar", "[", "right")}>
            <Icon name="ViewSidebar" size={22} />
          </button>
        )}
      </div>
    </nav>
  );
}

// ---------------------------------------------------------------- sidebar

const GEAR_SUBS: readonly { id: GearTab; label: string }[] = [
  { id: "beans", label: "Beans" },
  { id: "grinders", label: "Grinders" },
  { id: "app", label: "Settings" },
];

function Sidebar({ covered }: { covered: boolean }) {
  const tab = useRouter((s) => s.tab);
  const gearTab = useRouter((s) => s.gearTab);
  const live = useLiveBrew();
  return (
    <nav className={shell.sidebar} aria-label="Sections" inert={covered}>
      <div className={shell.sideHead}>
        <span className="t-title-l">Coffee</span>
        <button type="button" className={shell.railIconBtn} aria-label="Collapse sidebar" onClick={toggleSidebar} {...tooltipProps("Collapse sidebar", "[")}>
          <Icon name="ViewSidebar" size={22} />
        </button>
      </div>
      <button type="button" className={shell.sideSearch} onClick={() => openPalette()} aria-keyshortcuts="Control+K Meta+K">
        <Icon name="Search" size={20} />
        <span className={`t-body-m ${shell.sideSearchText}`}>Search or jump</span>
        <Kbd keys="mod+k" />
      </button>
      <button type="button" className={`${shell.sideStart} t-label-l`} onClick={startBrew} {...tooltipProps("Start brew", "b")}>
        <Icon name="Play" size={22} />
        Start brew
      </button>
      <ul className={shell.sideList}>
        {TABS.map((id, i) => (
          <li key={id}>
            <a
              href={pathOf(tabRoute(id))}
              className={shell.sideItem}
              aria-current={id === tab && id !== "gear" ? "page" : undefined}
              data-parent={id === "gear" && tab === "gear" ? true : undefined}
              onClick={(e) => onTabClick(e, id, id === tab)}
              onPointerDown={() => prefetchTab(id)}
              aria-keyshortcuts={String(i + 1)}
            >
              <Icon name={TAB_ICON[id]} size={20} />
              <span className={`t-title-m ${shell.sideItemLabel}`}>{TAB_LABELS[id]}</span>
              <span className={`t-label-m ${shell.sideDigit}`} aria-hidden="true">
                {i + 1}
              </span>
            </a>
            {id === "gear" && (
              <ul className={shell.sideList}>
                {GEAR_SUBS.map((g) => (
                  <li key={g.id}>
                    <a
                      href={pathOf(tabRoute("gear", g.id))}
                      className={shell.sideSub}
                      aria-current={tab === "gear" && gearTab === g.id ? "page" : undefined}
                      onClick={(e) => onTabClick(e, "gear", tab === "gear", g.id)}
                      onPointerDown={() => prefetchTab("gear")}
                    >
                      <span className={`t-body-m ${shell.sideItemLabel}`}>{g.label}</span>
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
      {live && (
        <button type="button" className={shell.liveRow} onClick={live.resume}>
          <span className={shell.liveDot} data-running={live.running} aria-hidden="true" />
          <span className="t-title-m">Brewing</span>
          {/* Out of the name: a clock in it would re-announce every second. */}
          <span className="num-inline" aria-hidden="true">
            {live.clock}
          </span>
          <span className={`t-body-s ${shell.liveRowStep}`}>{live.stepTitle}</span>
          <span className="visually-hidden">. Back to the brew</span>
        </button>
      )}
      <div className={shell.sideFoot}>
        <button type="button" className={`${shell.sideFootBtn} t-body-m`} onClick={openShortcuts} aria-keyshortcuts="?">
          Keyboard shortcuts
          <Kbd keys="?" />
        </button>
      </div>
    </nav>
  );
}
