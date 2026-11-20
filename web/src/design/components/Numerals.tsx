/** Port of ui/components/Numerals.kt. Numerals are tabular everywhere (base.css). */
import { memo, useState, type CSSProperties } from "react";
import s from "./components.module.css";

export type NumeralStyle = "num-clock" | "num-clock-compact" | "num-display" | "num-large" | "num-medium" | "num-small" | "num-inline";

const NUMERAL_PX: Record<NumeralStyle, number> = {
  "num-clock": 88,
  "num-clock-compact": 72,
  "num-display": 64,
  "num-large": 40,
  "num-medium": 26,
  "num-small": 20,
  "num-inline": 15,
};

/** Num.unit: max(13, 0.42 × numeral) px, medium weight, no tracking. */
export function unitSizePx(style: NumeralStyle): number {
  return Math.max(13, NUMERAL_PX[style] * 0.42);
}

export const NumberWithUnit = memo(function NumberWithUnit({ value, unit, numeral = "num-large", color, unitColor }: { value: string; unit?: string; numeral?: NumeralStyle; color?: string; unitColor?: string }) {
  return (
    <span className={s.nwu}>
      <span className={numeral} style={color ? { color } : undefined}>
        {value}
      </span>
      {unit && (
        <span className="num-unit" style={{ fontSize: `${unitSizePx(numeral) / 16}rem`, color: unitColor ?? "var(--ink-mid)" }}>
          {unit}
        </span>
      )}
    </span>
  );
});

/**
 * Digits that changed roll in with a CSS keyframe on transform/opacity, keyed by value. No JS
 * per frame: React renders only when the value itself changes.
 */
export function TickingNumber({ value, numeral = "num-large", label }: { value: string; numeral?: NumeralStyle; label?: string }) {
  // "Storing information from previous renders" (React docs pattern), not a ref read in render.
  const [shown, setShown] = useState(value);
  const [old, setOld] = useState(value);
  if (value !== shown) {
    setOld(shown);
    setShown(value);
  }
  // Compare right-aligned so "9" → "10" rolls the tens and the units.
  const pad = Math.max(old.length, value.length);
  const a = old.padStart(pad, " ");
  const b = value.padStart(pad, " ");
  const chars = Array.from(b);
  const changed = chars.map((ch, i) => ch !== a[i]);
  return (
    <span className={`${s.tick} ${numeral}`}>
      {/* Screen readers get the whole value once; the per-digit spans are decoration. */}
      <span className="visually-hidden">{label ?? value}</span>
      {chars.map((ch, i) =>
        ch === " " ? null : (
          <span key={`${i}:${changed[i] ? value : "s"}`} className={s.tickDigit} data-changed={changed[i]} aria-hidden="true">
            {ch}
          </span>
        ),
      )}
    </span>
  );
}

export interface Cell {
  label: string;
  value: string | null;
  unit?: string;
  sub?: string | null;
}

export function DataGrid({ cells, columns = 3 }: { cells: readonly Cell[]; columns?: number }) {
  // Blank cells close a short last row: the hairline background otherwise shows as a grey block.
  const fill = (columns - (cells.length % columns)) % columns;
  return (
    <div className={s.grid} style={{ "--cols": columns } as CSSProperties}>
      {cells.map((c) => (
        <div key={c.label} className={s.cell}>
          <span className="eyebrow">{c.label}</span>
          {c.value != null ? <NumberWithUnit value={c.value} unit={c.unit} numeral="num-small" /> : <span className="num-small ink-low">—</span>}
          {c.sub && <span className={`t-body-s ${s.cellSub}`}>{c.sub}</span>}
        </div>
      ))}
      {Array.from({ length: fill }, (_, i) => (
        <div key={`fill-${i}`} className={s.cell} aria-hidden="true" />
      ))}
    </div>
  );
}
