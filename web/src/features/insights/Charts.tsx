/**
 * Port of InsightsCharts.kt as inline SVG/HTML from pure models (web-spec §11 Insights): bars grow
 * with scaleY from the baseline, points fade in, once per session (rule 7). Each chart has a
 * summary label and a visually hidden list of its values.
 *
 * v4 §3.9: x-axis date ticks and labels at the normal label size (HTML, so they never scale with
 * the drawing); a pointer shows a crosshair and a tooltip ("Thu 12 Nov · Hoffmann V60 · 8/10"),
 * a click opens that brew in Log; the plot takes focus, Left/Right move between points, Enter
 * opens. The grind chart's x positions are percentages, so it fills any card width at one height.
 */
import { useEffect, useId, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from "react";
import { HairLine } from "@/design/components";
import { oneDp, plural } from "@/domain/core/format";
import { scoreStatus, statusColorVar } from "@/domain/core/status";
import { dayMonthCaps, grindChartModel, type GrindSeries, type Ranked, type ScorePoint } from "@/domain/insights";
import { chartKeyStep, nearestIndex, slotCentres, spacedTicks, tickDate, tickIndices, tooltipAlign } from "./chartMath";
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

/**
 * The layer over a plot that turns it into something you can point at and step through. `fracs`
 * are the points' x positions (0..1 of the plot); `texts` their tooltips; `onOpen` opens one.
 */
function ChartInteraction({ fracs, texts, onOpen, label }: { fracs: readonly number[]; texts: readonly string[]; onOpen: (index: number) => void; label: string }) {
  const [active, setActive] = useState<number | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const hintId = useId();
  const at = (e: ReactPointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return r.width > 0 ? nearestIndex(fracs, (e.clientX - r.left) / r.width) : -1;
  };
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key === "Enter" && active != null) {
      e.preventDefault();
      onOpen(active);
      return;
    }
    if (e.key === "Escape" && active != null) {
      e.preventDefault();
      e.stopPropagation();
      setActive(null);
      return;
    }
    const next = chartKeyStep(e.key, active, fracs.length);
    if (next == null) return;
    e.preventDefault();
    setActive(next);
  };
  const f = active != null ? (fracs[active] ?? 0) : 0;
  const align = tooltipAlign(f);
  return (
    <div
      ref={box}
      className={i.interact}
      tabIndex={0}
      // A slider is what it is to a keyboard: Left/Right pick a brew, and its value text is the tooltip.
      role="slider"
      aria-label={label}
      aria-describedby={hintId}
      aria-orientation="horizontal"
      aria-valuemin={1}
      aria-valuemax={Math.max(1, fracs.length)}
      aria-valuenow={(active ?? fracs.length - 1) + 1}
      aria-valuetext={active != null ? texts[active] : "Press Left or Right to read each brew"}
      onPointerMove={(e) => {
        if (e.pointerType !== "mouse") return;
        const n = at(e);
        if (n >= 0 && n !== active) setActive(n);
      }}
      onPointerLeave={(e) => {
        if (e.pointerType === "mouse" && document.activeElement !== box.current) setActive(null);
      }}
      onClick={(e) => {
        // A tap picks the point under the finger; a click opens what the crosshair is on.
        const n = at(e as unknown as ReactPointerEvent<HTMLDivElement>);
        if (n >= 0) onOpen(n);
      }}
      onBlur={() => setActive(null)}
      onKeyDown={onKeyDown}
    >
      <span id={hintId} className="visually-hidden">
        Left and Right move between brews, Enter opens one.
      </span>
      {active != null && (
        <>
          {/* Moved with transform only: the wrapper is plot-wide, so translateX(f * 100%) lands on the point. */}
          <div className={i.cross} style={{ transform: `translateX(${f * 100}%)` }} aria-hidden="true">
            <span className={i.crossLine} />
            <span className={`t-label-m ${i.tip}`} data-align={align}>
              {texts[active]}
            </span>
          </div>
        </>
      )}
    </div>
  );
}

/** x-axis date ticks under a plot: HTML, positioned by fraction, the ends hugging the edges. */
function DateTicks({ fracs, ats, max = 5 }: { fracs: readonly number[]; ats: readonly number[]; max?: number }) {
  const idx = spacedTicks(fracs, tickIndices(fracs.length, max));
  return (
    <div className={i.ticks} aria-hidden="true">
      {idx.map((n) => {
        const f = fracs[n]!;
        return (
          <span key={n} className={`t-label-m ${i.tick}`} data-align={tooltipAlign(f)} style={{ left: `${f * 100}%` }}>
            {tickDate(ats[n]!)}
          </span>
        );
      })}
    </div>
  );
}

const SCORE_KEY = [
  { word: "Great", range: "8–10", score: 9 },
  { word: "Good", range: "6–7", score: 6 },
  { word: "Off", range: "4–5", score: 4 },
  { word: "Faulty", range: "1–3", score: 2 },
] as const;

export function ScoreBars({ points, textOf, onOpen }: { points: readonly ScorePoint[]; textOf: (p: ScorePoint) => string; onOpen: (p: ScorePoint) => void }) {
  const animate = useOncePerSession("score");
  const fracs = slotCentres(points.length);
  const summary = `Scores of your last ${plural(points.length, "rated brew")}, oldest first: ${points.map((p) => p.score).join(", ")}.`;
  return (
    <figure className={i.bars} aria-label={summary}>
      <div className={i.barsArea}>
        <span className={i.gridLine} style={{ top: 0 }} aria-hidden="true" />
        <span className={i.gridLine} style={{ top: "50%" }} aria-hidden="true" />
        <span className={i.gridLine} style={{ bottom: 0 }} aria-hidden="true" />
        <span className={`t-label-m ${i.gridLabel}`} style={{ top: 0 }} aria-hidden="true">
          10
        </span>
        <span className={`t-label-m ${i.gridLabel}`} style={{ top: "50%" }} aria-hidden="true">
          5
        </span>
        <div className={i.barsPlot}>
          <div className={i.barsRow} aria-hidden="true">
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
          <ChartInteraction fracs={fracs} texts={points.map(textOf)} onOpen={(n) => points[n] && onOpen(points[n])} label="Score chart" />
        </div>
      </div>
      <div className={i.ticksRow}>
        <DateTicks fracs={fracs} ats={points.map((p) => p.at)} max={4} />
      </div>
      {/* Status colour always with a word: what each bar colour means. */}
      <p className={`t-label-m ${i.scoreKey}`} aria-hidden="true">
        {SCORE_KEY.map((k) => (
          <span key={k.word} className={i.scoreKeyItem}>
            <span className={i.scoreKeySwatch} style={{ background: chartInk(k.score) }} />
            {k.word} {k.range}
          </span>
        ))}
      </p>
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
export function GrindJourneyChart({ series, textOf, onOpen }: { series: GrindSeries; textOf: (index: number) => string; onOpen: (index: number) => void }) {
  const animate = useOncePerSession("grind");
  const m = grindChartModel(series);
  // The model draws in a 320 wide box with 8 px insets and a 28 px label gutter; x becomes a fraction of the plot.
  const left = 8;
  const span = m.plotRight - 16;
  const fracs = m.points.map((p) => (span > 0 ? (p.x - left) / span : 0.5));
  const pct = (f: number) => `${(f * 100).toFixed(2)}%`;
  const summary = `Grind on ${series.grinderLabel} for ${series.recipeName}: from ${series.first ?? "—"} to ${series.latest ?? "—"} clicks over ${plural(series.points.length, "brew")}${series.bestZone ? `, best scores between ${series.bestZone.first} and ${series.bestZone.last}` : ""}.`;
  const latest = m.points.length ? m.points.length - 1 : -1;
  const latestF = latest >= 0 ? fracs[latest]! : 0;
  // The label's right edge in model px left of the point (the model draws x in its own 320 box).
  const labelGap = m.latestLabel && latest >= 0 ? m.points[latest]!.x - m.latestLabel.x : 0;
  return (
    <figure className={i.grind} aria-label={summary}>
      <div className={i.grindArea} style={{ height: m.height }}>
        <span className={`t-label-m ${i.axisWord}`} style={{ top: 0 }} aria-hidden="true">
          COARSER ↑
        </span>
        <span className={`t-label-m ${i.axisWord}`} style={{ bottom: 0 }} aria-hidden="true">
          FINER ↓
        </span>
        {m.grid.map((g) => (
          <span key={g.value} aria-hidden="true">
            <span className={i.gridLine} style={{ top: g.y }} />
            {g.showLabel && (
              <span className={`t-label-m ${i.gridLabel}`} style={{ top: g.y }}>
                {g.value}
              </span>
            )}
          </span>
        ))}
        {m.band && (
          <span className={i.band} style={{ top: m.band.top, height: m.band.bottom - m.band.top }} aria-hidden="true">
            <span className={`t-label-m ${i.bandLabel}`}>{m.band.label}</span>
          </span>
        )}
        <div className={i.grindPlot}>
          <svg className={i.grindSvg} width="100%" height="100%" aria-hidden="true">
            {/* No heavier than a neutral point, so the coloured points read first. */}
            {m.points.length > 1 && (
              <g className={i.line} data-animate={animate}>
                {m.points.slice(1).map((p, n) => (
                  <line key={n} x1={pct(fracs[n]!)} y1={m.points[n]!.y} x2={pct(fracs[n + 1]!)} y2={p.y} stroke="var(--neutral-mark)" strokeWidth={1.5} />
                ))}
              </g>
            )}
            {m.points.map((p, n) =>
              p.latest ? (
                <g key={n} className={i.point} data-animate={animate} style={{ animationDelay: `${Math.min(300, n * 25)}ms` }}>
                  <circle cx={pct(fracs[n]!)} cy={p.y} r={8} fill="var(--surface1)" />
                  <circle cx={pct(fracs[n]!)} cy={p.y} r={6} fill={chartInk(p.score)} className={i.mark} />
                </g>
              ) : (
                <circle key={n} className={`${i.point} ${i.mark}`} data-animate={animate} style={{ animationDelay: `${Math.min(300, n * 25)}ms` }} cx={pct(fracs[n]!)} cy={p.y} r={4} fill={chartInk(p.score)} />
              ),
            )}
          </svg>
          {m.latestLabel && latest >= 0 && (
            <span className={`t-label-m ${i.latestLabel}`} style={{ left: `calc(${pct(latestF)} - ${labelGap}px)`, top: m.latestLabel.y }} aria-hidden="true">
              {m.latestLabel.text}
            </span>
          )}
          <ChartInteraction fracs={fracs} texts={m.points.map((_, n) => textOf(n))} onOpen={onOpen} label="Grind chart" />
        </div>
      </div>
      <div className={i.grindTicks}>
        <DateTicks fracs={fracs} ats={series.points.map((p) => p.at)} max={4} />
      </div>
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

