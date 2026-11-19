/** Gear › Beans (A§7.8): one card per bag, newest first; finished bags stay but step back. */
import { memo, useMemo, useState, type ReactNode } from "react";
import { CoffeeMug } from "@/art";
import { catalog } from "@/catalog/catalog";
import { DataGrid, EyebrowLabel, HairLine, SoftCard, StatusChip, type Cell } from "@/design/components";
import type { BeanRow, BrewRow } from "@/data/schema";
import { epochDayOf, oneDp } from "@/domain/core/format";
import { cupLabel, roastWord } from "@/domain/core/status";
import { restStatus } from "./mapping";
import s from "./gear.module.css";

export function EmptyGear({ text, art }: { text: string; art: ReactNode }) {
  return (
    <SoftCard>
      <div className={s.empty}>
        <div className={s.emptyArt} aria-hidden="true">
          {art}
        </div>
        <p className={`t-body-m ${s.emptyText}`}>{text}</p>
      </div>
    </SoftCard>
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
  return [place, catalog.label("process", b.processId), catalog.label("roastLevel", b.roastLevelId), restStatus(b, todayDay)].filter(Boolean).join(" · ");
}

const BeanCard = memo(function BeanCard({ bean, stats, todayDay, onEdit }: { bean: BeanRow; stats: Stats | undefined; todayDay: number; onEdit: (b: BeanRow) => void }) {
  const days = bean.roastedOnEpochDay == null ? null : Math.max(0, todayDay - bean.roastedOnEpochDay);
  const avg = stats?.avg ?? null;
  const cells: Cell[] = [
    // An unknown roast date is the gap worth nudging about.
    { label: "Off roast", value: days == null ? null : String(days), unit: "d", sub: days == null ? "add date" : roastWord(days) },
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

export function BeansTab({ beans, brews, onEdit }: { beans: readonly BeanRow[]; brews: readonly BrewRow[]; onEdit: (b: BeanRow) => void }) {
  const stats = useMemo(() => statsByBean(brews), [brews]);
  // Read once per mount: the tab stays mounted, and a day boundary mid-session only shifts a label.
  const [todayDay] = useState(() => epochDayOf(Date.now()));
  // Bags you are drinking first, newest first; finished ones after.
  const sorted = useMemo(() => [...beans].sort((a, b) => Number(a.isArchived) - Number(b.isArchived) || b.createdAt - a.createdAt), [beans]);
  if (sorted.length === 0)
    return (
      <EmptyGear
        text="Add the bag you are drinking. Roast date is the one field that pays for itself, because most days you will not remember it."
        art={<CoffeeMug fill={0.6} />}
      />
    );
  return (
    <ul className={s.pane} style={{ paddingTop: 0 }}>
      {sorted.map((b) => (
        <li key={b.uid}>
          <BeanCard bean={b} stats={b.id != null ? stats.get(b.id) : undefined} todayDay={todayDay} onEdit={onEdit} />
        </li>
      ))}
    </ul>
  );
}
