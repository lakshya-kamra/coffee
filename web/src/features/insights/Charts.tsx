/**
 * Port of InsightsCharts.kt as inline SVG/HTML from pure models (web-spec §11 Insights): bars grow
 * with scaleY from the baseline, points fade in, once per session (rule 7). Each chart is
 * role="img" with a summary label and a visually hidden list of its values.
 */
import { useEffect, useState } from "react";
import { HairLine } from "@/design/components";
import { oneDp, plural } from "@/domain/core/format";
import { scoreStatus, statusColorVar } from "@/domain/core/status";
import { axisDate, dayMonthCaps, grindChartModel, type GrindSeries, type Ranked, type ScorePoint } from "@/domain/insights";
import i from "./insights.module.css";

/** A score's mark: the status colours carry the story; a neutral 6-7 is the quietest scored mark. */
function chartInk(score: number): string {
  const s = scoreStatus(score);
  if (s === "unrated") return "var(--ink-faint)";
  if (s === "neutral") return "var(--neutral-mark)";
  return statusColorVar(s);
}

const played = new Set<string>();

/** True on the first mount of this chart in the session; the flag is set after paint. */
function useOncePerSession(key: string): boolean {
  const [animate] = useState(() => !played.has(key));
  useEffect(() => {
    played.add(key);
  }, [key]);
  return animate;
}

export function ScoreBars({ points }: { points: readonly ScorePoint[] }) {
  const animate = useOncePerSession("score");
  const start = points[0] ? axisDate(points[0].at, points.length === 1) : null;
  const end = points.length > 1 ? axisDate(points[points.length - 1]!.at, true) : null;
  const summary = `Scores of your last ${plural(points.length, "rated brew")}, oldest first: ${points.map((p) => p.score).join(", ")}.`;
  return (
    <figure className={i.bars} role="img" aria-label={summary}>
      <span className={i.gridLine} style={{ top: 6 }} aria-hidden="true" />
      <span className={i.gridLine} style={{ top: 51 }} aria-hidden="true" />
      <span className={i.gridLine} style={{ top: 96 }} aria-hidden="true" />
      <span className={`micro ${i.gridLabel}`} style={{ top: 6 }} aria-hidden="true">
        10
      </span>
      <span className={`micro ${i.gridLabel}`} style={{ top: 51 }} aria-hidden="true">
        5
      </span>
      <div className={i.barsPlot} aria-hidden="true">
        {points.map((p, n) => (
          <div key={p.uid} className={i.slot}>
            {p.score <= 0 ? (
              <span className={i.stub} style={{ background: chartInk(p.score) }} />
            ) : (
              <span className={i.bar} data-animate={animate} style={{ height: `${p.score * 10}%`, background: chartInk(p.score), animationDelay: `${Math.min(240, n * 30)}ms` }} />
            )}
          </div>
        ))}
      </div>
      <div className={`micro ${i.axisLabels}`} aria-hidden="true">
        <span>{start}</span>
        <span>{end}</span>
      </div>
      <ul className="visually-hidden">
        {points.map((p) => (
          <li key={p.uid}>
            {dayMonthCaps(p.at)}: {p.score} of 10
          </li>
        ))}
      </ul>
    </figure>
  );
}

/** Clicks over time for one grinder on one recipe, coarser up, the best-scoring band behind. */
export function GrindJourneyChart({ series }: { series: GrindSeries }) {
  const animate = useOncePerSession("grind");
  const m = grindChartModel(series);
  const summary = `Grind on ${series.grinderLabel} for ${series.recipeName}: from ${series.first ?? "—"} to ${series.latest ?? "—"} clicks over ${plural(series.points.length, "brew")}${series.bestZone ? `, best scores between ${series.bestZone.first} and ${series.bestZone.last}` : ""}.`;
  return (
    <figure style={{ margin: 0 }}>
      <svg className={i.chart} viewBox={`0 0 ${m.width} ${m.height}`} role="img" aria-label={summary}>
        {m.grid.map((g) => (
          <g key={g.value}>
            <line x1={0} x2={m.plotRight} y1={g.y} y2={g.y} stroke="var(--hairline)" strokeWidth={1} />
            {g.showLabel && (
              <text className={i.chartValue} x={m.width} y={g.y} textAnchor="end" dominantBaseline="central">
                {g.value}
              </text>
            )}
          </g>
        ))}
        {m.band && (
          <>
            <rect x={0} y={m.band.top} width={m.plotRight} height={m.band.bottom - m.band.top} fill="var(--good)" opacity={0.11} />
            <text className={i.chartText} x={4} y={(m.band.top + m.band.bottom) / 2} dominantBaseline="central" style={{ fill: "var(--good)" }}>
              {m.band.label}
            </text>
          </>
        )}
        <text className={i.chartText} x={0} y={0} dominantBaseline="hanging">
          COARSER ↑
        </text>
        <text className={i.chartText} x={0} y={m.height} dominantBaseline="auto">
          FINER ↓
        </text>
        {/* No heavier than a neutral point, so the coloured points read first. */}
        {m.line && <path className={i.line} data-animate={animate} d={m.line} fill="none" stroke="var(--neutral-mark)" strokeWidth={1.5} />}
        {m.points.map((p, n) =>
          p.latest ? (
            <g key={n} className={i.point} data-animate={animate} style={{ animationDelay: `${Math.min(300, n * 25)}ms` }}>
              <circle cx={p.x} cy={p.y} r={8} fill="var(--surface1)" />
              <circle cx={p.x} cy={p.y} r={6} fill={chartInk(p.score)} />
            </g>
          ) : (
            <circle key={n} className={i.point} data-animate={animate} style={{ animationDelay: `${Math.min(300, n * 25)}ms` }} cx={p.x} cy={p.y} r={4} fill={chartInk(p.score)} />
          ),
        )}
        {m.latestLabel && (
          <text className={i.chartText} x={m.latestLabel.x} y={m.latestLabel.y} textAnchor="middle" style={{ fill: "var(--ink-high)" }}>
            {m.latestLabel.text}
          </text>
        )}
      </svg>
      <ul className="visually-hidden">
        {series.points.map((p) => (
          <li key={p.id}>
            {dayMonthCaps(p.at)}: {p.clicks} clicks, {p.score > 0 ? `scored ${p.score}` : "not rated"}
          </li>
        ))}
      </ul>
    </figure>
  );
}

/** A short leaderboard: rank, name with a proportional bar, brew count, the average in its colour. */
export function RankedList({ items, max = 3 }: { items: readonly Ranked[]; max?: number }) {
  const shown = items.slice(0, max);
  const maxCount = Math.max(1, ...shown.map((r) => r.count));
  return (
    <ol className={i.ranked}>
      {shown.map((item, n) => (
        <li key={`${item.name}-${n}`}>
          {n > 0 && <HairLine inset={28} />}
          <div className={i.rankRow}>
            <span className={`num-small ${i.rankNum}`}>{n + 1}</span>
            <div className={i.rankMain}>
              <span className={`t-title-m ${i.ellipsis}`}>{item.name}</span>
              {item.sub && <span className={`t-body-s ink-mid ${i.ellipsis}`} style={{ fontSize: "0.75rem" }}>{item.sub}</span>}
              <span className={i.rankBar} style={{ transform: `scaleX(${item.count / maxCount})` }} aria-hidden="true" />
            </div>
            <div className={i.rankStat}>
              <span className={i.rankValue}>{item.count}</span>
              <span className="micro ink-low">{item.count === 1 ? "BREW" : "BREWS"}</span>
            </div>
            {/* Labelled like the count beside it: a coloured number never stands without a word. */}
            <div className={i.rankStat}>
              <span className={i.rankValue} style={{ color: item.avg != null ? statusColorVar(scoreStatus(Math.round(item.avg))) : "var(--ink-low)" }}>
                {item.avg != null ? oneDp(item.avg) : "—"}
              </span>
              <span className="micro ink-low">AVG</span>
            </div>
          </div>
        </li>
      ))}
    </ol>
  );
}
