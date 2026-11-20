/**
 * Gear (web-spec §11, A§7.4–7.8, v4 §3.10): Beans / Grinders / Settings under one pinned set of
 * real tabs (role="tab" + aria-controls). The sub-tab is part of the URL (/gear, /gear/grinders,
 * /gear/app). Below expanded: today's screen (cards, a card grid from medium, Settings as one
 * scroll with a sticky jump row) and the editors as sheets. From expanded: list-detail through
 * the shell's ListDetail; the detail pane is the editor itself (Save/Revert in its header,
 * Ctrl/Cmd+S) or the chosen Settings section. Moving away from unsaved changes asks first, and
 * drafts survive tab switches and resizes (§2.10).
 */
import { animate } from "motion";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { registerCommands } from "@/app/palette/commands";
import { ListDetail, usePaneMode } from "@/app/panes";
import { nav } from "@/app/router";
import type { GearTab } from "@/app/routes";
import { useShortcuts, type Binding } from "@/app/shortcuts";
import { showToast } from "@/app/toast";
import { prefersReducedMotion } from "@/app/transitionState";
import { catalog } from "@/catalog/catalog";
import { ConfirmSheet, DISCARD_COPY, IconWell, ScreenHeader, SegmentedControl } from "@/design/components";
import { effectsDefault, spatialDefault } from "@/design/motion";
import { repo } from "@/data/repo";
import { newBean, newGrinder, type BeanRow, type GrinderRow } from "@/data/schema";
import { useBeans, useBrews, useGrinders, useSettings } from "@/data/store";
import { beanStart, BeanEditorPane, BeanEditorSheet } from "./BeanEditor";
import { BeanCards, BeanRows, BeansEmpty, sortBeans } from "./BeansTab";
import { gearCommands, subscribeGearRequests, takeGearRequest, type GearRequest } from "./commands";
import { GrinderEditorPane, GrinderEditorSheet } from "./GrinderEditor";
import { GrinderCards, GrinderRows, GrindersEmpty, sortGrinders } from "./GrindersTab";
import { answerDiscard, Drafts, isAsking, requestSelect, type InlineSelection } from "./inlineEdit";
import { useGearActive } from "./PaneEditor";
import { SettingsJumpRow, SettingsList, SettingsScroll, SettingsSectionPane } from "./Settings";
import { selectSection, SETTINGS_SECTIONS, useSettingsSection } from "./settingsSections";
import s from "./gear.module.css";

const TABS: readonly GearTab[] = ["beans", "grinders", "app"];
const LABELS = ["Beans", "Grinders", "Settings"] as const;
const PANEL_IDS = TABS.map((t) => `gear-panel-${t}`);
/** The selection target that opens a new, unsaved row. */
const NEW = "+new";

function countLine(coffees: number, grinders: number): string {
  return `${coffees} ${coffees === 1 ? "coffee" : "coffees"} · ${grinders} ${grinders === 1 ? "grinder" : "grinders"}`;
}

function scrollerOf(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowY;
    if (o === "auto" || o === "scroll") return p;
  }
  return null;
}

type Confirm =
  | { kind: "discard"; which: "bean" | "grinder" }
  | { kind: "deleteBean"; bean: BeanRow }
  | { kind: "removeGrinder"; grinder: GrinderRow };

/** The detail pane's add-first empty state, with the pane's h1. */
function PaneEmpty({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className={s.settingsPane}>
      <header className={s.settingsPaneHead}>
        <h1 className="t-display-s">{title}</h1>
      </header>
      {children}
    </div>
  );
}

export function GearScreen({ tab }: { tab: GearTab }) {
  const paneMode = usePaneMode();
  const active = useGearActive();
  const beans = useBeans({ includeArchived: true });
  const grinders = useGrinders();
  const brews = useBrews();
  const st = useSettings();
  const section = useSettingsSection();

  // Below expanded: editors are sheets.
  const [editingBean, setEditingBean] = useState<BeanRow | null>(null);
  const [editingGrinder, setEditingGrinder] = useState<GrinderRow | null>(null);

  // Expanded+: the selection per list, the unsaved new row, drafts kept per row.
  const [beanDrafts] = useState(() => new Drafts<BeanRow>());
  const [grinderDrafts] = useState(() => new Drafts<GrinderRow>());
  const [beanSel, setBeanSel] = useState<InlineSelection>({ selected: null });
  const [grinderSel, setGrinderSel] = useState<InlineSelection>({ selected: null });
  const [newBeanRow, setNewBeanRow] = useState<BeanRow | null>(null);
  const [newGrinderRow, setNewGrinderRow] = useState<GrinderRow | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  const [visited, setVisited] = useState<ReadonlySet<GearTab>>(() => new Set([tab]));
  if (!visited.has(tab)) setVisited(new Set([...visited, tab]));

  // ---------------------------------------------------------------- what the panes show

  const sortedBeans = useMemo(() => sortBeans(beans), [beans]);
  const sortedGrinders = useMemo(() => sortGrinders(grinders), [grinders]);
  const defaultBeanUid = (beans.find((b) => b.id === st.defaultBeanId && !b.isArchived) ?? sortedBeans[0])?.uid ?? null;
  const defaultGrinderUid = sortedGrinders[0]?.uid ?? null;
  const beanExists = (uid: string | null) => uid != null && (beans.some((b) => b.uid === uid) || newBeanRow?.uid === uid);
  const grinderExists = (uid: string | null) => uid != null && (grinders.some((g) => g.uid === uid) || newGrinderRow?.uid === uid);
  // A selection that was deleted falls back to the default.
  const shownBean = beanExists(beanSel.selected) ? beanSel.selected : defaultBeanUid;
  const shownGrinder = grinderExists(grinderSel.selected) ? grinderSel.selected : defaultGrinderUid;

  // ---------------------------------------------------------------- selection with the discard question

  const freshBean = () => beanStart(newBean({ name: "" }));
  const freshGrinder = () => newGrinder({ brand: "", model: "", isDefault: grinders.length === 0 });

  const moveBean = (to: string | null) => {
    if (to === NEW) {
      const row = freshBean();
      setNewBeanRow(row);
      setBeanSel({ selected: row.uid });
      return;
    }
    if (newBeanRow && to !== newBeanRow.uid) {
      beanDrafts.drop(newBeanRow.uid);
      setNewBeanRow(null);
    }
    setBeanSel({ selected: to });
  };
  const moveGrinder = (to: string | null) => {
    if (to === NEW) {
      const row = freshGrinder();
      setNewGrinderRow(row);
      setGrinderSel({ selected: row.uid });
      return;
    }
    if (newGrinderRow && to !== newGrinderRow.uid) {
      grinderDrafts.drop(newGrinderRow.uid);
      setNewGrinderRow(null);
    }
    setGrinderSel({ selected: to });
  };

  const selectBean = (to: string | null) => {
    const next = requestSelect({ selected: shownBean }, to, beanDrafts.dirty(shownBean));
    if (isAsking(next)) {
      setBeanSel(next);
      setConfirm({ kind: "discard", which: "bean" });
    } else if (next.selected !== shownBean || to === NEW) moveBean(to);
  };
  const selectGrinder = (to: string | null) => {
    const next = requestSelect({ selected: shownGrinder }, to, grinderDrafts.dirty(shownGrinder));
    if (isAsking(next)) {
      setGrinderSel(next);
      setConfirm({ kind: "discard", which: "grinder" });
    } else if (next.selected !== shownGrinder || to === NEW) moveGrinder(to);
  };

  const answer = (which: "bean" | "grinder", discard: boolean) => {
    if (which === "bean") {
      const to = beanSel.pending;
      const next = answerDiscard(beanSel, discard);
      if (discard && to !== undefined) {
        beanDrafts.drop(shownBean);
        moveBean(to);
      } else setBeanSel(next);
    } else {
      const to = grinderSel.pending;
      const next = answerDiscard(grinderSel, discard);
      if (discard && to !== undefined) {
        grinderDrafts.drop(shownGrinder);
        moveGrinder(to);
      } else setGrinderSel(next);
    }
  };

  const addBean = () => (paneMode ? selectBean(NEW) : setEditingBean(newBean({ name: "" })));
  const addGrinder = () => (paneMode ? selectGrinder(NEW) : setEditingGrinder(freshGrinder()));
  const onAdd = () => (tab === "grinders" ? addGrinder() : addBean());

  // The palette's "Add a coffee" / "Add a grinder", now or as soon as Gear mounts.
  const addRef = useRef<(r: GearRequest) => void>(() => undefined);
  useEffect(() => {
    addRef.current = (r) => (r === "addBean" ? addBean() : addGrinder());
  });
  useEffect(() => {
    const check = () => {
      const r = takeGearRequest();
      if (r) addRef.current(r);
    };
    check();
    return subscribeGearRequests(check);
  }, []);
  useEffect(() => registerCommands(gearCommands), []);

  // ---------------------------------------------------------------- delete / remove (they ask)

  const askDelete = () => {
    if (!paneMode) return;
    if (tab === "beans") {
      const b = beans.find((x) => x.uid === shownBean);
      if (b) setConfirm({ kind: "deleteBean", bean: b });
    } else if (tab === "grinders") {
      const g = grinders.find((x) => x.uid === shownGrinder);
      if (g) setConfirm({ kind: "removeGrinder", grinder: g });
    }
  };

  const deleteBean = async (b: BeanRow) => {
    if (b.id == null) return;
    await repo.deleteBean(b.id);
    beanDrafts.drop(b.uid);
    if (beanSel.selected === b.uid) setBeanSel({ selected: null });
    setEditingBean(null);
    showToast({ text: "Coffee deleted" });
  };
  const removeGrinder = async (g: GrinderRow) => {
    if (g.id == null) return;
    const id = g.id;
    await repo.archiveGrinder(id);
    grinderDrafts.drop(g.uid);
    if (grinderSel.selected === g.uid) setGrinderSel({ selected: null });
    setEditingGrinder(null);
    // Archived, not deleted: old brews keep their grinder, and Undo is exact.
    showToast({ text: "Grinder removed", action: { label: "Undo", run: () => void repo.archiveGrinder(id, false) } });
  };

  // ---------------------------------------------------------------- keys (§2.7)

  const rows = tab === "beans" ? [...(newBeanRow ? [newBeanRow.uid] : []), ...sortedBeans.map((b) => b.uid)] : [...(newGrinderRow ? [newGrinderRow.uid] : []), ...sortedGrinders.map((g) => g.uid)];
  const rowsKey = rows.join(",");
  const step = useCallback(
    (by: 1 | -1) => {
      if (!paneMode) return;
      if (tab === "app") {
        const i = SETTINGS_SECTIONS.findIndex((x) => x.id === section);
        const next = SETTINGS_SECTIONS[Math.max(0, Math.min(SETTINGS_SECTIONS.length - 1, i + by))];
        if (next) selectSection(next.id);
        return;
      }
      const cur = rows.indexOf((tab === "beans" ? shownBean : shownGrinder) ?? "");
      const to = rows[Math.max(0, Math.min(rows.length - 1, cur + by))];
      if (to) (tab === "beans" ? selectBean : selectGrinder)(to);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [paneMode, tab, section, rowsKey, shownBean, shownGrinder],
  );
  const keys = useMemo<Binding[]>(() => {
    const list: Binding[] = [
      { keys: "j", label: "Next in the list", group: "Lists", run: () => step(1) },
      { keys: "k", label: "Previous in the list", group: "Lists", run: () => step(-1) },
    ];
    if (tab !== "app") {
      list.push({ keys: "n", label: tab === "grinders" ? "Add a grinder" : "Add a coffee", group: "Lists", run: () => onAdd() });
      if (paneMode) {
        list.push({ keys: "delete", label: tab === "grinders" ? "Remove the grinder" : "Delete the coffee", group: "Lists", run: () => askDelete() });
        // A Mac keyboard's Delete key sends Backspace.
        list.push({ keys: "backspace", label: "Delete", group: "Lists", run: () => askDelete(), hidden: true });
      }
    }
    return list;
    // The handlers read this render's state; rebuilt whenever what they act on changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, paneMode, step, shownBean, shownGrinder, beans, grinders, newBeanRow, newGrinderRow, beanSel, grinderSel]);
  useShortcuts("gear", keys, active);

  // ---------------------------------------------------------------- sub-tab switch motion

  const panels = useRef<Partial<Record<GearTab, HTMLDivElement | null>>>({});
  const scrolls = useRef<Partial<Record<GearTab, number>>>({});
  const shown = useRef(tab);
  // Remember where each list was scrolled, then fade the new one in (opacity + transform only).
  useLayoutEffect(() => {
    const from = shown.current;
    if (from === tab) return;
    shown.current = tab;
    const el = panels.current[tab];
    const scroller = el ? scrollerOf(el) : null;
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

  // ---------------------------------------------------------------- the list

  const active2 = beans.filter((b) => !b.isArchived).length;
  const header = (
    <ScreenHeader
      eyebrow={countLine(active2, grinders.length)}
      title="Gear"
      trailing={tab === "app" ? undefined : <IconWell icon="Add" label={tab === "grinders" ? "Add grinder" : "Add coffee"} keys="n" onClick={onAdd} />}
      status={
        <div className={s.tabs}>
          <SegmentedControl options={LABELS} selectedIndex={TABS.indexOf(tab)} onSelect={(i) => nav.switchTab("gear", TABS[i])} label="Gear sections" panelIds={PANEL_IDS} />
          {tab === "app" && !paneMode && <SettingsJumpRow />}
        </div>
      }
    />
  );

  const panelBody = (t: GearTab): ReactNode => {
    if (t === "beans") {
      if (!paneMode) return <BeanCards beans={beans} brews={brews} onEdit={setEditingBean} onAdd={addBean} />;
      return beans.length === 0 && !newBeanRow ? null : <BeanRows beans={beans} selectedUid={shownBean} onSelect={selectBean} newRow={newBeanRow} />;
    }
    if (t === "grinders") {
      if (!paneMode) return <GrinderCards grinders={grinders} brews={brews} notices={st.pendingNotices} onEdit={setEditingGrinder} onAdd={addGrinder} />;
      return grinders.length === 0 && !newGrinderRow ? null : (
        <GrinderRows grinders={grinders} notices={st.pendingNotices} selectedUid={shownGrinder} onSelect={selectGrinder} newRow={newGrinderRow} />
      );
    }
    return paneMode ? <SettingsList onSelect={(id) => selectSection(id)} /> : <SettingsScroll />;
  };

  const list = (
    <div className={paneMode ? s.listColumn : `column ${s.screen}`}>
      {header}
      {TABS.map((t, i) => (
        <div
          key={t}
          id={PANEL_IDS[i]}
          ref={(el) => {
            panels.current[t] = el;
          }}
          className={s.pane}
          hidden={t !== tab}
          role="tabpanel"
          aria-label={LABELS[i]}
        >
          {visited.has(t) ? panelBody(t) : null}
        </div>
      ))}
    </div>
  );

  // ---------------------------------------------------------------- the detail pane (expanded+)

  let detail: ReactNode = null;
  let detailKey: string = tab;
  if (paneMode) {
    if (tab === "beans") {
      const saved = beans.find((b) => b.uid === shownBean) ?? null;
      const template = saved ?? (newBeanRow?.uid === shownBean ? newBeanRow : null);
      detailKey = `bean:${shownBean ?? "none"}`;
      detail =
        template == null ? (
          <PaneEmpty title="Beans">
            <BeansEmpty onAdd={addBean} />
          </PaneEmpty>
        ) : (
          <BeanEditorPane
            saved={saved}
            template={template}
            draft={beanDrafts.get(template.uid)}
            brews={brews}
            onDraft={(b) => beanDrafts.put(template.uid, b, template)}
            onSaved={(b) => {
              beanDrafts.drop(b.uid);
              if (newBeanRow?.uid === b.uid) setNewBeanRow(null);
              setBeanSel({ selected: b.uid });
            }}
            onDelete={(b) => setConfirm({ kind: "deleteBean", bean: b })}
          />
        );
    } else if (tab === "grinders") {
      const saved = grinders.find((g) => g.uid === shownGrinder) ?? null;
      const template = saved ?? (newGrinderRow?.uid === shownGrinder ? newGrinderRow : null);
      detailKey = `grinder:${shownGrinder ?? "none"}`;
      detail =
        template == null ? (
          <PaneEmpty title="Grinders">
            <GrindersEmpty onAdd={addGrinder} />
          </PaneEmpty>
        ) : (
          <GrinderEditorPane
            saved={saved}
            template={template}
            draft={grinderDrafts.get(template.uid)}
            onDraft={(g) => grinderDrafts.put(template.uid, g, template)}
            onSaved={(g) => {
              grinderDrafts.drop(g.uid);
              if (newGrinderRow?.uid === g.uid) setNewGrinderRow(null);
              setGrinderSel({ selected: g.uid });
            }}
            onRemove={(g) => setConfirm({ kind: "removeGrinder", grinder: g })}
          />
        );
    } else {
      detailKey = `app:${section}`;
      detail = <SettingsSectionPane id={section} />;
    }
  }

  return (
    <>
      <ListDetail
        tab="gear"
        listLabel="Gear"
        list={list}
        defaultDetail={null}
        detail={paneMode ? detail : undefined}
        detailKey={detailKey}
        detailLabel={tab === "app" ? "Settings section" : tab === "grinders" ? "Grinder" : "Coffee"}
      />

      {/* Below expanded (and when a resize shrinks the window mid-edit) the editors are sheets. */}
      {editingBean && (
        <BeanEditorSheet key={editingBean.uid} initial={editingBean} onClose={() => setEditingBean(null)} onDelete={(b) => setConfirm({ kind: "deleteBean", bean: b })} />
      )}
      {editingGrinder && (
        <GrinderEditorSheet key={editingGrinder.uid} initial={editingGrinder} onClose={() => setEditingGrinder(null)} onRemove={(g) => setConfirm({ kind: "removeGrinder", grinder: g })} />
      )}

      {confirm?.kind === "discard" && (
        <ConfirmSheet
          title={DISCARD_COPY.title}
          body={confirm.which === "bean" ? DISCARD_COPY.body : "You've changed this grinder and haven't saved it."}
          cancelText={DISCARD_COPY.keep}
          confirmText={DISCARD_COPY.discard}
          danger
          safe="cancel"
          onConfirm={() => answer(confirm.which, true)}
          onCancel={() => answer(confirm.which, false)}
          onDismiss={() => answer(confirm.which, false)}
          onClose={() => setConfirm(null)}
        />
      )}
      {confirm?.kind === "deleteBean" && (
        <ConfirmSheet
          title="Delete this coffee?"
          body="Its brews stay in the log. Photos of the bag are deleted too."
          confirmText="Delete"
          danger
          safe="cancel"
          onConfirm={() => void deleteBean(confirm.bean)}
          onClose={() => setConfirm(null)}
        />
      )}
      {confirm?.kind === "removeGrinder" && (
        <ConfirmSheet
          title="Remove this grinder?"
          body="It leaves the list and brew setup. Its brews stay in the log."
          confirmText="Remove"
          danger
          safe="cancel"
          onConfirm={() => void removeGrinder(confirm.grinder)}
          onClose={() => setConfirm(null)}
        />
      )}
    </>
  );
}

/** Chunk + data warm-up, called on pointerdown of anything that navigates here. */
export function prefetch(): Promise<unknown> {
  return catalog.load(["grinders", "beans", "origins", "roasters", "water", "brewers"]);
}

// Pure helpers other features reuse (onboarding saves a grinder the same way the editor fills one).
export { fieldsFromCatalog, findExistingGrinder, isTimed, scaleSummary } from "./mapping";
export { BeanEditorSheet } from "./BeanEditor";
export { GrinderEditorSheet } from "./GrinderEditor";
export { gearCommands } from "./commands";
export { selectSection } from "./settingsSections";
