/**
 * Port of CoffeeNav.kt BottomBar (web-spec §6.7), compact only (v4 §2.2): floating, five
 * fixed-width items, one neutral indicator pill (never crema). v4.1, after Forge 2.0's
 * FloatingNavBar: the pill is liquid (it stretches with its speed, useSlidingIndicator), the
 * selected icon bounces to 1.1 on a medium-bouncy spring, and a pressed item sinks to 0.9 (CSS).
 * Real links with aria-current, so it is navigation for everyone. Labels may wrap to two lines at
 * large text; the whole bar is inert while a layer covers it (A3).
 */
import { animate } from "motion";
import { memo, useLayoutEffect, useRef, type MouseEvent, type RefObject } from "react";
// Not the components barrel: the shell needs only the icon, and the barrel would pull every
// primitive (sheets, fields, rows) into the entry chunk instead of the screens' shared one.
import { Icon } from "@/design/components/Icon";
import { navBounce, NAV_ICON_SCALE } from "@/design/motion";
import { useSlidingIndicator } from "@/design/useSlidingIndicator";
import { isPlainClick, prefetchTab, TAB_ICON } from "./navShared";
import { nav, useRouter } from "./router";
import { pathOf, TAB_LABELS, TABS, tabRoute, type TabId } from "./routes";
import { scrollTabToTop } from "./TabsHost";
import { prefersReducedMotion } from "./transitionState";
import shell from "./shell.module.css";

/**
 * The selected nav icon bounces to 1.1 (bar and rail). A retap mid-bounce stops the running one
 * (Motion commits where it was) and the next starts from there, so the icon never snaps.
 */
export function useIconBounce(selected: boolean): RefObject<HTMLSpanElement | null> {
  const icon = useRef<HTMLSpanElement>(null);
  const first = useRef(true);
  const live = useRef<{ stop: () => void } | null>(null);
  useLayoutEffect(() => {
    const el = icon.current;
    if (!el) return;
    const target = `scale(${selected ? NAV_ICON_SCALE : 1})`;
    const wasRunning = live.current != null;
    live.current?.stop();
    live.current = null;
    if (first.current || prefersReducedMotion()) {
      first.current = false;
      el.style.transform = target;
      return;
    }
    // Settled: an explicit from-keyframe of the same shape (scale → scale), so WAAPI never
    // interpolates a matrix. Caught mid-bounce: from where it was committed.
    const a = animate(el, { transform: [wasRunning ? null : `scale(${selected ? 1 : NAV_ICON_SCALE})`, target] }, navBounce);
    live.current = a;
    void a.then(() => {
      if (live.current === a) live.current = null;
    });
  }, [selected]);
  useLayoutEffect(() => () => live.current?.stop(), []);
  return icon;
}

const BarItem = memo(function BarItem({ id, selected }: { id: TabId; selected: boolean }) {
  const icon = useIconBounce(selected);

  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (!isPlainClick(e)) return;
    e.preventDefault();
    if (selected) scrollTabToTop(id, !prefersReducedMotion());
    else nav.switchTab(id);
  };

  return (
    <a href={pathOf(tabRoute(id))} className={shell.barItem} aria-current={selected ? "page" : undefined} onClick={onClick} onPointerDown={() => prefetchTab(id)}>
      <span ref={icon} className={shell.barIcon}>
        <Icon name={TAB_ICON[id]} size={22} />
      </span>
      <span className={`t-label-s ${shell.barLabel}`}>{TAB_LABELS[id]}</span>
    </a>
  );
});

export function BottomBar({ covered = false }: { covered?: boolean }) {
  const tab = useRouter((s) => s.tab);
  const track = useRef<HTMLDivElement>(null);
  const pill = useRef<HTMLDivElement>(null);
  useSlidingIndicator(track, pill, TABS.indexOf(tab), TABS.length, { axis: "x", liquid: true });
  return (
    <>
      <div className={shell.barScrim} aria-hidden="true" />
      <div className={shell.barWrap} inert={covered}>
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
