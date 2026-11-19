/**
 * Port of BrewRing.kt: the brew timer dial, drawn on a <canvas> from the engine's clock in the
 * shared frame loop. The arc is elapsed time against the whole recipe, with a tick at every step
 * boundary; the inner disc is a liquid level showing water poured against the total. Paths,
 * gradients and colours are built once per size or colour change (Compose's drawWithCache rule);
 * a frame allocates nothing but the wave path's points. No React render happens per frame.
 */
import { animate, type MotionValue } from "motion";
import { memo, useEffect, useRef, type ReactNode } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { heavy } from "@/design/motion";
import { addFrame } from "./frameLoop";
import { observeSize, onThemeChange, resolveColor } from "./measure";
import v from "./viz.module.css";

/** What the ring reads from the brew engine every frame (WEB-2's BrewEngine implements it). */
export interface BrewEngineView {
  elapsedMs(): number;
  /** The planned total; the arc is elapsed / total. Defaults to one minute when absent. */
  totalMs?(): number;
  /** Water poured at `elapsedMs` as a fraction of the total (unrounded waterAtF / waterG). */
  waterFractionAt?(elapsedMs: number): number;
}

interface Colours {
  accent: string;
  track: string;
  liquid: string;
  tick: string;
  core: string;
}

export const BrewRing = memo(function BrewRing({
  engine,
  accent,
  boundaries,
  seek,
  pouring = false,
  running = true,
  trackColor = "var(--ink-faint)",
  liquidColor = "var(--ring-liquid)",
  tickColor = "var(--ink-low)",
  headCore = "var(--bg)",
  strokeFraction = 0.055,
  minLiquidLevel = 0.12,
  glowRange = [0.15, 0.4],
  children,
  label,
}: {
  engine: BrewEngineView;
  /** A CSS colour, custom properties allowed ("var(--crema-stroke)"). */
  accent: string;
  /** Fractions of the total where each step ends (stepBoundaries). */
  boundaries: readonly number[];
  /** How far the ring is drawn ahead of or behind the clock: a seek snaps the digits but glides the arc. */
  seek?: MotionValue<number>;
  pouring?: boolean;
  running?: boolean;
  trackColor?: string;
  liquidColor?: string;
  tickColor?: string;
  headCore?: string;
  strokeFraction?: number;
  minLiquidLevel?: number;
  glowRange?: readonly [number, number];
  children?: ReactNode;
  label?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  // Everything the draw loop reads, kept in refs so prop changes never restart the loop.
  const props = useRef({ engine, boundaries, seek, minLiquidLevel, strokeFraction, glowRange });
  const colourSpec = useRef({ accent, trackColor, liquidColor, tickColor, headCore });
  const pulseAmt = useRef<{ value: number }>({ value: 0 });
  const recolour = useRef<() => void>(() => undefined);

  useEffect(() => {
    props.current = { engine, boundaries, seek, minLiquidLevel, strokeFraction, glowRange };
  });
  useEffect(() => {
    colourSpec.current = { accent, trackColor, liquidColor, tickColor, headCore };
    recolour.current();
  }, [accent, trackColor, liquidColor, tickColor, headCore]);

  // How much of the pulse the head shows springs in and out instead of switching at pour edges.
  useEffect(() => {
    const target = pouring && running && !prefersReducedMotion() ? 1 : 0;
    const holder = pulseAmt.current;
    const a = animate(holder.value, target, { ...heavy, onUpdate: (x: number) => (holder.value = x) });
    return () => a.stop();
  }, [pouring, running]);

  useEffect(() => {
    const el = box.current;
    const cv = canvas.current;
    if (!el || !cv) return;
    const ctx = cv.getContext("2d");
    if (!ctx) return;
    let w = 0;
    let h = 0;
    let dpr = 1;
    let visible = true;
    let colours: Colours = { accent: "#000", track: "#000", liquid: "#000", tick: "#000", core: "#000" };
    let glow: CanvasGradient | null = null;
    let geo = { size: 0, stroke: 0, radius: 0, cx: 0, cy: 0, innerR: 0, amp: 0, glowR: 0, headR: 0, coreR: 0, tickIn: 0, tickOut: 0, tickW: 0 };
    let discPath = new Path2D();

    const rebuild = () => {
      const size = Math.min(w, h);
      const stroke = size * props.current.strokeFraction;
      const radius = size / 2 - stroke * 1.2;
      const innerR = Math.max(0, radius - stroke * 1.9);
      geo = {
        size,
        stroke,
        radius,
        cx: w / 2,
        cy: h / 2,
        innerR,
        amp: innerR * 0.035,
        glowR: stroke * 3.2,
        headR: stroke * 0.9,
        coreR: stroke * 0.35,
        // A thin ring needs ticks longer than its stroke to be seen at all.
        tickIn: radius - stroke * 1.1,
        tickOut: radius + stroke * 1.1,
        tickW: Math.max(stroke * 0.22, 1.5),
      };
      discPath = new Path2D();
      discPath.arc(geo.cx, geo.cy, innerR, 0, Math.PI * 2);
      glow = ctx.createRadialGradient(0, 0, 0, 0, 0, Math.max(1, geo.glowR));
      glow.addColorStop(0, colours.accent);
      glow.addColorStop(1, "rgba(0,0,0,0)");
    };

    const resolve = () => {
      const c = colourSpec.current;
      colours = {
        accent: resolveColor(el, c.accent),
        track: resolveColor(el, c.trackColor),
        liquid: resolveColor(el, c.liquidColor),
        tick: resolveColor(el, c.tickColor),
        core: resolveColor(el, c.headCore),
      };
      rebuild();
    };
    recolour.current = resolve;

    const stopSize = observeSize(el, (width, height) => {
      w = width;
      h = height;
      dpr = Math.min(3, window.devicePixelRatio || 1);
      cv.width = Math.max(1, Math.round(w * dpr));
      cv.height = Math.max(1, Math.round(h * dpr));
      resolve();
    });
    const stopTheme = onThemeChange(resolve);
    const io =
      typeof IntersectionObserver === "undefined"
        ? null
        : new IntersectionObserver(([e]) => {
            visible = e?.isIntersecting ?? true;
          });
    io?.observe(el);

    const draw = (now: number) => {
      if (!visible || w <= 0 || h <= 0) return;
      const { engine: eng, boundaries: ticks, seek: sk, minLiquidLevel: minL, glowRange: gr } = props.current;
      const reduced = prefersReducedMotion();
      const total = Math.max(1, eng.totalMs?.() ?? 60_000);
      const ms = eng.elapsedMs() + (sk?.get() ?? 0);
      const p = Math.min(1, Math.max(0, ms / total));
      const wp = Math.min(1, Math.max(0, eng.waterFractionAt?.(ms) ?? 0));
      const wv = reduced ? 0 : ((now % 3000) / 3000) * Math.PI * 2;
      // Sine easing has zero velocity at both turnarounds, so the pulse never kinks (0.92..1.08, 1.1 s a leg).
      const pulse = 1 - 0.08 * Math.cos((Math.PI * now) / 1100);
      const amt = pulseAmt.current.value;
      const g = geo;

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);

      // Inner liquid disc.
      if (wp > 0.005 && g.innerR > 0) {
        const level = minL + (1 - minL) * wp;
        const surfaceY = g.cy + g.innerR - g.innerR * 2 * level;
        const amp = reduced ? 0 : g.amp;
        ctx.save();
        ctx.clip(discPath);
        ctx.beginPath();
        ctx.moveTo(g.cx - g.innerR, surfaceY);
        for (let x = g.cx - g.innerR; x <= g.cx + g.innerR; x += g.innerR / 14) {
          ctx.lineTo(x, surfaceY + Math.sin((x / g.innerR) * 3.4 + wv) * amp);
        }
        ctx.lineTo(g.cx + g.innerR, g.cy + g.innerR);
        ctx.lineTo(g.cx - g.innerR, g.cy + g.innerR);
        ctx.closePath();
        // Fades in over the first few grams instead of popping on at the minimum level.
        ctx.globalAlpha = Math.min(1, wp / 0.06);
        ctx.fillStyle = colours.liquid;
        ctx.fill();
        ctx.restore();
        ctx.globalAlpha = 1;
      }

      // Track.
      ctx.lineCap = "round";
      ctx.lineWidth = g.stroke;
      ctx.strokeStyle = colours.track;
      ctx.beginPath();
      ctx.arc(g.cx, g.cy, g.radius, 0, Math.PI * 2);
      ctx.stroke();

      // Step ticks cross-fade to the accent over about five degrees as the head passes.
      ctx.lineWidth = g.tickW;
      for (const frac of ticks) {
        const rad = ((-90 + 360 * frac) * Math.PI) / 180;
        const cs = Math.cos(rad);
        const sn = Math.sin(rad);
        const lit = Math.min(1, Math.max(0, (p - frac) / 0.015));
        ctx.beginPath();
        ctx.moveTo(g.cx + cs * g.tickIn, g.cy + sn * g.tickIn);
        ctx.lineTo(g.cx + cs * g.tickOut, g.cy + sn * g.tickOut);
        if (lit < 1) {
          ctx.globalAlpha = 1 - lit;
          ctx.strokeStyle = colours.tick;
          ctx.stroke();
        }
        if (lit > 0) {
          ctx.globalAlpha = lit;
          ctx.strokeStyle = colours.accent;
          ctx.stroke();
        }
      }
      ctx.globalAlpha = 1;

      // Progress and the leading head, with a glow that swells while a pour is live.
      if (p > 0.001) {
        ctx.lineWidth = g.stroke;
        ctx.strokeStyle = colours.accent;
        ctx.beginPath();
        ctx.arc(g.cx, g.cy, g.radius, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * p);
        ctx.stroke();
        const ha = -Math.PI / 2 + Math.PI * 2 * p;
        const hx = g.cx + Math.cos(ha) * g.radius;
        const hy = g.cy + Math.sin(ha) * g.radius;
        const hs = 1 + (pulse - 1) * amt;
        if (glow) {
          ctx.save();
          ctx.translate(hx, hy);
          ctx.scale(hs, hs);
          ctx.globalAlpha = gr[0] + (gr[1] - gr[0]) * amt;
          ctx.fillStyle = glow;
          ctx.beginPath();
          ctx.arc(0, 0, g.glowR, 0, Math.PI * 2);
          ctx.fill();
          ctx.restore();
          ctx.globalAlpha = 1;
        }
        ctx.fillStyle = colours.accent;
        ctx.beginPath();
        ctx.arc(hx, hy, g.headR * hs, 0, Math.PI * 2);
        ctx.fill();
        ctx.globalAlpha = 0.9;
        ctx.fillStyle = colours.core;
        ctx.beginPath();
        ctx.arc(hx, hy, g.coreR * hs, 0, Math.PI * 2);
        ctx.fill();
        ctx.globalAlpha = 1;
      }
    };

    const stopFrame = addFrame(draw);
    return () => {
      stopFrame();
      stopSize();
      stopTheme();
      io?.disconnect();
      recolour.current = () => undefined;
    };
  }, []);

  return (
    <div ref={box} className={v.ring} role={label ? "img" : undefined} aria-label={label}>
      <canvas ref={canvas} className={v.ringCanvas} aria-hidden="true" />
      {children && <div className={v.ringContent}>{children}</div>}
    </div>
  );
});
