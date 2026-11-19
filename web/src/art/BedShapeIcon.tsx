/**
 * Port of CoffeeArt.kt BedShapeIcon: the spent bed inside a V60 cone cross-section, one drawing
 * per BedShape. The one bed icon in the app (Review, log rows, Brew detail), so a bed looks the
 * same on every screen. Below 20 px the rim flare and spout are dropped and the bed is filled
 * more densely: at row size a flared rim over a thin bed line reads as a martini glass.
 */
import { memo, type ReactNode } from "react";
import { bedShapeLabel, type BedShape } from "@/domain/core/evaluation";

const TOP = 14;
const APEX = 86;
const LEFT = 12;
const RIGHT = 88;
const CX = 50;
const DEPTH = APEX - TOP;
const FLAT_Y = TOP + DEPTH * 0.45;

/** Inner wall x at height y: the cone narrows in a straight line to the apex. */
const xl = (y: number) => LEFT + (CX - LEFT) * ((y - TOP) / DEPTH);
const xr = (y: number) => RIGHT - (RIGHT - CX) * ((y - TOP) / DEPTH);
const f = (n: number) => Math.round(n * 100) / 100;

/** The top of the bed, wall to wall. */
function surface(shape: BedShape): string {
  switch (shape) {
    case "domed":
    case "crater": {
      const ctrl = shape === "domed" ? FLAT_Y - DEPTH * 0.3 : FLAT_Y + DEPTH * 0.36;
      return `M${f(xl(FLAT_Y))} ${f(FLAT_Y)}Q${CX} ${f(ctrl)} ${f(xr(FLAT_Y))} ${f(FLAT_Y)}`;
    }
    case "sloped": {
      const yl = TOP + DEPTH * 0.3;
      const yr = TOP + DEPTH * 0.58;
      return `M${f(xl(yl))} ${f(yl)}L${f(xr(yr))} ${f(yr)}`;
    }
    case "high-and-dry": {
      const low = TOP + DEPTH * 0.58;
      return `M${f(xl(low))} ${f(low)}L${f(xr(low))} ${f(low)}`;
    }
    default:
      return `M${f(xl(FLAT_Y))} ${f(FLAT_Y)}L${f(xr(FLAT_Y))} ${f(FLAT_Y)}`;
  }
}

export const BedShapeIcon = memo(function BedShapeIcon({
  shape,
  size = 16,
  tint = "var(--ink-mid)",
  cut = "var(--surface1)",
  wall = "var(--ink-low)",
  decorative = false,
}: {
  shape: BedShape | null;
  size?: number;
  /** Colours the bed (CSS colour). */
  tint?: string;
  /** Pits and layers are cut in this colour; match whatever the icon sits on. */
  cut?: string;
  wall?: string;
  decorative?: boolean;
}) {
  const small = size < 20;
  // At least one CSS pixel, as Kotlin's maxOf(1.dp, w * 0.045).
  const stroke = Math.max(100 / size, 4.5);
  const cone = small
    ? `M${LEFT} ${TOP}L${CX} ${APEX}L${RIGHT} ${TOP}`
    : `M${LEFT - 7} ${TOP}L${LEFT} ${TOP}L${CX} ${APEX}L${RIGHT} ${TOP}L${RIGHT + 7} ${TOP}M${CX} ${APEX}L${CX} ${APEX + 7}`;
  const fillAlpha = shape === "muddy" ? 0.78 : small ? 0.7 : 0.32;
  const marks: ReactNode[] = [];
  if (shape === "high-and-dry") {
    const low = TOP + DEPTH * 0.58;
    const high = TOP + DEPTH * 0.16;
    const inset = stroke * 1.2;
    marks.push(
      <path key="s" d={`M${f(xl(high) + inset)} ${f(high)}L${f(xl(low) + inset)} ${f(low)}M${f(xr(high) - inset)} ${f(high)}L${f(xr(low) - inset)} ${f(low)}`} style={{ stroke: tint }} strokeWidth={stroke * 2} strokeLinecap="round" fill="none" />,
    );
  } else if (shape === "sinkhole") {
    const pitR = stroke * 1.5;
    const pitDepth = DEPTH * 0.18;
    for (const [i, x] of [CX - 11, CX + 9].entries()) {
      marks.push(
        <g key={`p${i}`} style={{ fill: cut, stroke: cut }}>
          <circle cx={x} cy={f(FLAT_Y)} r={f(pitR)} strokeWidth={0} />
          <path d={`M${x} ${f(FLAT_Y)}L${x} ${f(FLAT_Y + pitDepth)}`} strokeWidth={stroke} strokeLinecap="round" />
        </g>,
      );
    }
  } else if (shape === "muddy") {
    const y1 = FLAT_Y + DEPTH * 0.14;
    const y2 = FLAT_Y + DEPTH * 0.28;
    marks.push(
      <path key="l" d={`M${f(xl(y1) + stroke)} ${f(y1)}L${f(xr(y1) - stroke)} ${f(y1)}M${f(xl(y2) + stroke)} ${f(y2)}L${f(xr(y2) - stroke)} ${f(y2)}`} style={{ stroke: cut }} strokeWidth={stroke * 0.8} strokeLinecap="round" fill="none" />,
    );
  }
  const top = shape ? surface(shape) : "";
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      role={decorative ? undefined : "img"}
      aria-label={decorative ? undefined : bedShapeLabel(shape)}
      aria-hidden={decorative ? true : undefined}
      focusable="false"
      overflow="visible"
    >
      {shape && (
        <>
          <path d={`${top}L${CX} ${APEX}Z`} style={{ fill: tint }} fillOpacity={fillAlpha} />
          <path d={top} style={{ stroke: tint }} strokeWidth={stroke * 1.3} strokeLinecap="round" strokeLinejoin="round" fill="none" />
        </>
      )}
      {marks}
      <path d={cone} style={{ stroke: wall }} strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  );
});
