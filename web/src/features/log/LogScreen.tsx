/**
 * Port of ui/screens/history/HistoryScreen.kt: the summary, day-grouped brews and the empty state.
 * Reads the hot store synchronously (rule 5); rows are content-visibility: auto (rule 9); over
 * 1,500 brews the list is paged by month ("Show earlier months").
 */
import { memo, startTransition, useMemo, useState } from "react";
import { prefetchers } from "@/app/registry";
import { nav } from "@/app/router";
import { pathOf } from "@/app/routes";
import shell from "@/app/shell.module.css";
import { BedShapeIcon, Dripper } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import type { BrewRow } from "@/data/schema";
import { useBrews, useRecipes } from "@/data/store";
import { EyebrowLabel, GroupRow, groupPosition, QuietButton, ScreenHeader, SoftCard, toCaps, beanShortName } from "@/design/components";
import { brewerRowTag } from "@/domain/core/brewer";
import { clipWords, clock, epochDayOf, oneDp, timeLabel } from "@/domain/core/format";
import { totalSec } from "@/domain/core/recipe";
import { scoreStatus, signedClock, statusColorVar, timeStatus } from "@/domain/core/status";
import { useAnalysedBrewIds } from "@/features/ai";
import { Glyph } from "@/features/ai/glyphs";
import l from "./log.module.css";

const DAYS = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"] as const;
const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"] as const;

/** "TODAY", "YESTERDAY", else "TUE 17 NOV". */
export function dayHeader(at: number, now: number = Date.now()): string {
  const day = epochDayOf(at);
  const today = epochDayOf(now);
  if (day === today) return "TODAY";
  if (day === today - 1) return "YESTERDAY";
  const d = new Date(at);
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}`;
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

function openBrew(uid: string) {
  nav.push(pathOf({ kind: "brewDetail", brewUid: uid }));
}

/**
 * The detail's code only. Its data comes through live queries once it is open; reading it here
 * too meant three IndexedDB reads at the start of every scroll gesture that began on a row.
 */
function warm() {
  void prefetchers.brewDetail();
}

export function LogScreen() {
  useCatalog(["brewers"]);
  const logs = useBrews();
  const recipes = useRecipes();
  const analysed = useAnalysedBrewIds();
  const [pages, setPages] = useState(1);
  const targetByRecipe = useMemo(() => new Map(recipes.map((r) => [r.id, totalSec(r)])), [recipes]);
  const summary = useMemo(() => logSummary(logs), [logs]);
  const days = useMemo(() => byDay(logs), [logs]);
  const paged = logs.length > PAGE_OVER;
  const shownDays = useMemo(() => {
    if (!paged || days.length === 0) return days;
    const firstMonth = days[0]!.month;
    return days.filter((d) => firstMonth - d.month < pages * MONTHS_PER_PAGE);
  }, [days, paged, pages]);
  const hasMore = paged && shownDays.length < days.length;

  const eyebrow = logs.length === 0 ? "NO BREWS YET" : `${logs.length} ${logs.length === 1 ? "BREW" : "BREWS"} · ${summary.weeks} ${summary.weeks === 1 ? "WEEK" : "WEEKS"}`;
  return (
    <div className={`column ${shell.screen}`}>
      <ScreenHeader eyebrow={eyebrow} title="Log" status={logs.length > 0 ? <p className="t-body-s ink-mid">{summaryText(summary)}</p> : undefined} />
      {logs.length === 0 && (
        <SoftCard>
          <div className={l.empty}>
            <span className={l.emptyArt} aria-hidden="true">
              {/* Small on purpose: a friendly mark, not a hero. */}
              <Dripper size={64} waterLevel={0.35} dripping />
            </span>
            <p className="t-body-m ink-mid">Brews you finish and rate show up here. The log is where the patterns live.</p>
          </div>
        </SoftCard>
      )}
      {shownDays.map((day, i) => (
        <section key={day.key} aria-label={dayHeader(day.at)}>
          <div className={l.dayHead} data-first={i === 0}>
            <EyebrowLabel text={dayHeader(day.at)} as="h2" />
            {/* Only a busy day is counted: a "1" on every header was noise. */}
            {day.logs.length > 1 && <span className="micro ink-low">{day.logs.length} BREWS</span>}
          </div>
          <ul className={l.group}>
            {day.logs.map((log, j) => (
              <li key={log.uid}>
                <LogEntryRow log={log} targetSec={targetByRecipe.get(log.recipeId) ?? null} position={groupPosition(j, day.logs.length)} analysed={log.id != null && analysed.has(log.id)} />
              </li>
            ))}
          </ul>
        </section>
      ))}
      {hasMore && (
        <div className={l.more}>
          <QuietButton text="Show earlier months" onClick={() => startTransition(() => setPages((p) => p + 1))} />
        </div>
      )}
    </div>
  );
}

/**
 * One brew in a day's inset-grouped card. The time delta sits in the meta line in its status
 * colour, and the bed is drawn, not typed.
 */
const LogEntryRow = memo(function LogEntryRow({ log, targetSec, position, analysed }: { log: BrewRow; targetSec: number | null; position: ReturnType<typeof groupPosition>; analysed: boolean }) {
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
  return (
    <div onPointerDown={warm}>
      <GroupRow position={position} onClick={() => openBrew(log.uid)} hairlineInset={96}>
        <div className={l.row}>
          <div className={l.rowTime}>
            <span className="num-inline">{timeLabel(log.startedAt)}</span>
            <span className={`micro ink-low ${l.rowTag}`}>{tag}</span>
          </div>
          <div className={l.rowMain}>
            <span className={`t-title-m ${l.ellipsis}`}>{log.recipeName.trim() || "Brew"}</span>
            <span className={`t-body-s ink-mid ${l.ellipsis}`}>
              {[grind, time].filter(Boolean).join(" · ")}
              {time && delta && (
                <span style={{ color: statusColorVar(delta.status), fontWeight: 500 }}> {delta.text}</span>
              )}
              {bean && `${grind || time ? " · " : ""}${bean}`}
            </span>
          </div>
          <div className={l.rowEnd}>
            <span className={l.rowScore} style={{ color: log.overall > 0 ? statusColorVar(scoreStatus(log.overall)) : "var(--ink-low)" }}>
              {log.overall > 0 ? log.overall : "—"}
            </span>
            {/* A fixed height, so a row with no marks keeps its score top-aligned like the rest. */}
            <span className={l.marks}>
              {analysed && <Glyph name="spark" size={12} />}
              {log.bedShape && <BedShapeIcon shape={log.bedShape} size={16} tint="var(--ink-low)" decorative />}
              {log.faults.length > 0 && <span className={l.faultDot} />}
            </span>
          </div>
        </div>
      </GroupRow>
    </div>
  );
});
