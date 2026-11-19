/**
 * Shared grind widgets (A§7.5), used by the grinder editor, onboarding, brew setup and review.
 * Everything about what a number means comes from GrindScale (domain/grind): the stored number
 * is total clicks from zero on click grinders (a K-Ultra's dial 7.5 is stored as 75) and the
 * printed dial number on the others. Ranges and their sources come from grinders.json only.
 */
import { memo, useId, useMemo, useState } from "react";
import type { GrindRange } from "@/catalog/types";
import { useCatalogGrinder } from "@/catalog/useCatalog";
import { EyebrowLabel, PillButton, SoftCard, StatusChip } from "@/design/components";
import { BREW_METHODS, brewMethodLabel, type BrewMethod } from "@/domain/core/brewer";
import type { Status } from "@/domain/core/status";
import { format, fromReading, grindScaleOf, reading, showsDialNumber, usesClicks, type DialReading, type GrinderScaleInput, type GrindScale } from "@/domain/grind";
import s from "./grind.module.css";

// ---------------------------------------------------------------- the scale, on screen

/**
 * A grinder row's scale for rendering. grinders.json loads after first paint, and a row picked
 * from the catalog keeps its dial format and ranges there, so a plain `scaleFor(g)` read during
 * render shows "0 turns + 75" for a K-Ultra until something else re-renders. This subscribes:
 * the scale is rebuilt the moment the catalog entry arrives.
 */
export function useGrindScale(g: GrinderScaleInput): GrindScale;
export function useGrindScale(g: GrinderScaleInput | null): GrindScale | null;
export function useGrindScale(g: GrinderScaleInput | null): GrindScale | null {
  const entry = useCatalogGrinder(g?.catalogId);
  return useMemo(() => (g ? grindScaleOf(g, entry) : null), [g, entry]);
}

// ---------------------------------------------------------------- confidence words (A§7.3)

/** high: "From the maker"; medium: "Check your manual"; low: "Unverified". Never colour alone. */
export function confidenceChip(confidence: string | null): { status: Status; text: string } | null {
  switch (confidence) {
    case "high":
      return { status: "good", text: "From the maker" };
    case "medium":
      return { status: "attention", text: "Check your manual" };
    case "low":
      return { status: "attention", text: "Unverified" };
    default:
      return null;
  }
}

// ---------------------------------------------------------------- words for numbers

/** "60–80 clicks", "14–18" (a dial number needs no unit). */
export function rangeWords(scale: GrindScale, r: GrindRange): string {
  const unit = usesClicks(scale) ? " clicks" : scale.unit ? ` ${scale.unit}` : "";
  return `${span(format(scale, r.from), format(scale, r.to))}${unit}`;
}

// As Kotlin GrindRanges.span: "9.0–1 turn + 0.0" reads as a sum, so a reading with words in it is joined with "to".
function span(from: string, to: string): string {
  if (from === to) return from;
  return from.includes(" ") || to.includes(" ") ? `${from} to ${to}` : `${from}–${to}`;
}

/** "dial 6.0–8.0" on numbered dials, "2 turns + 1 to 3 turns + 0" on turn-counted ones, else null. */
export function rangeDialWords(scale: GrindScale, r: GrindRange): string | null {
  const a = reading(scale, r.from);
  const b = reading(scale, r.to);
  if (a == null || b == null) return null;
  return showsDialNumber(scale.dial) ? `dial ${span(a, b)}` : span(a, b);
}

/** "dial 7.5" on a numbered dial, "6 turns + 6" on a turn-counted one, null on a plain dial. */
export function dialWords(scale: GrindScale, value: number | null | undefined): string | null {
  if (value == null || value < 0) return null;
  const r = reading(scale, value);
  if (r == null) return null;
  return showsDialNumber(scale.dial) ? `dial ${r}` : r;
}

function roundToStep(v: number, step: number): number {
  const st = step > 0 ? step : 1;
  return Math.round(Math.round(v / st) * st * 100) / 100;
}

/** Ranges in BrewMethod order with the chosen method first (A§7.5). */
export function orderedRanges(scale: GrindScale, method: BrewMethod | null): { method: BrewMethod; range: GrindRange }[] {
  const all = BREW_METHODS.map((m) => m.id)
    .map((m) => ({ method: m, range: scale.ranges[m] }))
    .filter((x): x is { method: BrewMethod; range: GrindRange } => x.range != null);
  if (!method) return all;
  const chosen = all.filter((x) => x.method === method);
  return [...chosen, ...all.filter((x) => x.method !== method)];
}

function sourceNames(scale: GrindScale): string[] {
  return [...new Set(scale.rangeSources.map((x) => x.who).filter(Boolean))];
}

// ---------------------------------------------------------------- number stepper

/**
 * − [value] +, with the value typeable. Clamped steps never produce an impossible number; a
 * typed one is reported by the caller (aria-describedby on the error line).
 */
export function NumberStepper({
  label,
  value,
  onText,
  onStep,
  min,
  max,
  error,
  describedBy,
  width,
}: {
  label: string;
  value: string;
  onText: (t: string) => void;
  onStep: (by: -1 | 1) => void;
  min?: number;
  max?: number;
  error?: boolean;
  describedBy?: string;
  width?: string;
}) {
  const n = Number(value);
  const known = value.trim() !== "" && Number.isFinite(n);
  return (
    <div className={s.stepper} data-error={Boolean(error)}>
      <button type="button" className={s.stepBtn} aria-label={`${label}: less`} disabled={known && min != null && n <= min} onClick={() => onStep(-1)}>
        −
      </button>
      <input
        className={s.stepInput}
        style={width ? { width } : undefined}
        value={value}
        inputMode="numeric"
        aria-label={label}
        aria-invalid={error || undefined}
        aria-describedby={describedBy}
        onChange={(e) => onText(e.currentTarget.value.replace(/[^\d]/g, "").slice(0, 4))}
        onFocus={(e) => e.currentTarget.select()}
      />
      <button type="button" className={s.stepBtn} aria-label={`${label}: more`} disabled={known && max != null && n >= max} onClick={() => onStep(1)}>
        +
      </button>
    </div>
  );
}

// ---------------------------------------------------------------- DialReadingInput

type PartKey = "turns" | "number" | "tick";
interface Part {
  key: PartKey;
  label: string;
  max: number;
}

function partsFor(scale: GrindScale): Part[] {
  const d = scale.dial;
  switch (d.kind) {
    case "number-tick": {
      const numbers = d.clicksPerRotation > 0 ? Math.ceil(d.clicksPerRotation / d.ticksPerNumber) : 99;
      const parts: Part[] = [
        { key: "number", label: "Number", max: numbers - 1 },
        { key: "tick", label: "Mark", max: d.ticksPerNumber - 1 },
      ];
      // A dial that never wraps (cpr unknown) has no turns to count.
      return d.clicksPerRotation > 0 ? [{ key: "turns", label: "Turns", max: 3 }, ...parts] : parts;
    }
    case "turns":
    case "turns-dot":
      return [
        { key: "turns", label: "Turns", max: 99 },
        { key: "tick", label: "Clicks", max: d.clicksPerTurn - 1 },
      ];
    case "plain":
      return [];
  }
}

function split(scale: GrindScale, value: number | null): Record<PartKey, string> {
  const empty = { turns: "0", number: "0", tick: "0" };
  if (value == null || value < 0) return empty;
  const v = Math.round(value);
  const d = scale.dial;
  if (d.kind === "number-tick") {
    const turns = d.clicksPerRotation > 0 ? Math.floor(v / d.clicksPerRotation) : 0;
    const within = v - turns * d.clicksPerRotation;
    return { turns: String(turns), number: String(Math.floor(within / d.ticksPerNumber)), tick: String(within % d.ticksPerNumber) };
  }
  if (d.kind === "turns" || d.kind === "turns-dot") return { turns: String(Math.floor(v / d.clicksPerTurn)), number: "0", tick: String(v % d.clicksPerTurn) };
  return empty;
}

function impossibleWords(scale: GrindScale, parts: Part[], texts: Record<PartKey, string>): string | null {
  for (const p of parts) {
    const n = Number(texts[p.key] || "0");
    if (n > p.max) {
      if (p.key === "tick" && scale.dial.kind === "number-tick") return `Marks go 0–${p.max} between numbers on this dial.`;
      if (p.key === "tick") return `A turn has ${p.max + 1} clicks, so clicks go 0–${p.max}.`;
      if (p.key === "number") return `This dial's numbers go 0–${p.max}.`;
      return `Up to ${p.max} turns.`;
    }
  }
  return null;
}

/**
 * Enter a grind the way the dial reads (A§7.5): steppers in the dial's own terms (numbered dial:
 * turns, number, mark; turn-counted: turns, clicks; plain: one number) and a live line saying
 * what the app stores ("= 75 clicks (what the app stores)").
 */
export function DialReadingInput({ scale, value, onValue }: { scale: GrindScale; value: number | null; onValue: (v: number | null) => void }) {
  const parts = partsFor(scale);
  const lineId = useId();
  const [texts, setTexts] = useState(() => split(scale, value));
  const [plain, setPlain] = useState(() => (value == null ? "" : format(scale, value)));
  // Follow outside changes (a "Use 70" tap) without fighting the keyboard ("storing information
  // from previous renders", not an effect).
  const [seen, setSeen] = useState<{ value: number | null; dial: string }>({ value, dial: JSON.stringify(scale.dial) });
  const dialKey = JSON.stringify(scale.dial);
  if (seen.value !== value || seen.dial !== dialKey) {
    setSeen({ value, dial: dialKey });
    const mine = parts.length ? toValue(texts) : parsePlain(plain);
    if (mine !== value || seen.dial !== dialKey) {
      setTexts(split(scale, value));
      setPlain(value == null ? "" : format(scale, value));
    }
  }

  function toValue(t: Record<PartKey, string>): number | null {
    const r: DialReading = { turns: Number(t.turns || "0"), number: Number(t.number || "0"), tick: Number(t.tick || "0") };
    return fromReading(scale, r);
  }

  function parsePlain(t: string): number | null {
    const v = Number(t.trim().replace(",", "."));
    return t.trim() !== "" && Number.isFinite(v) ? v : null;
  }

  if (parts.length === 0) {
    const v = parsePlain(plain);
    const bad = plain.trim() !== "" && v == null;
    return (
      <div className={s.reading}>
        <div className={s.stepper} data-error={bad} style={{ width: "fit-content", padding: "0 14px" }}>
          <input
            className={s.stepInput}
            style={{ width: "6ch" }}
            value={plain}
            inputMode="decimal"
            aria-label="Dial setting"
            aria-invalid={bad || undefined}
            aria-describedby={lineId}
            placeholder="—"
            onChange={(e) => {
              const t = e.currentTarget.value.slice(0, 7);
              setPlain(t);
              const nv = parsePlain(t);
              if (t.trim() === "") onValue(null);
              else if (nv != null) onValue(nv);
            }}
          />
          {scale.unit && <span className="t-body-m ink-low">{scale.unit}</span>}
        </div>
        <p id={lineId} className={`t-body-s ${bad ? s.error : s.stores}`} role={bad ? "alert" : undefined}>
          {bad ? "Type the number printed on the dial, like 14.5." : "Type the number printed on the dial."}
        </p>
      </div>
    );
  }

  const problem = impossibleWords(scale, parts, texts);
  const current = problem ? null : toValue(texts);

  const commit = (next: Record<PartKey, string>) => {
    setTexts(next);
    if (impossibleWords(scale, parts, next)) return;
    const v = toValue(next);
    if (v != null) onValue(v);
  };

  const step = (p: Part, by: -1 | 1) => {
    // Steps carry over like the dial does: mark 9 + 1 is the next number.
    // From an impossible typed reading, step from the last good value instead.
    const base = current ?? value ?? 0;
    const unit = p.key === "turns" ? turnSize(scale) : p.key === "number" && scale.dial.kind === "number-tick" ? scale.dial.ticksPerNumber : 1;
    const nv = Math.max(0, base + by * unit);
    const next = split(scale, nv);
    setTexts(next);
    onValue(nv);
  };

  return (
    <div className={s.reading}>
      <div className={s.parts} role="group" aria-label="Dial reading">
        {parts.map((p) => (
          <div key={p.key} className={s.part}>
            <EyebrowLabel text={p.label} />
            <NumberStepper
              label={p.label}
              value={texts[p.key]}
              min={0}
              error={Number(texts[p.key] || "0") > p.max}
              describedBy={lineId}
              onText={(t) => commit({ ...texts, [p.key]: t })}
              onStep={(by) => step(p, by)}
            />
          </div>
        ))}
      </div>
      <p id={lineId} className={`t-body-s ${problem ? s.error : s.stores}`} role={problem ? "alert" : undefined} aria-live="polite">
        {/* Only click grinders have dial words (non-click dials always read plain). */}
        {problem ?? (current != null ? `= ${format(scale, current)} clicks (what the app stores)` : "")}
      </p>
    </div>
  );
}

function turnSize(scale: GrindScale): number {
  const d = scale.dial;
  if (d.kind === "number-tick") return d.clicksPerRotation > 0 ? d.clicksPerRotation : 0;
  if (d.kind === "turns" || d.kind === "turns-dot") return d.clicksPerTurn;
  return 0;
}

// ---------------------------------------------------------------- GrindRangeCard

/**
 * "STARTING POINTS" (A§7.5): one row per catalog range, the chosen method first and
 * highlighted, the sources as links, the confidence chip and "A starting point. Taste decides."
 * Renders nothing when the grinder has no ranges. `collapsed` is the Gear card's one line.
 */
export const GrindRangeCard = memo(function GrindRangeCard({ scale, method, collapsed = false }: { scale: GrindScale; method: BrewMethod | null; collapsed?: boolean }) {
  const rows = orderedRanges(scale, method);
  if (rows.length === 0) return null;
  const chip = confidenceChip(scale.confidence);
  if (collapsed) {
    const first = rows[0]!;
    const dial = rangeDialWords(scale, first.range);
    return (
      <p className={`t-body-s ${s.oneLine}`}>
        <span>
          Start {brewMethodLabel(first.method).toLowerCase()} at {rangeWords(scale, first.range)}
          {dial ? ` · ${dial}` : ""}
        </span>
        {chip && <StatusChip status={chip.status} text={chip.text} />}
      </p>
    );
  }
  const who = sourceNames(scale);
  return (
    <SoftCard>
      <div className={s.rangeCard}>
        <div className={s.rangeHead}>
          <EyebrowLabel text="Starting points" as="h3" />
          {chip && <StatusChip status={chip.status} text={chip.text} />}
        </div>
        <ul className={s.rangeRows}>
          {rows.map(({ method: m, range }) => {
            const dial = rangeDialWords(scale, range);
            return (
              <li key={m} className={s.rangeRow} data-chosen={m === method}>
                <span className={`t-body-m ${s.rangeMethod}`}>{brewMethodLabel(m)}</span>
                <span className={`t-body-m ${s.rangeValue}`}>
                  {rangeWords(scale, range)}
                  {dial && <span className="ink-mid"> · {dial}</span>}
                </span>
              </li>
            );
          })}
        </ul>
        {who.length > 0 && (
          <p className={`t-body-s ${s.sources}`}>
            <span className="ink-mid">From:</span>
            {who.map((name) => {
              const src = scale.rangeSources.find((x) => x.who === name && x.url);
              return src ? (
                <a key={name} href={src.url} target="_blank" rel="noopener noreferrer">
                  {name}
                </a>
              ) : (
                <span key={name}>{name}</span>
              );
            })}
          </p>
        )}
        <p className={`t-body-s ${s.note}`}>A starting point. Taste decides.</p>
      </div>
    </SoftCard>
  );
});

// ---------------------------------------------------------------- GrindStartHint

/**
 * "Start around 70 · 1Zpresso's range 60–80" with a "Use 70" pill (midpoint rounded to the step).
 * The pill goes once `isCurrent` says the grind is already that value: it would do nothing.
 */
export function GrindStartHint({ scale, method, onUse, isCurrent }: { scale: GrindScale; method: BrewMethod | null; onUse: (v: number) => void; isCurrent?: (v: number) => boolean }) {
  const range = method ? scale.ranges[method] : undefined;
  if (!range) return null;
  const mid = roundToStep((range.from + range.to) / 2, scale.step);
  const who = sourceNames(scale)[0];
  const dial = dialWords(scale, mid);
  return (
    <div className={s.hint}>
      <p className={`t-body-s ${s.hintText}`}>
        Start around {format(scale, mid)}
        {dial ? ` (${dial})` : ""} · {who ? `${who}'s range` : "range"} {rangeWords(scale, range)}
      </p>
      {!isCurrent?.(mid) && <PillButton text={`Use ${format(scale, mid)}`} onClick={() => onUse(mid)} />}
    </div>
  );
}

/** The whole-number midpoint the hint offers (exported for tests and brew setup). */
export function startValue(scale: GrindScale, method: BrewMethod): number | null {
  const r = scale.ranges[method];
  return r ? roundToStep((r.from + r.to) / 2, scale.step) : null;
}
