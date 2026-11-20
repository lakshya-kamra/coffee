/**
 * Ports of CoffeeArt.kt's CoffeeMug, Steam, Dripper and TumblingBeans as SVG. Everything that
 * moves is a CSS keyframe or transition on `transform`/`opacity` (rule 10): the steam rising,
 * the crema ripple, the drips and the beans. The shell pauses keyframes in an inactive tab, and
 * reduced motion turns them off (base.css).
 *
 * Each moving part is its own box (an <svg> or <span> of its own), never a shape inside an
 * <svg>. Browsers hand a transform animation to the compositor only for a box; on an inner SVG
 * shape the same keyframe restyles, lays out and repaints on the main thread every frame (seen
 * in a trace: one style + layout pass per frame for as long as the Today mug was on screen).
 * Distances are percentages of the box, which equal the old viewBox units.
 */
import { memo, useId, type CSSProperties } from "react";
import s from "./art.module.css";

// ------------------------------------------------------------------------ steam

/** One wisp: a gentle S rising from the bottom edge, in a 100-unit box. */
const WISP = "M0 100C-7 84 7 70 0 54C-7 38 7 24 0 8";

/**
 * Rising steam: three wisps that drift up and fade, staggered. Fills its box; size the box to
 * the space above the rim. `strength` 0..1 scales the whole plume's opacity.
 */
export const Steam = memo(function Steam({ strength = 1, className }: { strength?: number; className?: string }) {
  if (strength <= 0.01) return null;
  return (
    <span className={`${s.steam} ${className ?? ""}`} aria-hidden="true" style={{ opacity: Math.min(1, strength) }}>
      {[-24, 0, 24].map((dx, i) => (
        <svg key={dx} className={s.wisp} data-loop="" viewBox="0 0 100 100" preserveAspectRatio="none" focusable="false" style={{ animationDelay: `${-i * 1.2}s` }}>
          <path d={WISP} transform={`translate(${50 + dx} 0)`} vectorEffect="non-scaling-stroke" />
        </svg>
      ))}
    </span>
  );
});

// -------------------------------------------------------------------------- mug

const TOP = 38;
const BOTTOM = 94;
const HALF_TOP = 30;
const HALF_BOTTOM = 24;
const CX = 46;
const CUP = `M${CX - HALF_TOP} ${TOP}L${CX + HALF_TOP} ${TOP}L${CX + HALF_BOTTOM} ${BOTTOM}C${CX + HALF_BOTTOM} ${BOTTOM + 3} ${CX - HALF_BOTTOM} ${BOTTOM + 3} ${CX - HALF_BOTTOM} ${BOTTOM}Z`;
// Compose drawArc(-105°, sweep 210°) in the box (64, 46.96, 22 × 29.12): an ellipse arc swept
// past vertical, so both ends tuck under the tapering wall instead of floating beside it.
const HANDLE = "M72.15 47.46A11 14.56 0 1 1 72.15 75.58";
/** Wavelength of Android's sin(x / w · 6.5): the ripple loops seamlessly over one of these. */
const WAVE_LEN = (2 * Math.PI * 100) / 6.5;
const RIPPLE = (() => {
  // Drawn at the full-mug level; a transform lowers it to the fill. Spans one extra wavelength
  // on the left so translating by one wavelength loops without a seam.
  const y0 = BOTTOM - (BOTTOM - TOP) * 0.9;
  let d = "";
  for (let x = -WAVE_LEN; x <= 100 + 4; x += 100 / 24) {
    const y = y0 + Math.sin((x / 100) * 6.5) * 0.8;
    d += `${d ? "L" : "M"}${x.toFixed(2)} ${y.toFixed(2)}`;
  }
  return `${d}L${104} ${y0 + 3}L${-WAVE_LEN} ${y0 + 3}Z`;
})();
const FULL_DROP = (BOTTOM - TOP) * 0.9;
/**
 * The inside of the cup as a CSS polygon in percentages (the box is the 100-unit viewBox): the
 * rim, the two walls, and the shallow curve of the base sampled from CUP's cubic. It clips the
 * liquid, which is a box of its own so that its level and ripple can move on the compositor.
 */
const CUP_CLIP = (() => {
  const pts: string[] = [`${CX - HALF_TOP}% ${TOP}%`, `${CX + HALF_TOP}% ${TOP}%`];
  for (let i = 0; i <= 8; i++) {
    const t = i / 8;
    const u = 1 - t;
    const x = (CX + HALF_BOTTOM) * (u * u * u + 3 * u * u * t) + (CX - HALF_BOTTOM) * (3 * u * t * t + t * t * t);
    const y = BOTTOM + 9 * t * u;
    pts.push(`${x.toFixed(2)}% ${y.toFixed(2)}%`);
  }
  return `polygon(${pts.join(", ")})`;
})();

/**
 * A mug that fills up. `fill` 0..1 eases to its new level with a CSS transition on transform
 * (the liquid is drawn full and translated down), so a caller can drive it from progress.
 */
export const CoffeeMug = memo(function CoffeeMug({
  fill,
  size = 120,
  showSteam = true,
  body = "var(--art-shell)",
  liquid = "var(--art-bed)",
  crema = "var(--art-water)",
  steamStrength,
  className,
  label,
}: {
  fill: number;
  /** Width and height in px; pass 0 to fill the parent. */
  size?: number;
  showSteam?: boolean;
  body?: string;
  liquid?: string;
  crema?: string;
  /** Defaults to the fill, with a sliver even when nearly empty so it never looks switched off. */
  steamStrength?: number;
  className?: string;
  label?: string;
}) {
  const lv = Math.max(0, Math.min(1, fill));
  const drop = FULL_DROP * (1 - lv);
  const box: CSSProperties = size > 0 ? { width: size, height: size } : { width: "100%", height: "100%" };
  return (
    <span className={`${s.mug} ${className ?? ""}`} style={box} role={label ? "img" : undefined} aria-label={label} aria-hidden={label ? undefined : true}>
      {showSteam && (
        <span className={s.mugSteam}>
          <Steam strength={steamStrength ?? Math.max(lv, 0.35)} />
        </span>
      )}
      <svg viewBox="0 0 100 100" width="100%" height="100%" focusable="false" aria-hidden="true">
        <path d={HANDLE} fill="none" style={{ stroke: body }} strokeWidth={4.5} strokeLinecap="round" />
        <path d={CUP} style={{ fill: body }} />
        {/* The liquid tops out below the rim, so the highlight can sit under the liquid's box. */}
        <path d={`M${CX - HALF_TOP} ${TOP}L${CX + HALF_TOP} ${TOP}`} stroke="white" strokeOpacity={0.35} strokeWidth={1.8} strokeLinecap="round" />
      </svg>
      {lv > 0.01 && (
        <span className={s.liquid} style={{ clipPath: CUP_CLIP }}>
          <span className={s.mugLevel} style={{ transform: `translateY(${drop.toFixed(2)}%)` }}>
            <span className={s.liquidBody} style={{ top: `${BOTTOM - FULL_DROP}%`, background: liquid }} />
            <svg className={s.ripple} data-loop="" viewBox="0 0 100 100" focusable="false">
              <path d={RIPPLE} style={{ fill: crema }} fillOpacity={0.85} />
            </svg>
          </span>
        </span>
      )}
    </span>
  );
});

/** A document-unique id for a clip path: useId with the characters url(#…) would need escaped removed. */
function useStableId(): string {
  return `art-clip-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
}

// ---------------------------------------------------------------------- dripper

const CONE_TOP = 10;
const CONE_BOTTOM = 66;
const D_HALF_TOP = 34;
const SPOUT = 4.5;
const DCX = 50;
const CONE = `M${DCX - D_HALF_TOP} ${CONE_TOP}L${DCX + D_HALF_TOP} ${CONE_TOP}L${DCX + SPOUT} ${CONE_BOTTOM}L${DCX - SPOUT} ${CONE_BOTTOM}Z`;
const RIBS = Array.from({ length: 7 }, (_, i) => {
  const p = (i + 0.5) / 7;
  const xt = DCX - D_HALF_TOP + D_HALF_TOP * 2 * p;
  const xb = DCX - SPOUT + SPOUT * 2 * p;
  return `M${xt.toFixed(2)} ${CONE_TOP}L${xb.toFixed(2)} ${CONE_BOTTOM}`;
}).join("");

/** A cone with a bed in it, water above the bed, and drips falling out of the bottom. */
export const Dripper = memo(function Dripper({
  waterLevel = 0.55,
  bedDepth = 0.22,
  dripping = true,
  size = 140,
  shell = "var(--art-shell)",
  water = "var(--art-water)",
  bed = "var(--art-bed)",
  className,
}: {
  waterLevel?: number;
  bedDepth?: number;
  dripping?: boolean;
  /** px; 0 fills the parent. */
  size?: number;
  shell?: string;
  water?: string;
  bed?: string;
  className?: string;
}) {
  const clipId = useStableId();
  const lv = Math.max(0, Math.min(1, waterLevel));
  const bedTop = CONE_BOTTOM - (CONE_BOTTOM - CONE_TOP) * bedDepth;
  const full = bedTop - CONE_TOP;
  const box: CSSProperties = size > 0 ? { width: size, height: size } : { width: "100%", height: "100%" };
  return (
    <span className={`${s.dripper} ${className ?? ""}`} style={box} aria-hidden="true">
      <svg viewBox="0 0 100 100" width="100%" height="100%" focusable="false">
        <defs>
          <clipPath id={clipId}>
            <path d={CONE} />
          </clipPath>
        </defs>
        <path d={CONE} style={{ fill: shell }} />
        <g clipPath={`url(#${clipId})`}>
          {lv > 0.01 && (
            <rect className={s.level} x={0} y={CONE_TOP} width={100} height={full} style={{ fill: water, transform: `translateY(${(full * (1 - lv)).toFixed(2)}px)` }} fillOpacity={0.92} />
          )}
          <rect x={0} y={bedTop} width={100} height={CONE_BOTTOM - bedTop} style={{ fill: bed }} />
        </g>
        <path d={RIBS} stroke="black" strokeOpacity={0.1} strokeWidth={1.2} />
        <path d={`M${DCX - D_HALF_TOP} ${CONE_TOP}L${DCX + D_HALF_TOP} ${CONE_TOP}`} stroke="white" strokeOpacity={0.3} strokeWidth={2} strokeLinecap="round" />
        <path d={`M${DCX - 28} 97L${DCX + 28} 97`} style={{ stroke: shell }} strokeOpacity={0.55} strokeWidth={3} strokeLinecap="round" />
      </svg>
      {dripping &&
        [0, 1, 2].map((i) => (
          <svg key={i} className={s.drip} data-loop="" viewBox="0 0 100 100" focusable="false" style={{ animationDelay: `${(-i * 0.9) / 3}s` }}>
            <circle cx={DCX} cy={CONE_BOTTOM} r={1.8} style={{ fill: water }} />
          </svg>
        ))}
    </span>
  );
});

// ------------------------------------------------------------------------ beans

const BEAN = (
  <>
    <ellipse cx={0} cy={0} rx={7.92} ry={5.7} />
    <path d="M-5.94 0C-1.98 -3.33 1.98 3.33 5.94 0" fill="none" stroke="black" strokeOpacity={0.28} strokeWidth={1.43} strokeLinecap="round" />
  </>
);

/** Three beans tumbling: the app's loading state. */
export const TumblingBeans = memo(function TumblingBeans({ size = 72, label = "Loading" }: { size?: number; label?: string }) {
  return (
    <span className={s.beans} style={{ width: size, height: size }} role="img" aria-label={label}>
      {[0, 1, 2].map((i) => (
        <svg key={i} className={s.bean} data-loop="" viewBox="0 0 72 72" focusable="false" aria-hidden="true" style={{ animationDelay: `${(-i * 1.5) / 3}s` }}>
          {BEAN}
        </svg>
      ))}
    </span>
  );
});
