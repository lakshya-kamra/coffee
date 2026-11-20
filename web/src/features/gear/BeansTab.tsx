/**
 * Gear › Beans (A§7.8, v4 §3.10): one card per bag, newest first; finished bags stay but step
 * back. Compact: one column of cards; medium: a card grid; expanded+: compact rows in the list
 * pane (name, roaster, days off roast with its word) beside the inline editor.
 */
import { memo, useMemo, useState, type ReactNode } from "react";
import { BrewerIcon, CoffeeMug } from "@/art";
import { Brewers, catalog } from "@/catalog/catalog";
import { DataGrid, EyebrowLabel, HairLine, PrimaryButton, SoftCard, StatusChip, useRovingList, type Cell } from "@/design/components";
import type { BeanRow, BrewRow } from "@/data/schema";
import { useSettings } from "@/data/store";
import { epochDayOf, oneDp } from "@/domain/core/format";
import { cupLabel, roastWord, type Status } from "@/domain/core/status";
import { restStatus } from "./mapping";
import s from "./gear.module.css";

/** Empty-state art (§4.12): the first brewer you own, else the mug. */
export function EmptyArt() {
  const st = useSettings();
  const b = st.ownedBrewerIds.map((id) => Brewers.byId(id)).find((x) => x != null);
  return b ? <BrewerIcon brewer={b} size={64} decorative /> : <CoffeeMug fill={0.6} size={72} />;
}

export function EmptyGear({ text, art, children }: { text: string; art?: ReactNode; children?: ReactNode }) {
  return (
    <SoftCard>
      <div className={s.empty}>
        <div className={s.emptyArt} aria-hidden="true">
          {art ?? <EmptyArt />}
        </div>
        <div className={s.emptyBody}>
          <p className={`t-body-m ${s.emptyText}`}>{text}</p>
          {children}
        </div>
      </div>
    </SoftCard>
  );
}

/** Beans, with an action (§4.12). */
export function BeansEmpty({ onAdd }: { onAdd: () => void }) {
  return (
    <EmptyGear text="Roast date is the one field that pays for itself.">
      <PrimaryButton text="Add the coffee you're drinking" onClick={onAdd} height={48} />
    </EmptyGear>
  );
}

interface Stats {
  brews: number;
  avg: number | null;
}

function statsByBean(brews: readonly BrewRow[]): Map<number, Stats> {
  const acc = new Map<number, { n: number; sum: number; rated: number }>();
  for (const b of brews) {
    if (b.beanId == null) continue;
    const a = acc.get(b.beanId) ?? { n: 0, sum: 0, rated: 0 };
    a.n += 1;
    if (b.overall > 0) {
      a.sum += b.overall;
      a.rated += 1;
    }
    acc.set(b.beanId, a);
  }
  return new Map([...acc].map(([id, a]) => [id, { brews: a.n, avg: a.rated ? a.sum / a.rated : null }]));
}

/** "Ethiopia, Guji · Washed · Light · Resting · ready in 3 days". */
export function beanMeta(b: BeanRow, todayDay: number): string {
  const place = [b.origin.trim(), b.region.trim()].filter(Boolean).join(", ");
  const form = b.form === "ground" ? "Ground" : null;
  return [form, place, catalog.label("process", b.processId), catalog.label("roastLevel", b.roastLevelId), restStatus(b, todayDay)].filter(Boolean).join(" · ");
}

/** "Resting", "Peak", "Fading" with the status colour that goes with each word (§3.10). */
export function roastState(b: Pick<BeanRow, "roastedOnEpochDay" | "restDays">, todayDay: number): { days: number; word: string; status: Status } | null {
  if (b.roastedOnEpochDay == null) return null;
  const days = Math.max(0, todayDay - b.roastedOnEpochDay);
  if (restStatus(b, todayDay) != null) return { days, word: "Resting", status: "neutral" };
  const w = roastWord(days);
  if (w === "resting") return { days, word: "Resting", status: "neutral" };
  if (w === "fading") return { days, word: "Fading", status: "attention" };
  return { days, word: "Peak", status: "good" };
}

/** Bags you are drinking first, newest first; finished ones after. */
export function sortBeans(beans: readonly BeanRow[]): BeanRow[] {
  return [...beans].sort((a, b) => Number(a.isArchived) - Number(b.isArchived) || b.createdAt - a.createdAt);
}

const BeanCard = memo(function BeanCard({ bean, stats, todayDay, onEdit }: { bean: BeanRow; stats: Stats | undefined; todayDay: number; onEdit: (b: BeanRow) => void }) {
  const days = bean.roastedOnEpochDay == null ? null : Math.max(0, todayDay - bean.roastedOnEpochDay);
  const avg = stats?.avg ?? null;
  const cells: Cell[] = [
    // An unknown roast date is the gap worth nudging about.
    { label: "Off roast", value: days == null ? null : String(days), unit: "d", sub: days == null ? "add date" : (roastState(bean, todayDay)?.word.toLowerCase() ?? null) },
    { label: "Brews", value: String(stats?.brews ?? 0) },
    { label: "Avg", value: avg == null ? null : oneDp(avg), sub: avg == null ? null : cupLabel(Math.round(avg)) },
  ];
  return (
    <div className={`${s.cardWrap} ${bean.isArchived ? s.finished : ""}`}>
      <button type="button" className={s.cardHit} onClick={() => onEdit(bean)} aria-label={`Edit ${bean.name.trim() || "coffee"}`} />
      <SoftCard padding="18px 18px 6px">
        <div className={s.cardBody}>
          <div className={s.cardTop}>
            <EyebrowLabel text={bean.roaster.trim() || "Coffee"} />
            {bean.isArchived && <StatusChip status="neutral" text="Finished" />}
          </div>
          <h3 className={`t-headline-s ${s.cardName}`}>{bean.name.trim() || "Unnamed"}</h3>
          <p className={`t-body-s ${s.cardMeta}`}>{beanMeta(bean, todayDay) || "No details yet"}</p>
          <div className={s.cardRule}>
            <HairLine />
          </div>
          <DataGrid cells={cells} columns={3} />
        </div>
      </SoftCard>
    </div>
  );
});

/** Compact and medium: the cards (a grid once the pane is 600 wide). */
export function BeanCards({ beans, brews, onEdit, onAdd }: { beans: readonly BeanRow[]; brews: readonly BrewRow[]; onEdit: (b: BeanRow) => void; onAdd: () => void }) {
  const stats = useMemo(() => statsByBean(brews), [brews]);
  // Read once per mount: the tab stays mounted, and a day boundary mid-session only shifts a label.
  const [todayDay] = useState(() => epochDayOf(Date.now()));
  const sorted = useMemo(() => sortBeans(beans), [beans]);
  if (sorted.length === 0) return <BeansEmpty onAdd={onAdd} />;
  return (
    <ul className={s.cardGrid}>
      {sorted.map((b) => (
        <li key={b.uid}>
          <BeanCard bean={b} stats={b.id != null ? stats.get(b.id) : undefined} todayDay={todayDay} onEdit={onEdit} />
        </li>
      ))}
    </ul>
  );
}

/** Expanded+: compact rows; the selected one is aria-current, J/K and arrows move (roving). */
export function BeanRows({
  beans,
  selectedUid,
  onSelect,
  newRow,
}: {
  beans: readonly BeanRow[];
  selectedUid: string | null;
  onSelect: (uid: string) => void;
  /** An unsaved new coffee shows as the first row while it is being edited. */
  newRow: BeanRow | null;
}) {
  const [todayDay] = useState(() => epochDayOf(Date.now()));
  const rows = useMemo(() => [...(newRow ? [newRow] : []), ...sortBeans(beans)], [beans, newRow]);
  const selectedIndex = rows.findIndex((b) => b.uid === selectedUid);
  const list = useRovingList({ count: rows.length, selectedIndex, onSettle: (i) => onSelect(rows[i]!.uid) });
  return (
    <ul className={s.rowList} aria-label="Coffees">
      {rows.map((b, i) => {
        const state = roastState(b, todayDay);
        const isNew = b === newRow;
        return (
          <li key={b.uid}>
            <button
              type="button"
              {...list.itemProps(i)}
              className={`${s.listRow} interactive-row`}
              aria-current={i === selectedIndex ? "true" : undefined}
              data-finished={b.isArchived}
              onClick={() => onSelect(b.uid)}
            >
              <span className={s.listRowText}>
                <span className={`t-title-m ${s.listRowTitle}`} data-title="">
                  {b.name.trim() || (isNew ? "New coffee" : "Unnamed")}
                </span>
                <span className={`t-body-s ${s.listRowSub}`}>{[b.roaster.trim() || null, b.form === "ground" ? "Ground" : null, b.isArchived ? "Finished" : null].filter(Boolean).join(" · ") || (isNew ? "Not saved yet" : "No roaster")}</span>
              </span>
              {state && (
                <span className={s.listRowEnd}>
                  <span className="num-inline">{state.days} d</span>
                  <StatusChip status={state.status} text={state.word} />
                </span>
              )}
            </button>
          </li>
        );
      })}
    </ul>
  );
}
