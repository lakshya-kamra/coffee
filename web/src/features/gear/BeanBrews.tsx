/**
 * "Brewed with this coffee" under the bean editor (v4 §3.10): how many brews, the average score,
 * the grind of the best one, the last five (each opens in Log) and the photos of the newest brew
 * that has any. Pure numbers come from `beanBrewStats`, tested on its own.
 */
import { useLiveQuery } from "dexie-react-hooks";
import { useMemo } from "react";
import { openInTab } from "@/app/panes";
import { DataGrid, EyebrowLabel, Icon, type Cell } from "@/design/components";
import { db } from "@/data/db";
import type { BrewRow } from "@/data/schema";
import { oneDp, screenDayLabel } from "@/domain/core/format";
import { cupLabel } from "@/domain/core/status";
import { PhotoStrip } from "@/features/photos";
import s from "./gear.module.css";

export interface BeanBrewStats {
  readonly count: number;
  readonly avg: number | null;
  /** The grind of the highest-rated brew (the newest on a tie); null when none is rated. */
  readonly bestGrind: string | null;
  readonly bestScore: number | null;
  /** Newest first, at most five. */
  readonly last: readonly BrewRow[];
}

export function beanBrewStats(beanId: number, brews: readonly BrewRow[]): BeanBrewStats {
  const mine = brews.filter((b) => b.beanId === beanId).sort((a, b) => b.startedAt - a.startedAt);
  const rated = mine.filter((b) => b.overall > 0);
  const avg = rated.length ? rated.reduce((n, b) => n + b.overall, 0) / rated.length : null;
  // Sorted newest first, so the first of the top score is the newest one.
  const best = rated.reduce<BrewRow | null>((top, b) => (top == null || b.overall > top.overall ? b : top), null);
  const grind = best ? (best.grindKind === "preground" ? "As bought" : best.grindSetting.trim() || null) : null;
  return { count: mine.length, avg, bestGrind: grind, bestScore: best?.overall ?? null, last: mine.slice(0, 5) };
}

/**
 * "75 clicks (7.5 on the dial)" is too long for a third of a pane and values never wrap or clip,
 * so the reading in brackets moves to the line under it, with the score.
 */
export function bestGrindCell(grind: string | null, score: number | null): Cell {
  const m = grind ? /^(.+?)\s*\((.+)\)$/.exec(grind) : null;
  const scored = score == null ? null : `scored ${score}/10`;
  if (!m) return { label: "Best grind", value: grind, sub: scored };
  return { label: "Best grind", value: m[1]!, sub: [m[2], scored].filter(Boolean).join(" · ") };
}

export function BeanBrews({ beanId, brews }: { beanId: number; brews: readonly BrewRow[] }) {
  const st = useMemo(() => beanBrewStats(beanId, brews), [beanId, brews]);
  // The newest of this coffee's brews that has a photo (photos hang off brews, not the bag).
  const ids = useMemo(() => brews.filter((b) => b.beanId === beanId && b.id != null).map((b) => b.id!), [beanId, brews]);
  const photoBrew = useLiveQuery(
    async () => {
      if (ids.length === 0) return null;
      const rows = await db.photos.where("brewLogId").anyOf(ids).toArray();
      const withPhotos = new Set(rows.map((p) => p.brewLogId));
      return st.last.find((b) => withPhotos.has(b.id!))?.id ?? brews.filter((b) => b.id != null && withPhotos.has(b.id)).sort((a, b) => b.startedAt - a.startedAt)[0]?.id ?? null;
    },
    [ids.join(","), st.last],
    null,
  );

  const cells: Cell[] = [
    { label: "Brews", value: String(st.count) },
    { label: "Average", value: st.avg == null ? null : oneDp(st.avg), sub: st.avg == null ? "none rated" : cupLabel(Math.round(st.avg)) },
    bestGrindCell(st.bestGrind, st.bestScore),
  ];

  return (
    <section className={s.brewed} aria-labelledby={`bean-brews-${beanId}`}>
      <h2 id={`bean-brews-${beanId}`} className={`eyebrow ${s.brewedHead}`}>
        Brewed with this coffee
      </h2>
      {st.count === 0 ? (
        <p className={`t-body-m ${s.described}`}>No brews with this coffee yet. Pick it in brew setup and its brews collect here.</p>
      ) : (
        <>
          <DataGrid cells={cells} columns={3} />
          <ul className={s.brewedList} aria-label="Last brews">
            {st.last.map((b) => (
              <li key={b.uid}>
                <button type="button" className={`${s.brewedRow} interactive-row`} onClick={() => openInTab("log", `/log/${b.uid}`)}>
                  <span className={s.brewedText}>
                    <span className="t-body-l" data-title="">
                      {b.recipeName || "Brew"}
                    </span>
                    <span className={`t-body-s ${s.described}`}>
                      {[screenDayLabel(b.startedAt), b.grindKind === "preground" ? "As bought" : b.grindSetting.trim()].filter(Boolean).join(" · ")}
                    </span>
                  </span>
                  <span className={`num-inline ${s.brewedScore}`}>{b.overall > 0 ? `${b.overall}/10` : "Not rated"}</span>
                  <Icon name="ChevronLeft" size={18} className={s.chev} />
                </button>
              </li>
            ))}
          </ul>
          {photoBrew != null && (
            <div className={s.field}>
              <EyebrowLabel text="Photos from your last brew with it" />
              <PhotoStrip brewId={photoBrew} kinds={["cup"]} editable={false} />
            </div>
          )}
        </>
      )}
    </section>
  );
}
