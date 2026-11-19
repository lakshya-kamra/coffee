/** Port of ui/components/Fields.kt: label above, the value in a well, a helper or error line below. */
import { useId } from "react";
import s from "./components.module.css";

export function FieldWell({
  label,
  value,
  onChange,
  placeholder,
  helper,
  error,
  suffix,
  numeric = false,
  multiline = false,
  type = "text",
  autoComplete = "off",
  maxLength,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  helper?: string;
  error?: string | null;
  suffix?: string;
  /** Decimal keypad on phones. */
  numeric?: boolean;
  multiline?: boolean;
  type?: "text" | "password" | "url" | "email";
  autoComplete?: string;
  maxLength?: number;
}) {
  const id = useId();
  const noteId = `${id}-note`;
  const note = error || helper;
  const common = {
    id,
    className: `${s.fieldInput} t-body-l`,
    value,
    placeholder,
    maxLength,
    "aria-invalid": error ? true : undefined,
    "aria-describedby": note ? noteId : undefined,
  } as const;
  return (
    <div className={s.field}>
      <label htmlFor={id} className="eyebrow">
        {label}
      </label>
      <div className={s.fieldWell} data-error={Boolean(error)}>
        {multiline ? (
          <textarea {...common} onChange={(e) => onChange(e.currentTarget.value)} />
        ) : (
          <input
            {...common}
            type={type}
            inputMode={numeric ? "decimal" : undefined}
            autoComplete={autoComplete}
            spellCheck={type === "password" ? false : undefined}
            onChange={(e) => onChange(e.currentTarget.value)}
          />
        )}
        {suffix && <span className={`${s.fieldSuffix} t-body-m`}>{suffix}</span>}
      </div>
      {note && (
        <p id={noteId} className={`t-body-s ${error ? s.fieldError : s.fieldHelper}`} role={error ? "alert" : undefined}>
          {note}
        </p>
      )}
    </div>
  );
}
