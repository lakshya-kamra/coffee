/** Small pieces shared by the brew panes. */
import { IconWell, tooltipProps } from "@/design/components";
import b from "./brew.module.css";

// Material Symbols Rounded "Remove" (Apache-2.0, web/NOTICE): the design icon set has no minus.
const MINUS = "M240-440q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h480q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H240Z";

/** − / + wells for the setup steppers; both carry a tooltip with their name (A10). */
export function Stepper({ dir, label, onClick }: { dir: -1 | 1; label: string; onClick: () => void }) {
  if (dir === 1) return <IconWell icon="Add" label={label} onClick={onClick} size={44} onCanvas={false} />;
  return (
    <button type="button" className={b.minus} aria-label={label} {...tooltipProps(label)} onClick={onClick}>
      <svg width={20} height={20} viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
        <path d={MINUS} fill="currentColor" />
      </svg>
    </button>
  );
}
