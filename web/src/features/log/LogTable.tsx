/**
 * The extra-large Log table (v4 §3.7): Date, Recipe, Coffee, Grinder + setting, Dose, Ratio, Time
 * vs plan, Score, Faults. Headers sort (aria-sort); rows are one roving tab stop, arrows move and
 * select into the 480 detail pane after a short rest, Enter or a click selects at once.
 */
import { useMemo, useState, type KeyboardEvent } from "react";
import type { BrewRow } from "@/data/schema";
import { beanShortName, useRovingList } from "@/design/components";
import { grams, oneDp, screenTime } from "@/domain/core/format";
import { cupLabel, scoreStatus, signedClock, statusColorVar } from "@/domain/core/status";
import { judgeTime, type TimeTarget } from "@/domain/core/shotTarget";
import { nextSort, sortTable, TABLE_COLUMNS, type SortDir, type TableKey } from "./logState";
import l from "./log.module.css";


function grindSetting(b: BrewRow): string {
  return b.grindClicks != null ? `${b.grindClicks} clicks` : b.grindValue != null ? String(Math.round(b.grindValue * 100) / 100) : b.grindSetting.trim() || "—";
}

export function LogTable({
  rows,
  selectedUid,
  planOf,
  onSelect,
  onKeyRow,
  onMenu,
}: {
  rows: readonly BrewRow[];
  selectedUid: string | null;
  planOf: (b: BrewRow) => TimeTarget | null;
  onSelect: (b: BrewRow) => void;
  onKeyRow: (b: BrewRow, e: KeyboardEvent<HTMLElement>, roam: (e: KeyboardEvent<HTMLElement>) => void) => void;
  onMenu: (b: BrewRow, el?: HTMLElement, at?: { x: number; y: number }) => void;
}) {
  const [sort, setSort] = useState<{ key: TableKey; dir: SortDir }>({ key: "date", dir: "desc" });
  const sorted = useMemo(() => sortTable(rows, sort.key, sort.dir, planOf), [rows, sort, planOf]);
  const selectedIndex = selectedUid != null ? sorted.findIndex((b) => b.uid === selectedUid) : -1;
  const roving = useRovingList({
    count: sorted.length,
    selectedIndex,
    onOpen: (i) => sorted[i] && onSelect(sorted[i]),
    onSettle: (i) => sorted[i] && onSelect(sorted[i]),
  });
  const fmt = useMemo(() => new Intl.DateTimeFormat(undefined, { weekday: "short", day: "numeric", month: "short" }), []);
  return (
    <div className={l.tableScroll}>
      <table className={l.table} aria-label="Brews" aria-rowcount={sorted.length + 1}>
        {/* Fixed widths: words take what is left and end in "…" (their full text is in the detail beside). */}
        <colgroup>
          <col style={{ width: 104 }} />
          <col style={{ width: "20%" }} />
          <col style={{ width: "15%" }} />
          <col style={{ width: "16%" }} />
          <col style={{ width: 64 }} />
          <col style={{ width: 68 }} />
          <col style={{ width: 96 }} />
          <col style={{ width: 120 }} />
          <col />
        </colgroup>
        <thead>
          <tr>
            {TABLE_COLUMNS.map((c) => {
              const on = sort.key === c.key;
              return (
                <th key={c.key} scope="col" aria-sort={on ? (sort.dir === "asc" ? "ascending" : "descending") : "none"} data-num={c.numeric || undefined}>
                  <button type="button" className={`micro ${l.sortBtn}`} onClick={() => setSort((s) => nextSort(s, c.key))}>
                    {c.label.toUpperCase()}
                    <span className={l.sortMark} aria-hidden="true" data-on={on || undefined}>
                      {on && sort.dir === "asc" ? "▲" : "▼"}
                    </span>
                  </button>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sorted.map((b, i) => {
            // A shot against its band, everything else against the recipe's time (A4).
            const judged = judgeTime(b.totalTimeSec, planOf(b));
            const rated = b.overall > 0;
            const props = roving.itemProps(i);
            return (
              <tr
                key={b.uid}
                ref={props.ref}
                tabIndex={props.tabIndex}
                onFocus={props.onFocus}
                data-roving-index={i}
                className={l.tr}
                aria-current={i === selectedIndex || undefined}
                aria-rowindex={i + 2}
                onClick={() => onSelect(b)}
                onKeyDown={(e) => onKeyRow(b, e, props.onKeyDown)}
                onContextMenu={(e) => {
                  if (e.shiftKey) return;
                  e.preventDefault();
                  onMenu(b, undefined, { x: e.clientX, y: e.clientY });
                }}
              >
                <td>
                  <span className={l.cellOne}>{fmt.format(new Date(b.startedAt))}</span>
                  <span className={`t-body-s ink-low ${l.cellOne}`}>{screenTime(b.startedAt)}</span>
                </td>
                <td>
                  <span className={`t-title-s ${l.cellOne}`}>{b.recipeName.trim() || "Brew"}</span>
                  {b.recipeAuthor.trim() && <span className={`t-body-s ink-low ${l.cellOne}`}>{b.recipeAuthor}</span>}
                </td>
                <td>
                  <span className={l.cellOne}>{beanShortName(b.beanLabel) || "—"}</span>
                  {b.beanLabel.includes(" — ") && <span className={`t-body-s ink-low ${l.cellOne}`}>{b.beanLabel.slice(0, b.beanLabel.indexOf(" — "))}</span>}
                </td>
                <td>
                  <span className={l.cellOne}>{grindSetting(b)}</span>
                  <span className={`t-body-s ink-low ${l.cellOne}`}>{b.grinderLabel.trim() || "—"}</span>
                </td>
                <td data-num="">{b.doseG > 0 ? `${grams(b.doseG)} g` : "—"}</td>
                <td data-num="">{b.doseG > 0 ? `1:${oneDp(b.waterG / b.doseG)}` : "—"}</td>
                <td data-num="">
                  {judged == null ? (
                    "—"
                  ) : (
                    <span style={{ color: statusColorVar(judged.status) }}>{judged.status === "good" ? judged.hit : signedClock(judged.delta)}</span>
                  )}
                </td>
                <td data-num="">
                  <span className={l.tableScore} style={{ color: rated ? statusColorVar(scoreStatus(b.overall)) : "var(--ink-low)" }}>
                    {rated ? b.overall : "—"}
                  </span>{" "}
                  <span className="micro" style={{ color: rated ? statusColorVar(scoreStatus(b.overall)) : "var(--ink-low)" }}>
                    {cupLabel(b.overall)}
                  </span>
                </td>
                <td style={{ color: b.faults.length ? "var(--fault)" : undefined }}>
                  <span className={l.cellOne}>{b.faults.join(", ") || "—"}</span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
