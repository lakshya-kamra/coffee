/** Port of ui/components/Grouped.kt: inset-grouped rows that read as one card. */
import { memo, type CSSProperties, type ReactNode } from "react";
import { Brewers } from "@/catalog/catalog";
import { brewerRowTag } from "@/domain/core/brewer";
import { clipWords, clock, timeLabel } from "@/domain/core/format";
import { scoreStatus, signedClock, statusColorVar, timeStatus } from "@/domain/core/status";
import type { BrewRow } from "@/data/schema";
import type { IconName } from "../icons";
import { toCaps } from "./Bits";
import { Icon } from "./Icon";
import s from "./components.module.css";

export type GroupPosition = "first" | "middle" | "last" | "only";

export function groupPosition(index: number, size: number): GroupPosition {
  if (size === 1) return "only";
  if (index === 0) return "first";
  if (index === size - 1) return "last";
  return "middle";
}

export function GroupRow({ position, onClick, hairlineInset = 16, children, label }: { position: GroupPosition; onClick?: () => void; hairlineInset?: number; children: ReactNode; label?: string }) {
  const style = { "--hairline-inset": `${hairlineInset}px` } as CSSProperties;
  return onClick ? (
    <button type="button" className={`${s.groupRow} ${s.pressable}`} data-pos={position} style={style} onClick={onClick} aria-label={label}>
      {children}
    </button>
  ) : (
    <div className={s.groupRow} data-pos={position} style={style}>
      {children}
    </div>
  );
}

/** "Square Mile — Red Brick" becomes "Red Brick": the roaster is noise in a one-line meta. */
export function beanShortName(label: string): string {
  const i = label.indexOf(" — ");
  return (i >= 0 ? label.slice(i + 3) : label).trim();
}

/** One brew in the log: time + brewer tag, recipe and meta line, the score in its status colour. */
export const LogRow = memo(function LogRow({ log, position, onOpen, targetSec }: { log: BrewRow; position: GroupPosition; onOpen: () => void; targetSec?: number | null }) {
  const brewer = Brewers.byId(log.brewerId) ?? Brewers.resolveOrNull(log.brewer);
  const tag = toCaps(brewer ? brewerRowTag(brewer) : log.brewer);
  const target = targetSec ?? null;
  // Same rule as Android: on time says so in words, and only a miss shows the signed delta.
  const delta =
    target != null && target > 0 && log.totalTimeSec > 0
      ? (() => {
          const st = timeStatus(log.totalTimeSec, target)[0];
          return { status: st, text: st === "good" ? "on time" : signedClock(log.totalTimeSec - target) };
        })()
      : null;
  const grind = log.grindClicks != null ? `${log.grindClicks} clk` : log.grindSetting.trim() ? clipWords(log.grindSetting, 14) : null;
  const bean = beanShortName(log.beanLabel) || null;
  const meta: ReactNode[] = [];
  if (grind) meta.push(grind);
  if (log.totalTimeSec > 0)
    meta.push(
      <span key="t">
        {clock(log.totalTimeSec)}
        {delta && <span style={{ color: statusColorVar(delta.status) }}> {delta.text}</span>}
      </span>,
    );
  if (bean) meta.push(bean);
  return (
    <GroupRow position={position} onClick={onOpen} hairlineInset={96}>
      <div className={s.logRow}>
        <div className={s.logTime}>
          <span className="num-inline">{timeLabel(log.startedAt)}</span>
          <span className="micro ink-low">{tag}</span>
        </div>
        <div className={s.logMain}>
          <span className={`t-title-m ${s.ellipsis}`}>{log.recipeName.trim() || "Brew"}</span>
          <span className={`t-body-s ink-mid ${s.ellipsis}`}>
            {meta.map((m, i) => (
              <span key={i}>
                {i > 0 && " · "}
                {m}
              </span>
            ))}
          </span>
        </div>
        <span className="num-small" style={{ color: log.overall > 0 ? statusColorVar(scoreStatus(log.overall)) : "var(--ink-low)" }} aria-label={log.overall > 0 ? `Scored ${log.overall} of 10` : "Not rated"}>
          {log.overall > 0 ? log.overall : "—"}
        </span>
      </div>
    </GroupRow>
  );
});

export function ActionRow({ position, icon, title, subtitle, onClick, trailing, danger = false }: { position: GroupPosition; icon?: IconName; title: string; subtitle?: string; onClick: () => void; trailing?: ReactNode; danger?: boolean }) {
  return (
    <GroupRow position={position} onClick={onClick} hairlineInset={icon ? 56 : 16}>
      <div className={s.actionRow}>
        {icon && <Icon name={icon} size={22} />}
        <span className={s.rowText}>
          <span className="t-body-l" style={danger ? { color: "var(--fault)" } : undefined}>
            {title}
          </span>
          {subtitle && <span className={`t-body-s ${s.rowSub}`}>{subtitle}</span>}
        </span>
        {trailing}
      </div>
    </GroupRow>
  );
}
