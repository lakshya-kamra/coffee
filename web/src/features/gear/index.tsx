/**
 * Gear (web-spec §11, A§7.4–7.8): Beans / Grinders / App under one pinned SegmentedControl.
 * The sub-tab is part of the URL (/gear, /gear/grinders, /gear/app). Panes stay mounted once
 * visited (no remount on switch, rule 6); the incoming pane fades in with a 24 px drift on the
 * compositor (opacity + one transform string, rule 2). Editors are full-height sheets.
 */
import { animate } from "motion";
import { useLayoutEffect, useRef, useState } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { nav } from "@/app/router";
import type { GearTab } from "@/app/routes";
import { catalog } from "@/catalog/catalog";
import { IconWell, ScreenHeader, SegmentedControl } from "@/design/components";
import { effectsDefault, spatialDefault } from "@/design/motion";
import { newBean, newGrinder, type BeanRow, type GrinderRow } from "@/data/schema";
import { useBeans, useBrews, useGrinders, useSettings } from "@/data/store";
import { AppTab } from "./AppTab";
import { BeanEditorSheet } from "./BeanEditorSheet";
import { BeansTab } from "./BeansTab";
import { GrinderEditorSheet } from "./GrinderEditorSheet";
import { GrindersTab } from "./GrindersTab";
import s from "./gear.module.css";

const TABS: readonly GearTab[] = ["beans", "grinders", "app"];
const LABELS = ["Beans", "Grinders", "App"] as const;

function countLine(coffees: number, grinders: number): string {
  return `${coffees} ${coffees === 1 ? "coffee" : "coffees"} · ${grinders} ${grinders === 1 ? "grinder" : "grinders"}`;
}

export function GearScreen({ tab }: { tab: GearTab }) {
  const beans = useBeans({ includeArchived: true });
  const grinders = useGrinders();
  const brews = useBrews();
  const st = useSettings();
  const [editingBean, setEditingBean] = useState<BeanRow | null>(null);
  const [editingGrinder, setEditingGrinder] = useState<GrinderRow | null>(null);
  const [visited, setVisited] = useState<ReadonlySet<GearTab>>(() => new Set([tab]));
  if (!visited.has(tab)) setVisited(new Set([...visited, tab]));

  const panes = useRef<Partial<Record<GearTab, HTMLDivElement | null>>>({});
  const scrolls = useRef<Partial<Record<GearTab, number>>>({});
  const shown = useRef(tab);

  // Sub-tab switch: remember where each pane was scrolled, then fade the new one in.
  useLayoutEffect(() => {
    const from = shown.current;
    if (from === tab) return;
    shown.current = tab;
    const el = panes.current[tab];
    const scroller = el?.closest<HTMLElement>("[data-tab]");
    if (scroller) {
      scrolls.current[from] = scroller.scrollTop;
      scroller.scrollTop = scrolls.current[tab] ?? 0;
    }
    if (!el) return;
    const dir = TABS.indexOf(tab) > TABS.indexOf(from) ? 1 : -1;
    const a = prefersReducedMotion()
      ? animate(el, { opacity: [0, 1] }, effectsDefault)
      : animate(el, { opacity: [0, 1], transform: [`translateX(${dir * 24}px)`, "translateX(0px)"] }, spatialDefault);
    return () => a.stop();
  }, [tab]);

  const active = beans.filter((b) => !b.isArchived).length;
  const onAdd = () => {
    if (tab === "beans") setEditingBean(newBean({ name: "" }));
    else if (tab === "grinders") setEditingGrinder(newGrinder({ brand: "", model: "", isDefault: grinders.length === 0 }));
  };

  return (
    <div className={`column ${s.screen}`}>
      <ScreenHeader
        eyebrow={countLine(active, grinders.length)}
        title="Gear"
        trailing={tab === "app" ? undefined : <IconWell icon="Add" label={tab === "grinders" ? "Add grinder" : "Add coffee"} onClick={onAdd} />}
        status={
          <div className={s.tabs}>
            <SegmentedControl options={LABELS} selectedIndex={TABS.indexOf(tab)} onSelect={(i) => nav.switchTab("gear", TABS[i])} label="Gear sections" />
          </div>
        }
      />

      {TABS.map((t) =>
        visited.has(t) ? (
          <div
            key={t}
            ref={(el) => {
              panes.current[t] = el;
            }}
            className={s.pane}
            hidden={t !== tab}
            role="tabpanel"
            aria-label={LABELS[TABS.indexOf(t)]}
          >
            {t === "beans" && <BeansTab beans={beans} brews={brews} onEdit={setEditingBean} />}
            {t === "grinders" && <GrindersTab grinders={grinders} brews={brews} notices={st.pendingNotices} onEdit={setEditingGrinder} />}
            {t === "app" && <AppTab />}
          </div>
        ) : null,
      )}

      {editingBean && <BeanEditorSheet key={editingBean.uid} initial={editingBean} onClose={() => setEditingBean(null)} />}
      {editingGrinder && <GrinderEditorSheet key={editingGrinder.uid} initial={editingGrinder} onClose={() => setEditingGrinder(null)} />}
    </div>
  );
}

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return catalog.load(["grinders", "beans", "origins", "roasters", "water", "brewers"]);
}

// Pure helpers other features reuse (onboarding saves a grinder the same way the editor fills one).
export { fieldsFromCatalog, findExistingGrinder, scaleSummary } from "./mapping";
export { BeanEditorSheet } from "./BeanEditorSheet";
export { GrinderEditorSheet } from "./GrinderEditorSheet";
