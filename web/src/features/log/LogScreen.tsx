/**
 * The Log tab (v4 §3.7): search and filter chips, day-grouped rows, and from expanded up a
 * list-detail with the newest brew as the default detail. Medium widens the rows; extra-large adds
 * a List / Table toggle. Rows are a roving list (J/K, Enter, B brew again, Delete) with a context
 * menu. Reads the hot store synchronously (rule 5); rows are content-visibility: auto (rule 9);
 * over 1,500 brews the list is paged by month ("Show earlier months").
 */
import { useLiveQuery } from "dexie-react-hooks";
import { memo, startTransition, useCallback, useDeferredValue, useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState, type KeyboardEvent } from "react";
import { ListDetail, openInTab, usePaneMode, useSelectedPath, useSelectInPane } from "@/app/panes";
import { registerCommands } from "@/app/palette/commands";
import { prefetchers } from "@/app/registry";
import { useRouter } from "@/app/router";
import { pathOf } from "@/app/routes";
import { useShortcuts, type Binding } from "@/app/shortcuts";
import { useWindowClass } from "@/app/windowClass";
import shell from "@/app/shell.module.css";
import { BedShapeIcon, Dripper } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { db } from "@/data/db";
import { settings } from "@/data/settings";
import type { BrewRow } from "@/data/schema";
import { useBrews, useRecipes, useStore } from "@/data/store";
import { EyebrowLabel, Icon, PrimaryButton, QuietButton, ScreenHeader, SegmentedControl, SelectableChip, SoftCard, toCaps, tooltipProps, useContextMenu, useRovingList, beanShortName, type MenuItem } from "@/design/components";
import { brewerRowTag } from "@/domain/core/brewer";
import { firstBrew } from "@/domain/core/firstBrew";
import { clipWords, clock, epochDayOf, oneDp, screenTime } from "@/domain/core/format";
import { filterLog, isFilterActive, EMPTY_LOG_FILTER } from "@/domain/core/logQuery";
import { totalSec } from "@/domain/core/recipe";
import { cupLabel, scoreStatus, signedClock, statusColorVar, timeStatus } from "@/domain/core/status";
import { useAnalysedBrewIds } from "@/features/ai";
import { Glyph } from "@/features/ai/glyphs";
import { BrewDetailScreen } from "./BrewDetailScreen";
import { brewAgain, brewMenuItems, brewPath, useComparePicker, useDeleteBrew } from "./logActions";
import { CLEAR_FILTERS, filterOptions, logUiReducer, MENU_CHIPS, menuChipLabel, NO_RESULTS, resultLine, SEARCH_PLACEHOLDER, selectedUidOf, SORTS, TOGGLE_CHIPS, type LogView, type MenuChip } from "./logState";
import { LogTable } from "./LogTable";
import l from "./log.module.css";

/** "TODAY", "YESTERDAY", else the locale's short weekday and date ("TUE 17 NOV", "TUE, NOV 17"). */
export function dayHeader(at: number, now: number = Date.now(), locale?: string): string {
  const day = epochDayOf(at);
  const today = epochDayOf(now);
  if (day === today) return "TODAY";
  if (day === today - 1) return "YESTERDAY";
  return new Intl.DateTimeFormat(locale, { weekday: "short", day: "numeric", month: "short" }).format(new Date(at)).toUpperCase();
}

export interface LogSummary {
  readonly count: number;
  readonly weeks: number;
  readonly recipes: number;
  readonly coffees: number;
  readonly avg: number | null;
}

/** HistoryState's header numbers. A brew with no recipe id counts by its name, so it never undercounts. */
export function logSummary(logs: readonly BrewRow[]): LogSummary {
  const days = logs.map((l) => epochDayOf(l.startedAt));
  const weeks = logs.length ? Math.max(1, Math.ceil((Math.max(...days) - Math.min(...days) + 1) / 7)) : 0;
  const rated = logs.filter((l) => l.overall > 0);
  return {
    count: logs.length,
    weeks,
    recipes: new Set(logs.map((l) => l.recipeId.trim() || l.recipeName)).size,
    coffees: new Set(logs.map((l) => (l.beanId != null ? `#${l.beanId}` : l.beanLabel.trim())).filter((k) => k !== "")).size,
    avg: rated.length ? rated.reduce((s, l) => s + l.overall, 0) / rated.length : null,
  };
}

export function summaryText(s: LogSummary): string {
  return [
    `${s.recipes} ${s.recipes === 1 ? "recipe" : "recipes"}`,
    s.coffees > 0 ? `${s.coffees} ${s.coffees === 1 ? "coffee" : "coffees"}` : null,
    s.avg != null ? `avg ${oneDp(s.avg)}` : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

interface Day {
  readonly key: number;
  readonly at: number;
  readonly logs: readonly BrewRow[];
  readonly month: number;
}

/** Logs bucketed by local day, newest first (the store is already newest first). */
export function byDay(logs: readonly BrewRow[]): Day[] {
  const out: Day[] = [];
  for (const log of logs) {
    const key = epochDayOf(log.startedAt);
    const last = out[out.length - 1];
    if (last && last.key === key) (last.logs as BrewRow[]).push(log);
    else {
      const d = new Date(log.startedAt);
      out.push({ key, at: log.startedAt, logs: [log], month: d.getFullYear() * 12 + d.getMonth() });
    }
  }
  return out;
}

/** Over this many brews the list shows a few months at a time (rule 9). */
export const PAGE_OVER = 1500;
const MONTHS_PER_PAGE = 3;

/**
 * The detail's code only. Its data comes through live queries once it is open; reading it here
 * too meant three IndexedDB reads at the start of every scroll gesture that began on a row.
 */
function warm() {
  void prefetchers.brewDetail();
}

/** Ids of brews with at least one photo, for "With photos". One index read per change. */
function usePhotoBrewIds(): ReadonlySet<number> {
  const ids = useLiveQuery(() => db.photos.orderBy("brewLogId").uniqueKeys(), []);
  const key = (ids ?? []).filter((k): k is number => typeof k === "number").join(",");
  return useMemo(() => new Set(key ? key.split(",").map(Number) : []), [key]);
}

export function LogScreen() {
  useCatalog(["brewers"]);
  const logs = useBrews();
  const wc = useWindowClass();
  const view = useStore((s) => s.settings.logView);
  const table = wc.width === "xl" && view === "table" && logs.length > 0;
  const newest = logs[0] ?? null;
  const changeView = (v: LogView) => void settings.set("logView", v);
  const list = <LogList table={table} view={view} onView={wc.width === "xl" && logs.length > 0 ? changeView : null} />;
  const detail = newest ? <BrewDetailScreen brewUid={newest.uid} /> : <LogEmptyDetail />;
  return (
    <div className={table ? l.tableMode : l.listMode}>
      <ListDetail tab="log" listLabel="Log" list={list} defaultDetail={detail} detailLabel="Brew details" />
    </div>
  );
}

/** The detail pane with no brews: the same next step as the list's empty state. */
function LogEmptyDetail() {
  return (
    <div className={`column ${l.emptyDetail}`}>
      <Dripper size={120} waterLevel={0.35} dripping />
      <p className="t-body-m ink-mid">Your newest brew shows here, with its numbers, taste and photos.</p>
    </div>
  );
}

/** §4.12: "No brews yet" and the one next step. */
function LogEmpty() {
  const recipes = useRecipes();
  const settings = useStore((s) => s.settings);
  const grinders = useStore((s) => s.grinders);
  const pick = useMemo(() => firstBrew(recipes, settings, grinders, (id) => Brewers.byId(id)), [recipes, settings, grinders]);
  const open = () => (pick.recipe ? openInTab("recipes", pathOf({ kind: "recipe", id: pick.recipe.id })) : openInTab("recipes", "/recipes"));
  return (
    <SoftCard>
      <div className={l.empty}>
        <span className={l.emptyArt} aria-hidden="true">
          {/* Small on purpose: a friendly mark, not a hero. */}
          <Dripper size={64} waterLevel={0.35} dripping />
        </span>
        <div className={l.emptyText}>
          <h2 className="t-title-l">No brews yet</h2>
          <p className="t-body-m ink-mid">Brews you finish and rate show up here.</p>
        </div>
      </div>
      <div className={l.emptyAction}>
        <PrimaryButton text="Brew your first cup" icon="Play" onClick={open} height={48} />
      </div>
    </SoftCard>
  );
}

function LogList({ table, view, onView }: { table: boolean; view: LogView; onView: ((v: LogView) => void) | null }) {
  const logs = useBrews();
  const recipes = useRecipes();
  const analysed = useAnalysedBrewIds();
  const photoIds = usePhotoBrewIds();
  const paneMode = usePaneMode();
  const select = useSelectInPane("log");
  const selectedPath = useSelectedPath("log");
  const [filter, dispatch] = useReducer(logUiReducer, EMPTY_LOG_FILTER);
  // Typing stays instant; the list catches up a frame later on a big log.
  const query = useDeferredValue(filter.query);
  const [pages, setPages] = useState(1);
  const del = useDeleteBrew();
  const picker = useComparePicker();

  const targetByRecipe = useMemo(() => new Map(recipes.map((r) => [r.id, totalSec(r)])), [recipes]);
  const summary = useMemo(() => logSummary(logs), [logs]);
  const active = isFilterActive(filter);
  const shown = useMemo(() => filterLog(logs, { ...filter, query }, photoIds), [logs, filter, query, photoIds]);
  const best = filter.sort === "best";
  const days = useMemo(() => (best ? [] : byDay(shown)), [shown, best]);
  const paged = !best && shown.length > PAGE_OVER;
  const shownDays = useMemo(() => {
    if (!paged || days.length === 0) return days;
    const firstMonth = days[0]!.month;
    return days.filter((d) => firstMonth - d.month < pages * MONTHS_PER_PAGE);
  }, [days, paged, pages]);
  const hasMore = paged && shownDays.length < days.length;
  const flat = useMemo(() => (best ? shown : shownDays.flatMap((d) => d.logs)), [best, shown, shownDays]);

  // The row that the detail shows: the selection, else (beside the pane) the newest brew.
  const selectedUid = selectedUidOf(selectedPath) ?? (paneMode ? (logs[0]?.uid ?? null) : null);
  const selectedIndex = selectedUid != null ? flat.findIndex((b) => b.uid === selectedUid) : -1;
  const roving = useRovingList({
    count: flat.length,
    selectedIndex,
    onSettle: paneMode ? (i) => flat[i] && select(brewPath(flat[i].uid)) : undefined,
  });

  // One context menu for every row; the row sets its brew just before opening it.
  const menuTarget = useRef<BrewRow | null>(null);
  const menu = useContextMenu(() => (menuTarget.current ? brewMenuItems(menuTarget.current, { compare: (b) => picker.open(b, null), del: del.ask }) : []), "Brew actions");
  const openMenu = (b: BrewRow, el?: HTMLElement, at?: { x: number; y: number }) => {
    menuTarget.current = b;
    if (at) menu.openAt(at.x, at.y);
    else if (el) menu.openFrom(el);
  };

  // J/K move focus with the selection, as Recipes does, so a screen reader hears the row and
  // Enter acts on it.
  const flatRef = useRef(flat);
  const focusRef = useRef(roving.focusIndex);
  useLayoutEffect(() => {
    flatRef.current = flat;
    focusRef.current = roving.focusIndex;
  });
  const focusRow = useCallback((uid: string) => {
    const i = flatRef.current.findIndex((b) => b.uid === uid);
    if (i >= 0) focusRef.current(i);
  }, []);
  useLogShortcuts(logs, paneMode, select, del.ask, focusRow);
  useEffect(
    () =>
      registerCommands(() =>
        logs[0]
          ? [{ id: "log.brewAgain", title: `Brew again: ${logs[0].recipeName.trim() || "last brew"}`, subtitle: "Same coffee, grind and dose", group: "Actions", icon: "Play", run: () => brewAgain(logs[0]!) }]
          : [],
      ),
    [logs],
  );

  const onRowKey = (b: BrewRow, e: KeyboardEvent<HTMLElement>, roam: (e: KeyboardEvent<HTMLElement>) => void) => {
    if (!e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
      if (e.key === "b") {
        e.preventDefault();
        brewAgain(b);
        return;
      }
      if (e.key === "Delete") {
        e.preventDefault();
        del.ask(b);
        return;
      }
    }
    if (e.key === "ContextMenu" || (e.shiftKey && e.key === "F10")) {
      e.preventDefault();
      openMenu(b, e.currentTarget);
      return;
    }
    roam(e);
  };

  const rowFor = (log: BrewRow, i: number, withDate: boolean) => {
    const props = roving.itemProps(i);
    return (
      <li key={log.uid}>
        <LogEntryRow
          log={log}
          withDate={withDate}
          rovingProps={props}
          current={i === selectedIndex}
          targetSec={targetByRecipe.get(log.recipeId) ?? null}
          analysed={log.id != null && analysed.has(log.id)}
          onOpen={() => select(brewPath(log.uid))}
          onKeyDown={(e) => onRowKey(log, e, props.onKeyDown)}
          onMenu={(el, at) => openMenu(log, el, at)}
        />
      </li>
    );
  };

  const eyebrow = logs.length === 0 ? "NO BREWS YET" : `${logs.length} ${logs.length === 1 ? "BREW" : "BREWS"} · ${summary.weeks} ${summary.weeks === 1 ? "WEEK" : "WEEKS"}`;
  const toggle = onView ? (
    <div className={l.viewToggle}>
      <SegmentedControl options={["List", "Table"]} selectedIndex={view === "table" ? 1 : 0} onSelect={(i) => onView(i === 1 ? "table" : "list")} label="Log view" />
    </div>
  ) : undefined;

  let index = 0;
  return (
    <div className={`${table ? l.tableColumn : "column"} ${shell.screen}`}>
      <ScreenHeader eyebrow={eyebrow} title="Log" trailing={toggle} />
      {logs.length === 0 ? (
        <LogEmpty />
      ) : (
        <>
          <Filters filter={filter} dispatch={dispatch} logs={logs} shown={shown.length} active={active} summary={summaryText(summary)} />
          {shown.length === 0 && (
            <SoftCard>
              <div className={l.noResults} role="status">
                <p className="t-body-m">{NO_RESULTS}</p>
                <QuietButton text={CLEAR_FILTERS} onClick={() => dispatch({ type: "clear" })} />
              </div>
            </SoftCard>
          )}
          {table ? (
            <LogTable rows={flat} selectedUid={selectedUid} planOf={(b) => targetByRecipe.get(b.recipeId) ?? null} onSelect={(b) => select(brewPath(b.uid))} onKeyRow={onRowKey} onMenu={openMenu} />
          ) : best ? (
            <section aria-label="Best rated">
              <div className={l.dayHead} data-first="true">
                <EyebrowLabel text="BEST RATED" as="h2" />
              </div>
              <ul className={l.group}>{flat.map((log, i) => rowFor(log, i, true))}</ul>
            </section>
          ) : (
            shownDays.map((day, d) => (
              <section key={day.key} aria-label={dayHeader(day.at)}>
                <div className={l.dayHead} data-first={d === 0}>
                  <EyebrowLabel text={dayHeader(day.at)} as="h2" />
                  {/* Only a busy day is counted: a "1" on every header was noise. */}
                  {day.logs.length > 1 && <span className="micro ink-low">{day.logs.length} BREWS</span>}
                </div>
                <ul className={l.group}>{day.logs.map((log) => rowFor(log, index++, false))}</ul>
              </section>
            ))
          )}
          {hasMore && (
            <div className={l.more}>
              <QuietButton text="Show earlier months" onClick={() => startTransition(() => setPages((p) => p + 1))} />
            </div>
          )}
        </>
      )}
      {menu.element}
      {del.element}
      {picker.element}
    </div>
  );
}

/** Log-wide keys while the Log tab is in front (§2.7): J/K walk the brews beside the pane; B and Delete act on the brew shown. */
function useLogShortcuts(logs: readonly BrewRow[], paneMode: boolean, select: (path: string) => void, askDelete: (b: BrewRow) => void, focusRow: (uid: string) => void) {
  const state = useRouter((s) => {
    if (s.tab !== "log" || s.depth > 1) return null;
    const top = s.top;
    const uid = top.kind === "brewDetail" || top.kind === "compare" ? top.brewUid : null;
    return `${s.depth}|${uid ?? ""}`;
  });
  const [depth, uid] = state ? state.split("|") : [null, null];
  const currentUid = uid || (depth === "0" && paneMode ? (logs[0]?.uid ?? null) : null);
  const bindings = useMemo<Binding[]>(() => {
    const at = currentUid ? logs.findIndex((b) => b.uid === currentUid) : -1;
    const cur = at >= 0 ? logs[at]! : null;
    const out: Binding[] = [];
    if (cur) {
      out.push({ keys: "b", label: "Brew this again", group: "Lists", run: () => brewAgain(cur) });
      out.push({ keys: "delete", label: "Delete this brew", group: "Lists", run: () => askDelete(cur) });
    }
    if (paneMode && logs.length > 0) {
      const step = (d: number) => {
        const next = logs[Math.min(logs.length - 1, Math.max(0, (at < 0 ? 0 : at) + d))];
        if (next) {
          select(brewPath(next.uid));
          focusRow(next.uid);
        }
      };
      out.push({ keys: "j", label: "Next brew", group: "Lists", run: () => step(1) });
      out.push({ keys: "k", label: "Previous brew", group: "Lists", run: () => step(-1) });
    }
    return out;
  }, [currentUid, logs, paneMode, select, askDelete, focusRow]);
  useShortcuts("log", bindings, state != null && bindings.length > 0);
}

function Filters({ filter, dispatch, logs, shown, active, summary }: { filter: ReturnType<typeof logUiReducer>; dispatch: (a: Parameters<typeof logUiReducer>[1]) => void; logs: readonly BrewRow[]; shown: number; active: boolean; summary: string }) {
  const options = useMemo(() => ({ coffee: filterOptions(logs, "coffee"), recipe: filterOptions(logs, "recipe"), brewer: filterOptions(logs, "brewer") }), [logs]);
  const which = useRef<MenuChip | "sort">("coffee");
  const menu = useContextMenu((): MenuItem[] => {
    const w = which.current;
    if (w === "sort") return SORTS.map((s) => ({ label: s.label, icon: filter.sort === s.key ? "Check" : undefined, onSelect: () => dispatch({ type: "sort", sort: s.key }) }));
    const chip = MENU_CHIPS.find((c) => c.key === w)!;
    return [
      { label: chip.any, icon: filter[w] == null ? "Check" : undefined, onSelect: () => dispatch({ type: "pick", chip: w, value: null }) },
      ...options[w].map((o) => ({ label: `${o.label} (${o.count})`, icon: filter[w] === o.key ? ("Check" as const) : undefined, onSelect: () => dispatch({ type: "pick", chip: w, value: o.key }) })),
    ];
  }, "Filter");
  const open = (w: MenuChip | "sort", el: HTMLElement) => {
    which.current = w;
    menu.openFrom(el);
  };
  const sortLabel = SORTS.find((s) => s.key === filter.sort)!.label;
  return (
    <div className={l.filters}>
      <label className={l.search}>
        <Icon name="Search" size={20} />
        <input
          type="search"
          data-list-search=""
          className={`t-body-l ${l.searchInput}`}
          placeholder={SEARCH_PLACEHOLDER}
          aria-label="Search the log"
          aria-keyshortcuts="/"
          value={filter.query}
          onChange={(e) => dispatch({ type: "query", query: e.currentTarget.value })}
          enterKeyHint="search"
          autoComplete="off"
          spellCheck={false}
        />
      </label>
      <div className={l.chips} role="group" aria-label="Filters">
        {TOGGLE_CHIPS.map((c) => (
          <SelectableChip key={c.key} text={c.label} selected={filter[c.key]} onClick={() => dispatch({ type: "toggle", chip: c.key })} />
        ))}
        {MENU_CHIPS.map((c) => (
          <button key={c.key} type="button" className={`t-label-l ${l.menuChip}`} aria-haspopup="menu" data-on={filter[c.key] != null} onClick={(e) => open(c.key, e.currentTarget)}>
            {menuChipLabel(c.key, filter, options[c.key])}
          </button>
        ))}
      </div>
      <div className={l.resultRow}>
        <p className="t-body-s ink-mid" aria-live="polite">
          {resultLine(shown, logs.length, active)}
          {/* The log's own numbers ride along until a filter makes the count the news. */}
          {!active && summary && <span className={l.summary}> · {summary}</span>}
        </p>
        {active && shown > 0 && <QuietButton text={CLEAR_FILTERS} onClick={() => dispatch({ type: "clear" })} />}
        <button type="button" className={`t-label-l ${l.sortChip}`} aria-haspopup="menu" aria-label={`Sort: ${sortLabel}`} onClick={(e) => open("sort", e.currentTarget)}>
          {sortLabel} ▾
        </button>
      </div>
      {menu.element}
    </div>
  );
}

/**
 * One brew. The time delta sits in the meta line in its status colour, and the bed is drawn, not
 * typed. From a 560 wide container the row spreads out: coffee, grind and ratio get columns and
 * the score gets its word.
 */
const LogEntryRow = memo(function LogEntryRow({
  log,
  withDate,
  rovingProps,
  current,
  targetSec,
  analysed,
  onOpen,
  onKeyDown,
  onMenu,
}: {
  log: BrewRow;
  withDate: boolean;
  rovingProps: ReturnType<ReturnType<typeof useRovingList>["itemProps"]>;
  current: boolean;
  targetSec: number | null;
  analysed: boolean;
  onOpen: () => void;
  onKeyDown: (e: KeyboardEvent<HTMLElement>) => void;
  onMenu: (el?: HTMLElement, at?: { x: number; y: number }) => void;
}) {
  // On time says so in words; only a miss shows the signed delta, because then the number is the news.
  const delta =
    targetSec != null && targetSec > 0 && log.totalTimeSec > 0
      ? (() => {
          const st = timeStatus(log.totalTimeSec, targetSec)[0];
          return { status: st, text: st === "good" ? "on time" : signedClock(log.totalTimeSec - targetSec) };
        })()
      : null;
  const grind = log.grindClicks != null ? `${log.grindClicks} clk` : log.grindValue != null ? String(Math.round(log.grindValue * 100) / 100) : log.grindSetting.trim() ? clipWords(log.grindSetting, 14) : null;
  const time = log.totalTimeSec > 0 ? clock(log.totalTimeSec) : null;
  const bean = beanShortName(log.beanLabel);
  const brewer = Brewers.byId(log.brewerId) ?? Brewers.resolveOrNull(log.brewer);
  const tag = toCaps(brewer ? brewerRowTag(brewer) : log.brewer);
  const name = log.recipeName.trim() || "Brew";
  const rated = log.overall > 0;
  const scoreInk = rated ? statusColorVar(scoreStatus(log.overall)) : "var(--ink-low)";
  const when = withDate ? new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short" }).format(new Date(log.startedAt)) : screenTime(log.startedAt);
  const { ref, tabIndex, onFocus, ...rest } = rovingProps;
  return (
    <div
      className={l.rowWrap}
      data-current={current || undefined}
      onPointerDown={warm}
      onContextMenu={(e) => {
        if (e.shiftKey) return;
        e.preventDefault();
        onMenu(undefined, { x: e.clientX, y: e.clientY });
      }}
    >
      <button
        type="button"
        ref={ref}
        tabIndex={tabIndex}
        onFocus={onFocus}
        data-roving-index={rest["data-roving-index"]}
        className={l.rowBtn}
        aria-current={current || undefined}
        aria-keyshortcuts="B Delete"
        onClick={onOpen}
        onKeyDown={onKeyDown}
      >
        <span className={l.row}>
          <span className={l.rowTime}>
            <span className="num-inline">{when}</span>
            <span className={`micro ink-low ${l.rowTag}`}>{tag}</span>
          </span>
          <span className={l.rowMain}>
            <span className={`t-title-m ${l.ellipsis}`} data-title="">
              {name}
            </span>
            {/* Numbers never end in "…" (A7): each keeps its whole self and wraps as a unit; only the coffee name gives way. */}
            <span className={`t-body-s ink-mid ${l.rowMeta}`}>
              {grind && <span className={l.nw}>{grind}</span>}
              {time && (
                <span className={l.nw}>
                  {grind ? "· " : ""}
                  {time}
                  {delta && <span style={{ color: statusColorVar(delta.status), fontWeight: 500 }}> {delta.text}</span>}
                </span>
              )}
              {bean && <span className={l.metaBean}>{grind || time ? "· " : ""}{bean}</span>}
            </span>
            <span className={`t-body-s ink-mid ${l.rowWideMeta}`}>
              {time ?? "—"}
              {time && delta && <span style={{ color: statusColorVar(delta.status), fontWeight: 500 }}> {delta.text}</span>}
            </span>
          </span>
          <span className={`t-body-m ${l.ellipsis} ${l.wideCell}`}>{bean || "—"}</span>
          <span className={`t-body-m ${l.wideCell} ${l.num}`}>{grind ?? "—"}</span>
          <span className={`t-body-m ${l.wideCell} ${l.num}`}>{log.doseG > 0 ? `1:${oneDp(log.waterG / log.doseG)}` : "—"}</span>
          <span className={l.rowEnd}>
            <span className={l.scoreLine}>
              <span className={`micro ${l.scoreWord}`} style={{ color: scoreInk }}>
                {cupLabel(log.overall)}
              </span>
              <span className={l.rowScore} style={{ color: scoreInk }}>
                {rated ? log.overall : "—"}
              </span>
            </span>
            {/* A fixed height, so a row with no marks keeps its score top-aligned like the rest. */}
            <span className={l.marks}>
              {log.isFavourite && <Icon name="Star" size={12} label="Favourite" />}
              {analysed && <Glyph name="spark" size={12} />}
              {log.bedShape && <BedShapeIcon shape={log.bedShape} size={16} tint="var(--ink-low)" decorative />}
              {/* Status colour always with a word: the first fault, the rest in the name. */}
              {log.faults.length > 0 && (
                <span className={`micro ${l.faultWord}`} title={log.faults.join(", ")}>
                  {log.faults[0]}
                  {log.faults.length > 1 && <span className="visually-hidden">, {log.faults.slice(1).join(", ")}</span>}
                </span>
              )}
            </span>
          </span>
        </span>
      </button>
      {/* Out of the Tab order: one stop per list (§2.6). Keyboard users have the menu key and Shift+F10. */}
      <button type="button" tabIndex={-1} className={l.rowMore} {...tooltipProps(`More for ${name}`, "shift+f10")} aria-label={`More for ${name}`} aria-haspopup="menu" onClick={(e) => onMenu(e.currentTarget)}>
        <Icon name="MoreHoriz" size={20} />
      </button>
    </div>
  );
});
