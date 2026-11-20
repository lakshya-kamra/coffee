/**
 * Label-above-value cells that wrap 3 -> 2 -> 1 by container width (v4 §5 A7), for Today's stats
 * and the recipe numbers. Thresholds are in rem, so 200% text wraps sooner. Numbers never
 * ellipsize: a value that cannot fit wraps under its label instead. `tiles` gives each cell its
 * own surface (the dashboard's small tiles); otherwise the cells share the card they sit in.
 */
import { memo, type ReactNode } from "react";
import { EyebrowLabel, NumberWithUnit, StatusChip } from "@/design/components";
import { statusColorVar, type Status } from "@/domain/core/status";
import g from "./statGrid.module.css";

export interface StatCell {
  readonly key: string;
  /** Plain text, or a <Term> that explains the word. */
  readonly label: ReactNode;
  readonly value: string | null;
  readonly unit?: string | null;
  /** Words (a grind description), shown as a wrapping title rather than a numeral. */
  readonly text?: boolean;
  readonly status?: Status | null;
  readonly sub?: ReactNode;
  readonly subStatus?: Status | null;
  /** A status sub as a chip (the colour always comes with its word). */
  readonly subChip?: boolean;
}

const tinted = (s: Status | null | undefined) => (s === "good" || s === "attention" || s === "fault" ? statusColorVar(s) : undefined);

export const StatGrid = memo(function StatGrid({ cells, tiles = false, numeral = "num-medium", label }: { cells: readonly StatCell[]; tiles?: boolean; numeral?: "num-medium" | "num-small"; label?: string }) {
  return (
    <div className={g.wrap} data-tiles={tiles || undefined}>
      <dl className={g.grid} aria-label={label}>
        {cells.map((c) => (
          <div key={c.key} className={g.cell}>
            <dt className={g.label}>{typeof c.label === "string" ? <EyebrowLabel text={c.label} /> : c.label}</dt>
            <dd className={g.value}>
              {c.value == null ? (
                <span className={`${numeral} ink-low`}>—</span>
              ) : c.text ? (
                <span className={`t-title-m ${g.text}`}>{c.value}</span>
              ) : (
                <NumberWithUnit value={c.value} unit={c.unit ?? undefined} numeral={numeral} color={tinted(c.status)} />
              )}
            </dd>
            {c.sub != null && c.sub !== "" && (
              <dd className={g.sub}>
                {c.subStatus && c.subChip !== false && typeof c.sub === "string" ? (
                  <StatusChip status={c.subStatus} text={c.sub} />
                ) : (
                  <span className="t-body-s ink-mid" style={{ color: c.subStatus ? statusColorVar(c.subStatus) : undefined }}>
                    {c.sub}
                  </span>
                )}
              </dd>
            )}
          </div>
        ))}
      </dl>
    </div>
  );
});
