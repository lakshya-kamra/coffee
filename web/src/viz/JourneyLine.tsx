/**
 * Port of JourneyLine.kt: the whole brew as a route with a droplet travelling along it.
 * - live: the brew in progress, crema, droplet moving, labelled nodes below. `progress` is a
 *   getter read once per frame by the shared frame loop; React never renders per frame (rule 4).
 * - historical: a finished brew in ink, with a planned-end tick and an actual-end cap.
 * - compact: a 24 px route line for cards, no labels.
 * Drawn in one SVG sized to the measured width. A mask clears the line around nodes and the
 * droplet, so the line can sit on a card, the canvas or the brew mesh without knowing its colour.
 */
import { memo, useId, useMemo, useRef } from "react";
import { statusColorVar, type Status } from "@/domain/core/status";
import { NumberWithUnit, toCaps, type NumeralStyle } from "@/design/components";
import { useFrame } from "./frameLoop";
import { nodeFraction, type JourneyNode } from "./journey";
import { useWidth } from "./measure";
import v from "./viz.module.css";

export type JourneyStyle = "live" | "historical" | "compact";

/** A teardrop, point up, centred on the origin so drawing it is a single translate. */
function teardrop(w: number, h: number): string {
  const r = w / 2;
  const top = -h / 2;
  const cy = h / 2 - r;
  const shoulder = top + (cy - top) * 0.45;
  const n = (x: number) => x.toFixed(2);
  return `M0 ${n(top)}C${n(r * 0.25)} ${n(shoulder)} ${n(r)} ${n(cy - r * 0.35)} ${n(r)} ${n(cy)}A${n(r)} ${n(r)} 0 0 1 ${n(-r)} ${n(cy)}C${n(-r)} ${n(cy - r * 0.35)} ${n(-r * 0.25)} ${n(shoulder)} 0 ${n(top)}Z`;
}

/** Approximate rendered widths (no text measurement in render): micro caps and the endpoint numerals. */
const microWidth = (t: string) => t.length * 7.2;
const endpointWidth = (t: string) => t.length * 10.4;

/** The facts a finished brew labels under its line; planned pour times would crowd them out. */
const labelledWhenHistorical = (n: JourneyNode) => n.label != null && ((n.priority ?? 0) >= 2 || n.kind === "actual");

/** A document-unique id usable inside url(#…): React's ids contain characters that need escaping. */
export function useSvgId(prefix: string): string {
  return `${prefix}-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
}

interface Placed {
  text: string;
  left: number;
  color: string;
}

export const JourneyLine = memo(function JourneyLine({
  nodes,
  style = "live",
  progress,
  totalSec,
  startLabel,
  endLabel,
  actualEnd,
  endStatus,
  overrunLabel,
  actualLabel,
  label,
}: {
  nodes: readonly JourneyNode[];
  style: JourneyStyle;
  /** 0..1 of the plan. A getter is read every frame (live); a number is drawn once. */
  progress?: number | (() => number);
  /** Needed only for nodes given as `atSec`. */
  totalSec?: number;
  startLabel?: string;
  endLabel?: string;
  /** Historical: where the brew actually ended, as a fraction of the plan (may exceed 1). */
  actualEnd?: number | null;
  endStatus?: Status | null;
  overrunLabel?: string | null;
  actualLabel?: string | null;
  /** Accessible summary; the drawing itself is decorative. */
  label?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const w = useWidth(box);
  const live = style === "live";
  const historical = style === "historical";
  const compact = style === "compact";
  const getter = typeof progress === "function" ? progress : null;
  const fixed = typeof progress === "number" ? progress : 0;

  const shownNodes = useMemo(
    () =>
      nodes
        .map((n) => ({ n, f: nodeFraction(n, totalSec) }))
        .filter((x): x is { n: JourneyNode; f: number } => x.f != null),
    [nodes, totalSec],
  );
  const historicalLabels = historical && (actualLabel != null || overrunLabel != null || shownNodes.some((x) => labelledWhenHistorical(x.n)));
  const height = compact ? 24 : historical && !historicalLabels ? 46 : 64;

  const g = useMemo(() => {
    const strokeW = compact ? 2 : 3;
    const nodeR = (compact ? 7 : 9) / 2;
    const ringW = 1.5;
    const dropH = compact ? 11 : 14;
    const dropW = dropH * 0.72;
    const haloR = dropH / 2 + 2;
    const capR = 5;
    const lineY = compact ? height / 2 : 34;
    const maxX = historical ? Math.max(1, actualEnd ?? 1) : 1;
    const inset = Math.max(dropW / 2, nodeR, capR) + 1;
    const span = Math.max(1, w - 2 * inset);
    const xOf = (f: number) => inset + span * (f / maxX);
    const shown = shownNodes.filter((x) => x.f >= 0 && x.f <= maxX);
    return { strokeW, nodeR, ringW, dropH, dropW, haloR, capR, lineY, maxX, inset, span, xOf, shown, droplet: teardrop(dropW, dropH) };
  }, [compact, height, historical, actualEnd, w, shownNodes]);

  // Labels: endpoints above the line; node labels below, highest priority first, then left to
  // right; a label is dropped if it starts within 36 px of a kept one or would touch it. A
  // finished brew's actual end outranks every node.
  const labels = useMemo(() => {
    if (compact || w <= 0) return { below: [] as Placed[], start: null as Placed | null, end: null as Placed | null };
    const kept: { left: number; right: number }[] = [];
    const below: Placed[] = [];
    const order = g.shown
      .filter((x) => (live ? x.n.label != null : labelledWhenHistorical(x.n)))
      .sort((a, b) => (b.n.priority ?? 0) - (a.n.priority ?? 0) || a.f - b.f);
    const actualInk = endStatus == null || endStatus === "neutral" ? "var(--ink-high)" : statusColorVar(endStatus);
    const candidates: [string, number, string][] = [];
    if (historical && actualEnd != null && actualLabel) candidates.push([actualLabel, g.xOf(Math.min(Math.max(actualEnd, 0), g.maxX)), actualInk]);
    for (const x of order) candidates.push([x.n.label!, g.xOf(x.f), "var(--ink-low)"]);
    for (const [text, x, color] of candidates) {
      const lw = microWidth(text);
      const left = Math.min(Math.max(0, x - lw / 2), Math.max(0, w - lw));
      const right = left + lw;
      const clash = kept.some((k) => Math.abs(left - k.left) < 36 || (left < k.right + 6 && right > k.left - 6));
      if (!clash) {
        kept.push({ left, right });
        below.push({ text, left, color });
      }
    }
    const start = startLabel ? { text: startLabel, left: 0, color: "var(--ink-low)" } : null;
    const end = endLabel ? { text: endLabel, left: Math.max(0, Math.min(w, g.xOf(1) + g.inset) - endpointWidth(endLabel)), color: "var(--ink-high)" } : null;
    return { below, start, end };
  }, [compact, w, g, live, historical, actualEnd, actualLabel, endStatus, startLabel, endLabel]);

  const maskId = useSvgId("jm");
  const elapsedRef = useRef<SVGLineElement>(null);
  const dropRef = useRef<SVGGElement>(null);
  const dropPath = useRef<SVGPathElement>(null);
  const haloRef = useRef<SVGCircleElement>(null);
  const litRefs = useRef<(SVGCircleElement | null)[]>([]);
  const lastP = useRef(-1);

  const pStatic = historical && actualEnd != null ? Math.min(Math.max(actualEnd, 0), g.maxX) : Math.min(Math.max(fixed, 0), 1);

  // Live: one comparison per frame; attribute writes only when the head actually moved.
  useFrame(
    () => {
      if (!getter || w <= 0) return;
      const p = Math.min(Math.max(getter(), 0), 1);
      if (Math.abs(p - lastP.current) < 0.0004) return;
      lastP.current = p;
      const xp = g.xOf(p);
      elapsedRef.current?.setAttribute("x2", String(Math.max(g.xOf(0), xp)));
      elapsedRef.current?.setAttribute("opacity", xp > g.xOf(0) ? "1" : "0");
      dropRef.current?.setAttribute("transform", `translate(${xp.toFixed(2)} ${g.lineY})`);
      haloRef.current?.setAttribute("cx", xp.toFixed(2));
      dropPath.current?.setAttribute("class", `${p >= 1 ? v.dropDone : v.drop} ${v[`j_${style}`] ?? ""}`);
      g.shown.forEach((x, i) => {
        const el = litRefs.current[i];
        if (el) el.setAttribute("opacity", String(Math.min(Math.max((p - x.f) / 0.015, 0), 1)));
      });
    },
    getter != null && w > 0,
  );

  const p0 = getter ? Math.min(Math.max(getter(), 0), 1) : pStatic;
  const x0 = g.xOf(0);
  const xp = g.xOf(p0);
  const xPlanEnd = g.xOf(1);
  const xMax = g.xOf(g.maxX);

  const cls = (base: string) => `${base} ${v[`j_${style}`] ?? ""}`;

  return (
    <div ref={box} className={v.journey} style={{ height }} role={label ? "img" : undefined} aria-label={label} aria-hidden={label ? undefined : true}>
      {w > 0 && (
        <svg width={w} height={height} className={v.journeySvg} focusable="false" aria-hidden="true">
          <defs>
            <mask id={maskId} maskUnits="userSpaceOnUse" x={0} y={0} width={w} height={height}>
              <rect x={0} y={0} width={w} height={height} fill="white" />
              {g.shown.map((x, i) => (
                <circle key={i} cx={g.xOf(x.f)} cy={g.lineY} r={x.n.kind === "actual" ? g.capR + g.ringW : g.nodeR} fill="black" />
              ))}
              {historical && actualEnd != null && <circle cx={xp} cy={g.lineY} r={g.capR + g.ringW} fill="black" />}
              {!historical && <circle ref={haloRef} cx={xp} cy={g.lineY} r={g.haloR} fill="black" />}
            </mask>
          </defs>
          <g mask={`url(#${maskId})`} strokeLinecap="round" strokeWidth={g.strokeW}>
            {historical && actualEnd != null ? (
              actualEnd < 1 && <line className={v.dash} x1={xp} y1={g.lineY} x2={xPlanEnd} y2={g.lineY} strokeDasharray="4 4" />
            ) : (
              <line className={cls(v.track!)} x1={x0} y1={g.lineY} x2={xMax} y2={g.lineY} />
            )}
            <line ref={elapsedRef} className={cls(v.elapsed!)} x1={x0} y1={g.lineY} x2={Math.max(x0, xp)} y2={g.lineY} opacity={xp > x0 ? 1 : 0} />
          </g>
          {g.shown.map((x, i) => {
            const cx = g.xOf(x.f);
            if (x.n.kind === "actual") {
              return <circle key={i} cx={cx} cy={g.lineY} r={g.capR} style={{ fill: statusColorVar(x.n.status ?? "good") }} />;
            }
            const lit = Math.min(Math.max((p0 - x.f) / 0.015, 0), 1);
            return (
              <g key={i}>
                <circle className={cls(v.nodeRing!)} cx={cx} cy={g.lineY} r={g.nodeR - g.ringW / 2} strokeWidth={g.ringW} fill="none" />
                <circle
                  ref={(el) => {
                    litRefs.current[i] = el;
                  }}
                  className={cls(v.nodeLit!)}
                  cx={cx}
                  cy={g.lineY}
                  r={g.nodeR}
                  opacity={lit}
                />
              </g>
            );
          })}
          {historical && (
            <>
              <line className={v.tick} x1={xPlanEnd} y1={g.lineY - 5} x2={xPlanEnd} y2={g.lineY + 5} strokeWidth={1} />
              {actualEnd != null && <circle cx={xp} cy={g.lineY} r={g.capR} style={{ fill: statusColorVar(endStatus ?? "neutral") }} />}
            </>
          )}
          {!historical && (
            <g ref={dropRef} transform={`translate(${xp.toFixed(2)} ${g.lineY})`}>
              <path ref={dropPath} className={`${p0 >= 1 ? v.dropDone : v.drop} ${v[`j_${style}`] ?? ""}`} d={g.droplet} />
            </g>
          )}
        </svg>
      )}
      {labels.start && (
        <span className={`num-small ${v.endpoint}`} style={{ left: 0, top: Math.max(0, g.lineY - (g.dropH / 2 + 4) - 20), color: labels.start.color }}>
          {labels.start.text}
        </span>
      )}
      {labels.end && (
        <span className={`num-small ${v.endpoint}`} style={{ left: labels.end.left, top: Math.max(0, g.lineY - (g.dropH / 2 + 4) - 20), color: labels.end.color }}>
          {labels.end.text}
        </span>
      )}
      {labels.below.map((l) => (
        <span key={`${l.text}@${l.left}`} className={`micro ${v.nodeLabel}`} style={{ left: l.left, top: g.lineY + Math.max(g.nodeR, g.dropH / 2) + 3, color: l.color }}>
          {l.text}
        </span>
      ))}
      {historical && actualLabel == null && overrunLabel && (actualEnd ?? 0) > 1 && w > 0 && (
        <span className={`micro ${v.nodeLabel}`} style={{ left: Math.min(Math.max(0, xp - microWidth(overrunLabel) / 2), Math.max(0, w - microWidth(overrunLabel))), top: g.lineY + g.capR + 5, color: "var(--attention)" }}>
          {overrunLabel}
        </span>
      )}
    </div>
  );
});

// --------------------------------------------------------------------------- route

const NUMERAL_PX: Record<NumeralStyle, number> = {
  "num-clock": 88, "num-clock-compact": 72, "num-display": 64, "num-large": 40, "num-medium": 26, "num-small": 20, "num-inline": 15,
};
const STEP_DOWN: Partial<Record<NumeralStyle, NumeralStyle>> = { "num-large": "num-medium", "num-medium": "num-small" };

/** Mirrors Kotlin RouteRow's parameters. `fromTime`/`toTime` are the stub's names for from/to. */
export interface RouteRowProps {
  mode: "planned" | "done";
  from?: string;
  fromUnit?: string;
  fromLabel: string;
  to?: string;
  toUnit?: string;
  toLabel: string;
  fromTime?: string;
  toTime?: string;
  /** Pour starts as fractions of the total TIME (pourNodes). */
  nodes?: readonly number[];
  midLabel?: string | null;
  /** "RATIO" by default; null when the mid label already says what it is. */
  midCaption?: string | null;
  numeral?: NumeralStyle;
}

/**
 * "30 g ─○─○─ 500 g" with the ratio under the line. The nodes are pour starts in TIME, so the mid
 * label carries a RATIO caption, or "1:16.7" under a time axis reads as 1 min 16.7 s. If both
 * numbers will not fit at `numeral`, it steps down once (decided per input and width).
 */
export const RouteRow = memo(function RouteRow(p: RouteRowProps) {
  const box = useRef<HTMLDivElement>(null);
  const w = useWidth(box);
  const from = p.from ?? p.fromTime ?? "";
  const to = p.to ?? p.toTime ?? "";
  const style = p.numeral ?? "num-large";
  const fitted = useMemo(() => {
    if (w <= 0) return style;
    const px = NUMERAL_PX[style];
    const unitPx = Math.max(13, px * 0.42);
    const numbers = (from.length + to.length) * px * 0.58 + ((p.fromUnit ?? "").length + (p.toUnit ?? "").length) * unitPx * 0.55 + 8;
    return numbers + 102 <= w ? style : (STEP_DOWN[style] ?? style);
  }, [w, style, from, to, p.fromUnit, p.toUnit]);
  const journey = useMemo(() => (p.nodes ?? []).map((f) => ({ fraction: f })), [p.nodes]);
  const caption = p.midCaption === undefined ? "RATIO" : p.midCaption;
  return (
    <div ref={box} className={v.route}>
      <div className={v.routeEnd}>
        <NumberWithUnit value={from} unit={p.fromUnit} numeral={fitted} />
        <span className="micro ink-low">{toCaps(p.fromLabel)}</span>
      </div>
      <div className={v.routeMid} data-centered={p.midLabel == null}>
        <JourneyLine nodes={journey} style="compact" progress={p.mode === "done" ? 1 : 0} />
        {p.midLabel != null && (
          <span className={v.routeMidLabel}>
            {caption != null && <span className="micro ink-low">{caption}</span>}
            <span className={`t-title-s ink-mid ${v.routeMidValue}`}>{p.midLabel}</span>
          </span>
        )}
      </div>
      <div className={`${v.routeEnd} ${v.routeEndRight}`}>
        <NumberWithUnit value={to} unit={p.toUnit} numeral={fitted} />
        <span className="micro ink-low">{toCaps(p.toLabel)}</span>
      </div>
    </div>
  );
});
