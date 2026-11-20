/**
 * Full port of Numerals.kt DataGrid: rows split by hairlines, columns by vertical hairlines, a
 * label over each value, values in their status colour, text values (a grind word) as titles, and
 * an optional sub line (plain, tinted, or a status chip).
 */
import { memo, type CSSProperties } from "react";
import { EyebrowLabel, NumberWithUnit, StatusChip } from "@/design/components";
import { statusColorVar, type Status } from "@/domain/core/status";
import v from "./viz.module.css";

export interface GridCell {
  label: string;
  value: string | null;
  unit?: string | null;
  /** Show the value as words (a grind description), not a numeral. */
  textValue?: boolean;
  status?: Status | null;
  sub?: string | null;
  subStatus?: Status | null;
  /** A status sub renders as a chip unless this is false. */
  subChip?: boolean;
  /** The full text behind a shortened value (a recipe's grind sentence), as a native tooltip. */
  title?: string;
}

const tinted = (s: Status | null | undefined) => (s === "good" || s === "attention" || s === "fault" ? statusColorVar(s) : undefined);

export const InfoGrid = memo(function InfoGrid({ cells, columns = 3, compact = false, numeral = "num-medium" }: { cells: readonly GridCell[]; columns?: number; compact?: boolean; numeral?: "num-medium" | "num-small" }) {
  const cols = Math.max(1, columns);
  const rows: GridCell[][] = [];
  for (let i = 0; i < cells.length; i += cols) rows.push(cells.slice(i, i + cols));
  return (
    <div className={v.grid} data-compact={compact || undefined}>
      {rows.map((row, r) => (
        // A custom property, not an inline template, so a narrow recipe card can re-share the columns.
        <div key={r} className={v.gridRow} style={{ "--grid-cols": cols } as CSSProperties}>
          {row.map((c) => (
            <div key={c.label} className={v.gridCell}>
              <EyebrowLabel text={c.label} />
              {c.value == null ? (
                <span className={`${compact ? v.compactNum : numeral} ink-low`}>—</span>
              ) : c.textValue ? (
                // Words wrap to a second line rather than end in "…" (A7); "Medium-fine" breaks at its hyphen.
                <span className={`t-title-m ${v.clamp2}`} title={c.title}>
                  {c.value}
                </span>
              ) : compact ? (
                <span className={v.nwuCompact} style={{ color: tinted(c.status) }}>
                  <span className={v.compactNum}>{c.value}</span>
                  {c.unit && <span className={`num-unit ${v.compactUnit}`}>{c.unit}</span>}
                </span>
              ) : (
                <NumberWithUnit value={c.value} unit={c.unit ?? undefined} numeral={numeral} color={tinted(c.status)} />
              )}
              {c.sub &&
                (c.subStatus && c.subChip !== false ? (
                  <span className={v.gridSubChip}>
                    <StatusChip status={c.subStatus} text={c.sub} />
                  </span>
                ) : (
                  <span className={`${v.gridSub} ${v.ellipsis}`} style={{ color: c.subStatus ? statusColorVar(c.subStatus) : undefined }}>
                    {c.sub}
                  </span>
                ))}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
});
