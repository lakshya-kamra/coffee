/**
 * Espresso shot mode (v4 §4.9): one big count-up seconds number (the hero), the target band from
 * the recipe, and one button, "Start shot" then "Stop shot" (Space). Once stopped: "How much came
 * out?" (the yield in grams, prefilled with the recipe's target) and "Rate it". Pre-infusion shows
 * as a label during its window when the recipe has one. The seconds are a frame-loop leaf.
 */
import { useEffect, useMemo, useRef } from "react";
import { FieldWell, IconWell, PrimaryButton, QuietButton, StatusLine, tooltipProps } from "@/design/components";
import type { Status } from "@/domain/core/status";
import { clock, grams, ratio } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { useFrame, writeText } from "@/viz";
import type { RunLayout } from "./layout";
import { bandStatus, preinfusion, shotBand, shotSeconds, shotYield, type BandStatus, type ShotState } from "./shot";
import b from "./brew.module.css";

const BAND_WORDS: Record<BandStatus, { status: Status; lead: string }> = {
  under: { status: "attention", lead: "Fast" },
  in: { status: "good", lead: "In the band" },
  over: { status: "attention", lead: "Slow" },
};

export function ShotPane({
  recipe,
  shot,
  layout,
  finishing,
  onToggle,
  onAgain,
  onYield,
  onRate,
  onClose,
}: {
  recipe: Recipe;
  shot: ShotState;
  layout: RunLayout;
  finishing: boolean;
  onToggle: () => void;
  onAgain: () => void;
  onYield: (text: string) => void;
  onRate: () => void;
  onClose: () => void;
}) {
  const band = useMemo(() => shotBand(recipe), [recipe]);
  const pre = useMemo(() => preinfusion(recipe), [recipe]);
  const button = useRef<HTMLButtonElement>(null);
  const pulling = shot.phase === "pulling";
  const stopped = shot.phase === "stopped";
  const yieldG = shotYield(shot);

  useEffect(() => {
    const a = document.activeElement;
    if (!a || a === document.body) button.current?.focus({ preventScroll: true });
  }, []);

  const verdict = stopped && shot.shotSec != null ? BAND_WORDS[bandStatus(shot.shotSec, band)] : null;

  return (
    <div className={b.run} data-layout={layout}>
      <div className={b.shot} data-layout={layout}>
        <div className={b.runTop}>
          <IconWell icon="Close" label={pulling || stopped ? "Leave this shot" : "Back to set up"} keys="escape" onClick={onClose} />
          <h1 className={`t-title-s ink-mid ${b.runTitle}`}>{recipe.name}</h1>
          <span className={`t-body-s ink-low ${b.stepCount}`}>Shot</span>
        </div>

        <div className={b.shotHero}>
          <span className="micro ink-low">SHOT TIME</span>
          <span className={b.shotNumber} data-live={pulling || undefined}>
            <SecondsLeaf shot={shot} />
            <span className={b.shotUnit}>s</span>
          </span>
          <span className="t-body-m ink-mid">
            Target {band.label} · {grams(recipe.doseG)} g in, {grams(recipe.waterG)} g out ({ratio(recipe.doseG, recipe.waterG)})
          </span>
          {pre && pulling && <PreinfusionLeaf shot={shot} endSec={pre.endSec} />}
          {verdict && <StatusLine status={verdict.status} lead={verdict.lead} rest={`${shot.shotSec} s against ${band.label}`} />}
        </div>

        <div className={b.shotControls}>
          {!stopped ? (
            <button
              ref={button}
              type="button"
              className={`t-title-l ${b.shotButton}`}
              data-live={pulling || undefined}
              onClick={onToggle}
              {...tooltipProps(pulling ? "Stop shot" : "Start shot", "space")}
            >
              {pulling ? "Stop shot" : "Start shot"}
            </button>
          ) : (
            <>
              <FieldWell label="HOW MUCH CAME OUT?" value={shot.yieldText} suffix="g" numeric helper={`The recipe aims for ${grams(recipe.waterG)} g in the cup.`} error={shot.yieldText.trim() && yieldG == null ? "Type the grams, like 36" : null} onChange={onYield} />
              <PrimaryButton text="Rate it" icon="Check" onClick={onRate} enabled={!finishing} className={b.finishDone} />
              <QuietButton text="Pull it again" icon="Replay" onClick={onAgain} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function SecondsLeaf({ shot }: { shot: ShotState }) {
  const ref = useRef<HTMLSpanElement>(null);
  useFrame(() => writeText(ref.current, String(shotSeconds(shot, performance.now()))), shot.phase === "pulling");
  // The frame loop fills in the live seconds; the first paint shows where the shot stands.
  return (
    <span ref={ref} className={b.shotDigits} role="timer" aria-live="off">
      {String(shot.phase === "stopped" ? (shot.shotSec ?? 0) : 0)}
    </span>
  );
}

/** "Pre-infusion · 0:08": time left in the window, gone once the pump is at full pressure. */
function PreinfusionLeaf({ shot, endSec }: { shot: ShotState; endSec: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  useFrame(() => {
    const left = endSec - shotSeconds(shot, performance.now());
    writeText(ref.current, left > 0 ? `Pre-infusion · ${clock(left)}` : "");
  });
  return (
    <span ref={ref} className={`t-title-m ${b.preLabel}`}>
      {`Pre-infusion · ${clock(endSec)}`}
    </span>
  );
}
