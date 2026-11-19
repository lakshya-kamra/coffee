/**
 * Port of Timeline.kt: a vertical timeline with a continuous rail. It has no card of its own: put
 * it in a SoftCard. The last actual row gets a surface2 band so "where you ended up" stands out.
 * Planned rows are grey; actual rows are bold, coloured by their status, with a delta chip. To
 * show planned and actual on the same row, put the actual time in `time` and the plan in `planned`.
 */
import { memo } from "react";
import { StatusChip, toCaps } from "@/design/components";
import { clock } from "@/domain/core/format";
import { statusColorVar } from "@/domain/core/status";
import type { TimelineRow } from "./journey";
import v from "./viz.module.css";

/** The rail node's centre from the row top: 10 px padding + 10 px into the first line. */
const CY = 20;

export const TimelineCard = memo(function TimelineCard({ rows, detailMaxLines = 2 }: { rows: readonly TimelineRow[]; detailMaxLines?: number }) {
  let highlight = -1;
  rows.forEach((r, i) => {
    if (r.actual) highlight = i;
  });
  return (
    <ol className={v.timeline}>
      {rows.map((row, i) => {
        const first = i === 0;
        const last = i === rows.length - 1;
        const status = row.deltaStatus ?? null;
        const coloured = row.actual && (status === "good" || status === "attention" || status === "fault");
        const nodeInk = row.actual && status ? statusColorVar(status) : "var(--ink-high)";
        const nodeR = row.terminal || row.actual ? 5 : 4;
        const gap = row.terminal ? nodeR : row.actual ? nodeR + 3 : 0;
        const time = row.time ?? (row.startSec != null ? clock(row.startSec) : "");
        return (
          <li key={i} className={v.tlRow} data-highlight={i === highlight || undefined}>
            <span className={v.tlRail} aria-hidden="true">
              {!first && <span className={v.tlSeg} style={{ top: 0, height: CY - gap }} />}
              {!(row.terminal || last) && <span className={v.tlSeg} style={{ top: CY + gap, bottom: 0 }} />}
              <span
                className={v.tlNode}
                data-kind={row.terminal ? "terminal" : row.actual ? "actual" : "planned"}
                style={{ top: CY - nodeR, width: nodeR * 2, height: nodeR * 2, marginLeft: -nodeR, ...(row.actual && !row.terminal ? { background: nodeInk } : {}) }}
              />
            </span>
            <span className={v.tlTime}>
              <span className="num-inline" style={{ color: !row.actual ? "var(--ink-mid)" : coloured ? nodeInk : "var(--ink-high)" }}>
                {time}
              </span>
              {row.planned && <s className={`num-inline ${v.tlPlanned}`}>{row.planned}</s>}
            </span>
            <span className={v.tlBody}>
              {row.kind && <span className="micro ink-low">{toCaps(row.kind)}</span>}
              <span className="t-title-m" style={{ fontWeight: row.actual ? 700 : 500 }}>
                {row.title}
              </span>
              {row.detail && (
                <span className={`t-body-s ink-mid ${v.clamp}`} style={detailMaxLines > 0 && detailMaxLines < 100 ? { WebkitLineClamp: detailMaxLines } : { display: "block" }}>
                  {row.detail}
                </span>
              )}
            </span>
            {row.actual && row.delta ? (
              <span className={v.tlTrail}>
                <StatusChip status={status ?? "neutral"} text={row.delta} />
              </span>
            ) : row.trailing ? (
              <span className={`num-inline ${v.tlTrail}`}>{row.trailing}</span>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
});
