/** Small building blocks shared by the Gear editors (ports of GearScreen.kt's private helpers). */
import { useId, useState, type ReactNode } from "react";
import { EyebrowLabel, Icon, SelectableChip } from "@/design/components";
import { epochDayToIso, isoToEpochDay } from "@/domain/core/format";
import s from "./gear.module.css";

export function SheetTitle({ text }: { text: string }) {
  return <h2 className={`t-headline-s ${s.sheetTitle}`}>{text}</h2>;
}

export function SheetNote({ text }: { text: string }) {
  return <p className={s.note}>{text}</p>;
}

export function ChipGroup({ label, children, note }: { label: string; children: ReactNode; note?: string | null }) {
  return (
    <div className={s.chipGroup} role="group" aria-label={label}>
      <EyebrowLabel text={label} />
      <div className={s.chipRow}>{children}</div>
      {note && <p className={`t-body-s ${s.described}`}>{note}</p>}
    </div>
  );
}

/** Single-choice chips where tapping the selected chip clears it (as Android's bean sheet). */
export function ChoiceChips<T extends string>({
  label,
  options,
  value,
  onChange,
  describe,
  allowClear = true,
}: {
  label: string;
  options: readonly { id: T; label: string }[];
  value: T | null | "";
  onChange: (v: T | null) => void;
  describe?: (id: T) => string | null | undefined;
  allowClear?: boolean;
}) {
  return (
    <ChipGroup label={label} note={value && describe ? describe(value) : null}>
      {options.map((o) => (
        <SelectableChip key={o.id} text={o.label} selected={value === o.id} onClick={() => onChange(value === o.id && allowClear ? null : o.id)} />
      ))}
    </ChipGroup>
  );
}

/** A row that opens a picker: label above, the current choice (or a placeholder), a chevron. */
export function PickRow({ label, value, placeholder, onClick, trailing }: { label: string; value: string; placeholder: string; onClick: () => void; trailing?: ReactNode }) {
  return (
    <div className={s.field}>
      <EyebrowLabel text={label} />
      <button type="button" className={s.pickRow} onClick={onClick} aria-label={`${label}: ${value || placeholder}`}>
        <span className={s.pickText}>
          <span className={`t-body-l ${s.pickValue}`} data-empty={!value}>
            {value || placeholder}
          </span>
        </span>
        {trailing}
        <Icon name="ChevronLeft" size={20} className={s.chev} />
      </button>
    </div>
  );
}

/** "More details" / "Edit the scale": the space opens at once and the content fades in. */
export function Disclosure({ label, open, onToggle, children }: { label: string; open: boolean; onToggle: (v: boolean) => void; children: ReactNode }) {
  const id = useId();
  return (
    <>
      <button type="button" className={`${s.disclosure} t-label-l`} aria-expanded={open} aria-controls={id} onClick={() => onToggle(!open)}>
        {label}
        <Icon name="ChevronLeft" size={18} className={s.disclosureChev} />
      </button>
      {open && (
        <div id={id} className={s.reveal}>
          {children}
        </div>
      )}
    </>
  );
}

/** A native date picker in a field well; value is a local epoch day (A§8.1). */
export function DateField({ label, day, onDay, helper, max }: { label: string; day: number | null; onDay: (d: number | null) => void; helper?: string; max?: number }) {
  const id = useId();
  return (
    <div className={s.field}>
      <label htmlFor={id} className="eyebrow">
        {label}
      </label>
      <div className={s.dateWell}>
        <input
          id={id}
          type="date"
          className={`${s.dateInput} t-body-l`}
          value={day == null ? "" : epochDayToIso(day)}
          max={max == null ? undefined : epochDayToIso(max)}
          onChange={(e) => onDay(e.currentTarget.value ? isoToEpochDay(e.currentTarget.value) : null)}
        />
      </div>
      {helper && <p className={`t-body-s ${s.described}`}>{helper}</p>}
    </div>
  );
}

/** Two-tap delete: the first tap asks, the second does it (no modal on top of a sheet). */
export function useTwoTap(): [armed: boolean, tap: (action: () => void) => void] {
  const [armed, setArmed] = useState(false);
  return [
    armed,
    (action) => {
      if (armed) action();
      else {
        setArmed(true);
        setTimeout(() => setArmed(false), 4000);
      }
    },
  ];
}
