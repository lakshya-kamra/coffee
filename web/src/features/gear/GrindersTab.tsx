/**
 * Gear › Grinders (A§7.4, §7.8): one card per grinder with its numbers in the dial's own words,
 * a one-line starting point for the method you brew most, and the "Check the scale" notice when
 * a linked catalog entry disagrees with the row.
 */
import { memo, useMemo } from "react";
import { Dripper } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { useCatalogGrinder } from "@/catalog/useCatalog";
import { DataGrid, EyebrowLabel, HairLine, PillButton, SoftCard, StatusChip, StatusLine, type Cell } from "@/design/components";
import { repo } from "@/data/repo";
import type { BrewRow, GrinderRow } from "@/data/schema";
import { settings } from "@/data/settings";
import { grams, oneDp } from "@/domain/core/format";
import type { BrewMethod } from "@/domain/core/brewer";
import { finerWords } from "@/domain/grind";
import { dialWords, GrindRangeCard, useGrindScale } from "@/features/grind";
import { EmptyGear } from "./BeansTab";
import { SCALE_NOTICE_FOOTNOTE, scaleFromCatalog, scaleNoticeId, scaleNoticeText } from "./mapping";
import s from "./gear.module.css";

interface GrinderUse {
  brews: number;
  method: BrewMethod | null;
}

/** Brews per grinder and the method each one is used for most. */
function useByGrinder(brews: readonly BrewRow[]): Map<number, GrinderUse> {
  return useMemo(() => {
    const counts = new Map<number, Map<BrewMethod, number>>();
    const totals = new Map<number, number>();
    for (const b of brews) {
      if (b.grinderId == null) continue;
      totals.set(b.grinderId, (totals.get(b.grinderId) ?? 0) + 1);
      const brewer = Brewers.byId(b.brewerId) ?? Brewers.resolveOrNull(b.brewer);
      if (!brewer) continue;
      const m = counts.get(b.grinderId) ?? new Map<BrewMethod, number>();
      m.set(brewer.method, (m.get(brewer.method) ?? 0) + 1);
      counts.set(b.grinderId, m);
    }
    const out = new Map<number, GrinderUse>();
    for (const [id, n] of totals) {
      const m = counts.get(id);
      const top = m ? [...m].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null : null;
      out.set(id, { brews: n, method: top });
    }
    return out;
  }, [brews]);
}

const STYLE_LABEL: Readonly<Record<string, string>> = { numbers: "Numbered dial", stepless: "Stepless", microns: "Microns" };

const GrinderCard = memo(function GrinderCard({ g, use, notice, onEdit }: { g: GrinderRow; use: GrinderUse | undefined; notice: boolean; onEdit: (g: GrinderRow) => void }) {
  const scale = useGrindScale(g);
  const entry = useCatalogGrinder(g.catalogId);
  const clicks = g.settingStyle === "clicks";
  const unit = clicks ? undefined : scale.unit || undefined;
  const brews = use?.brews ?? 0;
  const meta = [
    // "Clicks" would repeat the FILTER unit and the direction words, so only dials say it.
    clicks ? null : STYLE_LABEL[g.settingStyle],
    finerWords(scale).replace("settings are", "is"),
    brews === 0 ? "no brews yet" : brews === 1 ? "1 brew" : `${brews} brews`,
  ]
    .filter(Boolean)
    .join(" · ");
  const cells: Cell[] = [
    { label: "Filter", value: g.defaultFilterSetting == null ? null : grams(g.defaultFilterSetting), unit, sub: dialWords(scale, g.defaultFilterSetting) },
    { label: "Per turn", value: g.clicksPerRotation > 0 ? String(g.clicksPerRotation) : null },
    { label: "Burrs", value: g.burrSizeMm.replace(/mm$/i, "").trim() || null, unit: "mm", sub: g.burrType ? g.burrType[0]!.toUpperCase() + g.burrType.slice(1) : null },
  ];
  // The second row only earns its space when the manual's numbers were filled in.
  if (g.micronsPerClick != null || g.defaultEspressoSetting != null) {
    cells.push(
      // Not "µm / CLICK": µ upper-cases to Greek Mu, which reads as "MM".
      { label: "Per click", value: g.micronsPerClick == null ? null : oneDp(g.micronsPerClick), unit: "µm" },
      { label: "Espresso", value: g.defaultEspressoSetting == null ? null : grams(g.defaultEspressoSetting), unit, sub: dialWords(scale, g.defaultEspressoSetting) },
    );
  }
  const cat = notice ? entry : null;
  return (
    <div className={s.cardWrap}>
      <button type="button" className={s.cardHit} onClick={() => onEdit(g)} aria-label={`Edit ${g.brand} ${g.model}`.trim()} />
      <SoftCard padding="18px 18px 6px">
        <div className={s.cardBody}>
          <div className={s.cardTop}>
            <EyebrowLabel text={g.brand.trim() || "Grinder"} />
            {g.isDefault && <StatusChip status="neutral" text="Default" />}
          </div>
          <h3 className={`t-headline-s ${s.cardName}`}>{g.model.trim() || "Unnamed"}</h3>
          <p className={`t-body-s ${s.cardMeta}`}>{meta}</p>
          <div className={s.rangeLine}>
            <GrindRangeCard scale={scale} method={use?.method ?? "pourover"} collapsed />
          </div>
        </div>
        {cat && (
          <div className={s.notice}>
            <StatusLine status="attention" lead="Check the scale" rest={scaleNoticeText(g, cat)} />
            <div className={s.noticeActions}>
              <PillButton
                text="Use these numbers"
                onClick={() => {
                  void repo.upsertGrinder({ ...g, ...scaleFromCatalog(cat), dialFormat: "" }).then(() => settings.removeNotice(scaleNoticeId(g.uid)));
                }}
              />
              <PillButton text="Keep mine" onClick={() => void settings.removeNotice(scaleNoticeId(g.uid))} />
            </div>
            <p className={`t-body-s ${s.noticeNote}`}>{SCALE_NOTICE_FOOTNOTE}</p>
          </div>
        )}
        <div className={s.cardRule}>
          <HairLine />
        </div>
        <DataGrid cells={cells} columns={3} />
      </SoftCard>
    </div>
  );
});

export function GrindersTab({ grinders, brews, notices, onEdit }: { grinders: readonly GrinderRow[]; brews: readonly BrewRow[]; notices: readonly string[]; onEdit: (g: GrinderRow) => void }) {
  const use = useByGrinder(brews);
  const pending = useMemo(() => new Set(notices), [notices]);
  const sorted = useMemo(() => [...grinders].sort((a, b) => Number(b.isDefault) - Number(a.isDefault) || (a.id ?? 0) - (b.id ?? 0)), [grinders]);
  if (sorted.length === 0)
    return (
      <EmptyGear
        text="Grind setting is the variable you will change most often, so it is worth recording which grinder the number belongs to. Pick yours from the list of popular grinders."
        art={<Dripper />}
      />
    );
  return (
    <ul className={s.pane} style={{ paddingTop: 0 }}>
      {sorted.map((g) => (
        <li key={g.uid}>
          <GrinderCard g={g} use={g.id != null ? use.get(g.id) : undefined} notice={pending.has(scaleNoticeId(g.uid))} onEdit={onEdit} />
        </li>
      ))}
    </ul>
  );
}
