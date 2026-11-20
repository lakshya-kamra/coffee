/** Keys as the platform prints them: <Kbd keys="mod+k" /> is "Ctrl K" or "⌘K" (v4 §2.7). */
import { memo } from "react";
import { keyParts } from "../keys";
import s from "./components.module.css";

export const Kbd = memo(function Kbd({ keys, className }: { keys: string; className?: string }) {
  if (/^\d-\d$/.test(keys)) {
    return (
      <span className={`${s.kbd} ${className ?? ""}`}>
        <kbd className={s.kbdKey}>{keys}</kbd>
      </span>
    );
  }
  const steps = keys.trim().split(/\s+/);
  return (
    <span className={`${s.kbd} ${className ?? ""}`}>
      {steps.map((step, i) => (
        <span key={i} className={s.kbd}>
          {i > 0 && <span className={s.kbdThen}>then</span>}
          <kbd className={s.kbd}>
            {keyParts(step).map((p, j) => (
              <kbd key={j} className={s.kbdKey}>
                {p}
              </kbd>
            ))}
          </kbd>
        </span>
      ))}
    </span>
  );
});
