/**
 * Port of CoffeeNav.kt BottomBar (web-spec §6.7): floating, five fixed-width items, one neutral
 * indicator pill (never crema) sliding with transform + spatialDefault. Colours change by CSS
 * transition (paint only); the selected icon scales to 1.06 with `bounce` (a deliberate bit of
 * life on a tiny element). Real links with aria-current, so it is navigation for everyone.
 */
import { animate } from "motion";
import { memo, useLayoutEffect, useRef, type MouseEvent } from "react";
// Not the components barrel: the shell needs only the icon, and the barrel would pull every
// primitive (sheets, fields, rows) into the entry chunk instead of the screens' shared one.
import { Icon } from "@/design/components/Icon";
import type { IconName } from "@/design/icons";
import { bounce } from "@/design/motion";
import { useSlidingIndicator } from "@/design/useSlidingIndicator";
import { prefetchers } from "./registry";
import { nav, useRouter } from "./router";
import { pathOf, TAB_LABELS, TABS, tabRoute, type TabId } from "./routes";
import { scrollTabToTop } from "./TabsHost";
import { prefersReducedMotion } from "./transitionState";
import shell from "./shell.module.css";

const ICON: Record<TabId, IconName> = { today: "Coffee", recipes: "MenuBook", log: "History", insights: "Insights", gear: "Tune" };

const BarItem = memo(function BarItem({ id, selected }: { id: TabId; selected: boolean }) {
  const icon = useRef<HTMLSpanElement>(null);
  const first = useRef(true);
  useLayoutEffect(() => {
    const el = icon.current;
    if (!el) return;
    const target = `scale(${selected ? 1.06 : 1})`;
    if (first.current || prefersReducedMotion()) {
      first.current = false;
      el.style.transform = target;
      return;
    }
    // Explicit from-keyframe of the same shape (scale → scale), so WAAPI never interpolates a matrix.
    const a = animate(el, { transform: [`scale(${selected ? 1 : 1.06})`, target] }, bounce);
    return () => a.stop();
  }, [selected]);

  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
    e.preventDefault();
    if (selected) scrollTabToTop(id, !prefersReducedMotion());
    else nav.switchTab(id);
  };

  return (
    <a
      href={pathOf(tabRoute(id))}
      className={shell.barItem}
      aria-current={selected ? "page" : undefined}
      onClick={onClick}
      onPointerDown={() => void prefetchers[id]().catch(() => undefined)}
    >
      <span ref={icon} className={shell.barIcon}>
        <Icon name={ICON[id]} size={22} />
      </span>
      <span className={`t-label-s ${shell.barLabel}`}>{TAB_LABELS[id]}</span>
    </a>
  );
});

export function BottomBar() {
  const tab = useRouter((s) => s.tab);
  const track = useRef<HTMLDivElement>(null);
  const pill = useRef<HTMLDivElement>(null);
  useSlidingIndicator(track, pill, TABS.indexOf(tab), TABS.length);
  return (
    <>
      <div className={shell.barScrim} aria-hidden="true" />
      <div className={shell.barWrap}>
        <div className="column">
          <nav className={shell.bar} aria-label="Sections">
            <div className={shell.barItems}>
              <div ref={track} className={shell.barTrack} aria-hidden="true">
                <div ref={pill} className={shell.barPill} />
              </div>
              {TABS.map((id) => (
                <BarItem key={id} id={id} selected={id === tab} />
              ))}
            </div>
          </nav>
        </div>
      </div>
    </>
  );
}
