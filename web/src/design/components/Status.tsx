/** Port of the composables in ui/components/Status.kt. A status colour always travels with a word. */
import { memo } from "react";
import { statusColorVar, statusContainerVar, type Status } from "@/domain/core/status";
import s from "./components.module.css";

export const StatusDot = memo(function StatusDot({ status, size = 8 }: { status: Status; size?: number }) {
  const color = status === "live" ? "var(--crema)" : status === "neutral" ? "var(--neutral-mark)" : statusColorVar(status);
  return <span className={s.statusDot} data-live={status === "live"} style={{ width: size, height: size, background: color }} aria-hidden="true" />;
});

/** "DEFAULT", "Unverified"… Neutral uses inkLow: 4.8–5.0:1 on surface2, level with the others. */
export const StatusChip = memo(function StatusChip({ status, text }: { status: Status; text: string }) {
  return (
    <span
      className={`${s.statusChip} micro`}
      style={{ background: statusContainerVar(status), color: status === "neutral" ? "var(--ink-low)" : statusColorVar(status) }}
    >
      {text}
    </span>
  );
});

/** Dot, a lead word in the status colour, then the rest in inkMid. Never feed it a ticking value. */
export const StatusLine = memo(function StatusLine({ status, lead, rest, onClick }: { status: Status; lead: string; rest?: string; onClick?: () => void }) {
  const leadColor = status === "neutral" ? "var(--ink-high)" : status === "unrated" ? "var(--ink-mid)" : statusColorVar(status);
  const body = (
    <>
      <StatusDot status={status} />
      <span className="t-body-m">
        <span className={s.statusLineLead} style={{ color: leadColor }}>
          {lead}
        </span>
        {rest && <span className={s.statusLineRest}> · {rest}</span>}
      </span>
    </>
  );
  return onClick ? (
    <button type="button" className={s.statusLine} onClick={onClick}>
      {body}
    </button>
  ) : (
    <p className={s.statusLine}>{body}</p>
  );
});
