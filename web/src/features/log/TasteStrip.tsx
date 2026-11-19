/**
 * Port of TasteStrip.kt: seven monochrome columns, one per taste axis. Taste is data, not status,
 * so there is no colour. Each fill grows once from 0, staggered, on the first strip of the session
 * only (rule 7); it is a CSS keyframe on transform, so React never renders per frame.
 */
import { useEffect, useState, type CSSProperties } from "react";
import { TASTE_AXES, type TasteProfile } from "@/domain/core/evaluation";
import l from "./log.module.css";

/** Short labels in TASTE_AXES order; seven columns leave no room for full words. */
const AXIS_SHORT = ["ACID", "SWEET", "BITTER", "BODY", "BAL", "AFTER", "CLAR"];

let played = false;

export function TasteStrip({ taste }: { taste: TasteProfile }) {
  const [animate] = useState(() => !played);
  useEffect(() => {
    played = true;
  }, []);
  return (
    <div className={l.taste} role="img" aria-label={TASTE_AXES.map((a) => `${a.label} ${taste[a.key]} of 10`).join(", ")}>
      {TASTE_AXES.map((a, i) => {
        const v = Math.min(10, Math.max(0, taste[a.key]));
        const style: CSSProperties = { transform: `scaleY(${v / 10})`, animationDelay: `${i * 50}ms` };
        return (
          <div key={a.key} className={l.tasteCol} aria-hidden="true">
            <span className={l.tasteValue}>{taste[a.key]}</span>
            <span className={l.tasteTrack}>
              <span className={l.tasteFill} style={style} data-animate={animate} />
            </span>
            <span className={`micro ink-low ${l.tasteLabel}`}>{AXIS_SHORT[i] ?? a.label.slice(0, 5).toUpperCase()}</span>
          </div>
        );
      })}
    </div>
  );
}
