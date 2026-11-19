import { memo } from "react";
import { ICONS, type IconName } from "../icons";
import s from "./components.module.css";

/** A Material Symbols Rounded glyph. Decorative unless `label` is given. */
export const Icon = memo(function Icon({ name, size = 24, label, className }: { name: IconName; size?: number; label?: string; className?: string }) {
  return (
    <svg
      className={className ? `${s.icon} ${className}` : s.icon}
      width={size}
      height={size}
      viewBox="0 -960 960 960"
      aria-hidden={label ? undefined : true}
      role={label ? "img" : undefined}
      aria-label={label}
      focusable="false"
    >
      <path d={ICONS[name]} />
    </svg>
  );
});
