/** Port of ui/components/Bits.kt (except CoffeeSheet, in Sheet.tsx). */
import { memo, useId, useLayoutEffect, useRef, type CSSProperties, type KeyboardEvent, type ReactNode } from "react";
import type { Status } from "@/domain/core/status";
import { useSlidingIndicator } from "../useSlidingIndicator";
import { IconWell } from "./Buttons";
import { StatusChip } from "./Status";
import s from "./components.module.css";

/** Upper-cases a label without mangling µ (it upper-cases to Greek Mu, which reads as "MM"). */
export function toCaps(text: string): string {
  return text.includes("µ") ? Array.from(text, (ch) => (ch === "µ" ? ch : ch.toUpperCase())).join("") : text.toUpperCase();
}

// ---------------------------------------------------------------- labels

/** The label above a value: 11 px, tracked, upper-cased, inkLow. */
export const EyebrowLabel = memo(function EyebrowLabel({ text, color, uppercase = true, as: Tag = "span" }: { text: string; color?: string; uppercase?: boolean; as?: "span" | "h2" | "h3" | "p" }) {
  // Upper-casing is done in JS (toCaps) so µ survives; the class only sets size and tracking.
  return (
    <Tag className={`eyebrow ${s.eyebrow}`} style={{ textTransform: "none", ...(color ? { color } : {}) }}>
      {uppercase ? toCaps(text) : text}
    </Tag>
  );
});

/** A section's eyebrow as an <h2>, with an optional trailing action ("See all"). */
export const SectionLabel = memo(function SectionLabel({ text, trailing, onTrailing, topGap = 24 }: { text: string; trailing?: string; onTrailing?: () => void; topGap?: number }) {
  return (
    <div className={s.sectionLabel} style={{ marginTop: topGap }}>
      <EyebrowLabel text={text} as="h2" />
      {trailing && onTrailing && (
        <button type="button" className={`${s.sectionTrailing} t-label-m`} onClick={onTrailing}>
          {trailing}
        </button>
      )}
    </div>
  );
});

export const SectionHeader = memo(function SectionHeader({ title, subtitle, trailing }: { title: string; subtitle?: string; trailing?: ReactNode }) {
  return (
    <div className={s.sectionHeader}>
      <div className={s.sectionHeaderText}>
        <h2 className="t-headline-s">{title}</h2>
        {subtitle && <p className="t-body-s ink-mid">{subtitle}</p>}
      </div>
      {trailing}
    </div>
  );
});

/**
 * Sticky screen title. The hairline under it appears once content scrolls beneath: a CSS
 * scroll-driven animation where supported, else an IntersectionObserver sentinel. Never a
 * scroll listener (rule 8).
 */
export function ScreenHeader({ eyebrow, title, trailing, status }: { eyebrow: string; title: string; trailing?: ReactNode; status?: ReactNode }) {
  const header = useRef<HTMLElement>(null);
  const sentinel = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    if (typeof CSS !== "undefined" && CSS.supports?.("animation-timeline: scroll()")) return;
    const el = sentinel.current;
    const h = header.current;
    if (!el || !h || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver(([e]) => {
      h.dataset.scrolled = String(!(e?.isIntersecting ?? true));
    });
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <>
      <span ref={sentinel} className={s.sentinel} aria-hidden="true" />
      <header ref={header} className={s.screenHeader}>
        <div className={s.screenHeaderRow}>
          <div>
            <EyebrowLabel text={eyebrow} />
            <h1 className={`t-display-s ${s.screenHeaderTitle}`}>{title}</h1>
          </div>
          {trailing}
        </div>
        {status && <div className={s.screenHeaderStatus}>{status}</div>}
        <span className={s.headerHairline} aria-hidden="true" />
      </header>
    </>
  );
}

export const DetailTopBar = memo(function DetailTopBar({ onBack, title, actions }: { onBack: () => void; title?: string; actions?: ReactNode }) {
  return (
    <div className={s.detailTopBar}>
      <IconWell icon="ChevronLeft" label="Back" onClick={onBack} />
      {title && <span className={`t-title-m ${s.detailTopBarTitle}`}>{title}</span>}
      {actions && <div className={s.detailTopBarActions}>{actions}</div>}
    </div>
  );
});

// ---------------------------------------------------------------- surfaces

export function SoftCard({
  children,
  onClick,
  color,
  border,
  padding,
  className,
  label,
}: {
  children: ReactNode;
  onClick?: () => void;
  color?: string;
  border?: string;
  padding?: string | number;
  className?: string;
  /** Accessible name when the card is a button. */
  label?: string;
}) {
  const style: CSSProperties = {
    ...(color ? { background: color } : {}),
    ...(border ? { borderColor: border } : {}),
    ...(padding != null ? { padding } : {}),
  };
  return onClick ? (
    <button type="button" className={`${s.card} ${s.pressable} ${className ?? ""}`} style={style} onClick={onClick} aria-label={label}>
      {children}
    </button>
  ) : (
    <div className={`${s.card} ${className ?? ""}`} style={style}>
      {children}
    </div>
  );
}

export const QuietPanel = memo(function QuietPanel({ text }: { text: string }) {
  return (
    <SoftCard>
      <p className={`t-body-m ${s.quietPanel}`}>{text}</p>
    </SoftCard>
  );
});

export const HairLine = memo(function HairLine({ inset = 0, color }: { inset?: number; color?: string }) {
  return <hr className={s.hairline} style={{ marginLeft: inset, ...(color ? { background: color } : {}) }} />;
});

export const VerticalHairline = memo(function VerticalHairline({ color }: { color?: string }) {
  return <span className={s.vhairline} style={color ? { background: color } : undefined} aria-hidden="true" />;
});

/** The boarding-pass tear line: a dashed rule with a notch at each edge. */
export const PerforatedDivider = memo(function PerforatedDivider() {
  return <div className={s.perforated} aria-hidden="true" />;
});

// ---------------------------------------------------------------- chips

export const SelectableChip = memo(function SelectableChip({
  text,
  selected,
  onClick,
  status,
  enabled = true,
  onCanvas = false,
  leading,
}: {
  text: string;
  selected: boolean;
  onClick: () => void;
  status?: Status;
  enabled?: boolean;
  onCanvas?: boolean;
  leading?: ReactNode;
}) {
  return (
    <button type="button" className={`${s.chip} t-label-l`} aria-pressed={selected} data-on-canvas={onCanvas} disabled={!enabled} onClick={onClick} data-status={status}>
      {leading}
      {text}
    </button>
  );
});

export function ChipFlow({ options, selected, onToggle, label }: { options: readonly string[]; selected: ReadonlySet<string>; onToggle: (o: string) => void; label?: string }) {
  return (
    <div className={s.chipFlow} role="group" aria-label={label}>
      {options.map((o) => (
        <SelectableChip key={o} text={o} selected={selected.has(o)} onClick={() => onToggle(o)} />
      ))}
    </div>
  );
}

/**
 * Arrow/Home/End movement for a one-of-N group (A2): the next index from a key, or null for any
 * other key. Left/Up go back, Right/Down forward, both wrap.
 */
export function stepIndex(key: string, index: number, count: number): number | null {
  if (count <= 0) return null;
  switch (key) {
    case "ArrowRight":
    case "ArrowDown":
      return (Math.max(index, -1) + 1) % count;
    case "ArrowLeft":
    case "ArrowUp":
      return (Math.max(index, 0) - 1 + count) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}

/**
 * One indicator sliding with transform + spatialDefault, like the bottom bar's pill. A radio
 * group with a roving tabindex (A2): Tab lands on the selected option, arrows move and select.
 * Pass `panelIds` only when the options really switch panels (Gear's sections): it then becomes a
 * tablist whose tabs have aria-controls.
 */
export function SegmentedControl({
  options,
  selectedIndex,
  onSelect,
  onCard = false,
  label,
  panelIds,
}: {
  options: readonly string[];
  selectedIndex: number;
  onSelect: (i: number) => void;
  onCard?: boolean;
  label?: string;
  /** One element id per option: renders role="tab" + aria-controls instead of radios. */
  panelIds?: readonly string[];
}) {
  const track = useRef<HTMLDivElement>(null);
  const indicator = useRef<HTMLDivElement>(null);
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  useSlidingIndicator(track, indicator, selectedIndex, options.length);
  const tabs = panelIds != null;
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    const next = stepIndex(e.key, selectedIndex, options.length);
    if (next == null) return;
    e.preventDefault();
    onSelect(next);
    buttons.current[next]?.focus();
  };
  return (
    <div className={s.segmented} data-on-card={onCard} role={tabs ? "tablist" : "radiogroup"} aria-label={label}>
      <div ref={track} className={s.segTrack} aria-hidden="true">
        <div ref={indicator} className={s.segIndicator} />
      </div>
      {options.map((o, i) => (
        <button
          key={o}
          ref={(el) => {
            buttons.current[i] = el;
          }}
          type="button"
          role={tabs ? "tab" : "radio"}
          aria-selected={tabs ? i === selectedIndex : undefined}
          aria-checked={tabs ? undefined : i === selectedIndex}
          aria-controls={tabs ? panelIds[i] : undefined}
          tabIndex={i === selectedIndex || (selectedIndex < 0 && i === 0) ? 0 : -1}
          className={`${s.segOption} t-label-l`}
          onClick={() => onSelect(i)}
          onKeyDown={onKeyDown}
        >
          {o}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- slider, rating

/** "7 of 10, electric": the hint's words ("flat → electric") at each end. */
export function axisValueText(value: number, hint: string, max = 10): string {
  const [low, high] = hint.split("→").map((w) => w.trim());
  const word = value > max / 2 ? high : value < max / 2 ? low : "middle";
  return `${value} of ${max}${word ? ", " + word : ""}`;
}

/** A native range input underneath, so keyboards and screen readers work. */
export function AxisSlider({ label, hint, value, onChange, accent }: { label: string; hint: string; value: number; onChange: (v: number) => void; accent?: string }) {
  const id = useId();
  return (
    <div className={s.axis}>
      <div className={s.axisTop}>
        <label htmlFor={id} className="t-title-s">
          {label}
        </label>
        <span className="t-body-s ink-low">{hint}</span>
        <span className="num-inline">{value}</span>
      </div>
      <input
        id={id}
        className={s.range}
        type="range"
        min={0}
        max={10}
        step={1}
        value={value}
        aria-valuetext={axisValueText(value, hint)}
        style={accent ? { accentColor: accent } : undefined}
        onChange={(e) => onChange(Number(e.currentTarget.value))}
      />
    </div>
  );
}

/** Overall score as a radio group (A2): roving tabindex, arrows move and set, Home/End jump. */
export function DotRating({ value, onChange, max = 10, accent, label = "Overall" }: { value: number; onChange: (v: number) => void; max?: number; accent?: string; label?: string }) {
  const dots = useRef<(HTMLButtonElement | null)[]>([]);
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    const next = stepIndex(e.key, value - 1, max);
    if (next == null) return;
    e.preventDefault();
    onChange(next + 1);
    dots.current[next]?.focus();
  };
  return (
    <div className={s.dots} role="radiogroup" aria-label={label} style={accent ? ({ "--dot-accent": accent } as CSSProperties) : undefined}>
      {Array.from({ length: max }, (_, i) => i + 1).map((n) => (
        <button
          key={n}
          ref={(el) => {
            dots.current[n - 1] = el;
          }}
          type="button"
          role="radio"
          aria-checked={n === value}
          aria-label={`${n} of ${max}`}
          tabIndex={n === value || (value < 1 && n === 1) ? 0 : -1}
          className={s.dot}
          data-on={n <= value}
          onClick={() => onChange(n === value ? 0 : n)}
          onKeyDown={onKeyDown}
        />
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- rows

export const KeyValueRow = memo(function KeyValueRow({ k, value }: { k: string; value: string }) {
  return (
    <div className={s.kv}>
      <span className={`t-body-m ${s.kvKey}`}>{k}</span>
      <span className={`t-body-m ${s.kvValue}`}>{value}</span>
    </div>
  );
});

/** Native checkbox with role="switch"; the whole row is the label. */
export function ToggleRow({ label, checked, onChange, subtitle, disabled }: { label: string; checked: boolean; onChange: (v: boolean) => void; subtitle?: string; disabled?: boolean }) {
  return (
    <label className={s.toggleRow}>
      <span className={s.rowText}>
        <span className="t-body-l">{label}</span>
        {subtitle && <span className={`t-body-s ${s.rowSub}`}>{subtitle}</span>}
      </span>
      <input type="checkbox" role="switch" className={s.switch} checked={checked} disabled={disabled} onChange={(e) => onChange(e.currentTarget.checked)} />
    </label>
  );
}

export function RadioRow({
  title,
  selected,
  onClick,
  name,
  subtitle,
  badge,
  note,
  noteStatus,
  detail,
}: {
  title: string;
  selected: boolean;
  onClick: () => void;
  /** Radio group name. */
  name: string;
  subtitle?: string;
  badge?: string;
  note?: string;
  noteStatus?: Status;
  detail?: string;
}) {
  // Named and described by reference, with the visible copy hidden from the tree: a wrapped
  // label read its text twice in browse mode (once as the radio's name, once as plain text).
  const id = useId();
  const extra = [subtitle && `${id}-s`, note && `${id}-n`, detail && `${id}-d`].filter(Boolean).join(" ") || undefined;
  return (
    <label className={s.radioRow}>
      <input type="radio" name={name} className={s.radio} checked={selected} onChange={onClick} aria-labelledby={`${id}-t`} aria-describedby={extra} />
      <span className={s.rowText} aria-hidden="true">
        <span className="t-body-l" id={`${id}-t`}>
          {title} {badge && <span className={`${s.badge} micro`}>{badge}</span>}
        </span>
        {subtitle && (
          <span className={`t-body-s ${s.rowSub}`} id={`${id}-s`}>
            {subtitle}
          </span>
        )}
        {note &&
          (noteStatus ? (
            <span id={`${id}-n`}>
              <StatusChip status={noteStatus} text={note} />
            </span>
          ) : (
            <span className={`t-body-s ${s.rowSub}`} id={`${id}-n`}>
              {note}
            </span>
          ))}
        {detail && (
          <span className={`t-body-s ${s.rowSub}`} id={`${id}-d`}>
            {detail}
          </span>
        )}
      </span>
    </label>
  );
}

// ---------------------------------------------------------------- skeleton, placeholder

export const SkeletonBar = memo(function SkeletonBar({ widthFraction, height, radius = 10 }: { widthFraction: number; height: number; radius?: number }) {
  return <div className={s.skeleton} style={{ width: `${widthFraction * 100}%`, height, borderRadius: radius }} aria-hidden="true" />;
});

/** A dashed outline at the final size: stubs render this so layouts can be built against them. */
export const DashedPlaceholder = memo(function DashedPlaceholder({ text, onClick, height }: { text: string; onClick?: () => void; height?: number | string }) {
  const style = height != null ? { minHeight: height } : undefined;
  return onClick ? (
    <button type="button" className={`${s.placeholder} t-label-l`} style={style} onClick={onClick}>
      {text}
    </button>
  ) : (
    <div className={`${s.placeholder} t-label-l`} style={style}>
      {text}
    </div>
  );
});

// ---------------------------------------------------------------- CTA bar, tiles, badges

export function StickyCtaBar({ children, color }: { children: ReactNode; color?: string }) {
  return (
    <div className={s.stickyCta} style={color ? { background: `linear-gradient(to bottom, transparent, ${color} 24px)` } : undefined}>
      {children}
    </div>
  );
}

export const StatTile = memo(function StatTile({ label, value, sub, accent }: { label: string; value: string; sub?: string; accent?: string }) {
  return (
    <div className={s.statTile}>
      <EyebrowLabel text={label} />
      <span className="num-medium" style={accent ? { color: accent } : undefined}>
        {value}
      </span>
      {sub && <span className="t-body-s ink-mid">{sub}</span>}
    </div>
  );
});

/** Bits.kt AccentBadge: `accent` is ignored there too (screens use tokens, no arbitrary fills). */
export const AccentBadge = memo(function AccentBadge({ text }: { text: string; accent?: string }) {
  return <span className={`${s.accentBadge} micro`}>{toCaps(text)}</span>;
});
