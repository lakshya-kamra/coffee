/** Port of ui/components/Buttons.kt. Press feedback is CSS (:active scale 0.97), never JS. */
import { memo, type ReactNode } from "react";
import type { IconName } from "../icons";
import { Icon } from "./Icon";
import { tooltipProps, type TooltipSide } from "./Tooltip";
import s from "./components.module.css";

interface BaseButton {
  text: string;
  onClick: () => void;
  icon?: IconName;
  enabled?: boolean;
  className?: string;
}

/** Crema: the one primary action on a screen. */
export const PrimaryButton = memo(function PrimaryButton({ text, onClick, icon, enabled = true, height = 56, className }: BaseButton & { height?: number }) {
  return (
    <button type="button" className={`${s.primary} primary-label ${className ?? ""}`} style={{ minHeight: height }} disabled={!enabled} onClick={onClick}>
      {icon && <Icon name={icon} size={22} />}
      {text}
    </button>
  );
});

export const SecondaryButton = memo(function SecondaryButton({ text, onClick, icon, enabled = true, height = 48, onCanvas = false, className }: BaseButton & { height?: number; onCanvas?: boolean }) {
  return (
    <button type="button" className={`${s.secondary} t-label-l ${className ?? ""}`} style={{ minHeight: height }} data-on-canvas={onCanvas} disabled={!enabled} onClick={onClick}>
      {icon && <Icon name={icon} size={20} />}
      {text}
    </button>
  );
});

export const QuietButton = memo(function QuietButton({ text, onClick, icon, enabled = true, color, className }: BaseButton & { color?: string }) {
  return (
    <button type="button" className={`${s.quiet} t-label-l ${className ?? ""}`} style={color ? { color } : undefined} disabled={!enabled} onClick={onClick}>
      {icon && <Icon name={icon} size={20} />}
      {text}
    </button>
  );
});

export const PillButton = memo(function PillButton({ text, onClick, icon, enabled = true, selected, className }: BaseButton & { selected?: boolean }) {
  return (
    <button type="button" className={`${s.pill} t-label-l ${className ?? ""}`} aria-pressed={selected} disabled={!enabled} onClick={onClick}>
      {icon && <Icon name={icon} size={18} />}
      {text}
    </button>
  );
});

/**
 * A square icon button. `label` is the accessible name (contentDescription) and the tooltip
 * (v4 §2.6, A10); `keys` adds the shortcut to the tooltip and aria-keyshortcuts, never the name.
 */
export const IconWell = memo(function IconWell({
  icon,
  label,
  onClick,
  size = 40,
  iconSize = 20,
  onCanvas = true,
  tint,
  fill,
  enabled = true,
  className,
  children,
  keys,
  tooltipSide,
}: {
  icon: IconName;
  label: string;
  /** Shortcut shown in the tooltip ("space", "mod+k"). */
  keys?: string;
  tooltipSide?: TooltipSide;
  onClick: () => void;
  size?: number;
  iconSize?: number;
  onCanvas?: boolean;
  tint?: string;
  fill?: string;
  enabled?: boolean;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <button
      type="button"
      className={`${s.iconWell} ${className ?? ""}`}
      data-on-canvas={onCanvas}
      style={{ width: size, height: size, color: tint, background: fill }}
      aria-label={label}
      {...tooltipProps(label, keys, tooltipSide)}
      disabled={!enabled}
      onClick={onClick}
    >
      <Icon name={icon} size={iconSize} />
      {children}
    </button>
  );
});
