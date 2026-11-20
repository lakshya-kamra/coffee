/**
 * The running timer (v4 §3.5) in its three layouts, chosen by the window and recomputed live:
 *
 * - portrait: today's phone screen, but the ring never shrinks below the phone size, the step card
 *   grows with its words, and the transport is sticky at the bottom so Pause is always reachable.
 * - stage: wide landscape. The ring and a big clock fill the left; the right column holds the
 *   current step, the full step list and the transport. Readable from a metre away.
 * - short: height under 480. Two columns and no scroll container: the ring left, the step and
 *   the transport right, and a 4 px progress strip along the top edge.
 *
 * Only discrete engine changes render React; the clock, water readout, countdown, ring, journey
 * line and progress strip are frame-loop leaves (rule 4).
 */
import { m, type MotionValue } from "motion/react";
import { memo, useEffect, useMemo, useRef, type ReactNode, type Ref } from "react";
import { prefersReducedMotion } from "@/app/transitionState";
import { catalog } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { HairLine, Icon, IconWell, PrimaryButton, StatusLine, Term, toCaps, tooltipProps } from "@/design/components";
import { decel, spatialDefault } from "@/design/motion";
import { brewStyle } from "@/domain/core/brewer";
import { clock, formatDuration } from "@/domain/core/format";
import { findTerm, termForStepKind } from "@/domain/core/glossary";
import { stepBoundaries, totalSec, waterAt, waterAtF, type Recipe, type RecipeStep } from "@/domain/core/recipe";
import { renderTemps } from "@/domain/core/units";
import { BrewRing, JourneyLine, journeyNodes, useFrame, writeText, type BrewEngineView, type JourneyNode } from "@/viz";
import { stepLead } from "./announce";
import type { BrewEngine, EngineState } from "./engine";
import type { RunLayout } from "./layout";
import b from "./brew.module.css";

/** A step-change cue: the flash and the banner run once per serial. */
export interface StepCueEvent {
  readonly serial: number;
  readonly text: string;
}

export interface RunningProps {
  recipe: Recipe;
  engine: BrewEngine;
  st: EngineState;
  seek: MotionValue<number>;
  layout: RunLayout;
  finishing: boolean;
  /** Auto-advance is off and the clock has passed the step: the card waits for a tap. */
  waitingForTap: boolean;
  hasScale: boolean;
  fahrenheit: boolean;
  /** No brews yet: the glossary line under each step kind's first step (§4.6). */
  firstBrew: boolean;
  /** experience "new": the "?" on each term's first appearance (§4.5). */
  experienceNew: boolean;
  cue: StepCueEvent | null;
  onClose: () => void;
  onFinish: () => void;
  onToggle: () => void;
  onSeek: (deltaSec: number) => void;
  onSkip: () => void;
  onAdvance: () => void;
  onBed: () => void;
}

/** "about 60 ml": a jug is read to the nearest 10 ml. */
const aboutMl = (g: number) => Math.max(0, Math.round(g / 10) * 10);

export const RunningPane = memo(function RunningPane(p: RunningProps) {
  const { recipe, engine, st, seek, layout } = p;
  const total = totalSec(recipe);
  const step = recipe.steps[st.stepIndex];
  const waterG = Math.max(1, recipe.waterG);
  // One whole-brew timebase for the ring, the journey line and the strip, so the drawings of
  // progress never disagree with each other or with the clock.
  const view: BrewEngineView = useMemo(
    () => ({
      elapsedMs: () => engine.elapsedMs(),
      totalMs: () => Math.max(1, total) * 1000,
      waterFractionAt: (ms: number) => waterAtF(recipe, ms) / waterG,
    }),
    [engine, recipe, total, waterG],
  );
  const trip = useMemo(() => () => (engine.elapsedMs() + seek.get()) / (Math.max(1, total) * 1000), [engine, seek, total]);
  const playRef = useRef<HTMLButtonElement>(null);
  const drawdown = step?.kind === "Drawdown";
  const bedOpen = drawdown && st.bedExposedSec == null;

  // Start replaced the Start button under the focus: hand it to Pause, the control that matters now.
  useEffect(() => {
    const a = document.activeElement;
    if (!a || a === document.body) playRef.current?.focus({ preventScroll: true });
  }, []);

  const top = <TopRow recipe={recipe} index={st.stepIndex} onClose={p.onClose} />;
  const ring = <Ring {...p} view={view} total={total} step={step} />;
  const card = (
    <StepCard
      recipe={recipe}
      index={st.stepIndex}
      layout={layout}
      hasScale={p.hasScale}
      fahrenheit={p.fahrenheit}
      firstBrew={p.firstBrew}
      experienceNew={p.experienceNew}
      waitingForTap={p.waitingForTap}
      onAdvance={p.onAdvance}
      bedHelp={bedOpen}
    />
  );
  const transport = (
    <Transport
      layout={layout}
      running={st.running}
      done={st.done}
      finishing={p.finishing}
      // Stage and short swap Next step for Bed exposed during a drawdown (§3.5).
      bed={layout !== "portrait" && bedOpen}
      canSkip={st.stepIndex + 1 < recipe.steps.length}
      playRef={playRef}
      onToggle={p.onToggle}
      onSeek={p.onSeek}
      onSkip={p.onSkip}
      onBed={p.onBed}
      onFinish={p.onFinish}
    />
  );
  const finish = <FinishButton layout={layout} done={st.done} finishing={p.finishing} onFinish={p.onFinish} />;
  const status = <BrewStatus st={st} step={step} engine={engine} finishing={p.finishing} />;
  const bedMarked = st.bedExposedSec != null ? <StatusLine status="good" lead="Bed exposed" rest={`at ${clock(st.bedExposedSec)}`} /> : null;

  return (
    <div className={b.run} data-layout={layout}>
      {layout === "portrait" && (
        <div className={`column ${b.portrait}`}>
          {top}
          {status}
          {ring}
          <Journey recipe={recipe} st={st} total={total} trip={trip} />
          <OverrunLeaf engine={engine} totalSec={total} />
          {card}
          {(drawdown || st.done) && (
            <div className={b.bedRow}>
              {st.bedExposedSec == null ? (
                <button type="button" className={`t-label-l ${b.bedBtn}`} onClick={p.onBed} {...tooltipProps("Bed exposed", "x")}>
                  Bed exposed
                </button>
              ) : (
                bedMarked
              )}
            </div>
          )}
          <div className={b.dock}>
            {transport}
            {finish}
          </div>
        </div>
      )}
      {layout === "stage" && (
        <div className={b.stage}>
          <div className={b.stageLeft}>
            {ring}
            <div className={b.stageJourney}>
              <Journey recipe={recipe} st={st} total={total} trip={trip} />
              <OverrunLeaf engine={engine} totalSec={total} />
            </div>
          </div>
          <div className={b.stageRight}>
            {top}
            {status}
            {card}
            {bedMarked}
            <StepList recipe={recipe} index={st.stepIndex} hasScale={p.hasScale} />
            <div className={b.stageDock}>
              {transport}
              {finish}
            </div>
          </div>
        </div>
      )}
      {layout === "short" && (
        <>
          <ProgressStrip trip={trip} />
          <div className={b.short}>
            <div className={b.shortLeft}>{ring}</div>
            <div className={b.shortRight}>
              {top}
              {card}
              <NextLine recipe={recipe} index={st.stepIndex} hasScale={p.hasScale} bedAt={st.bedExposedSec} />
              {transport}
            </div>
          </div>
        </>
      )}
      <StepCue cue={p.cue} />
    </div>
  );
});

// ------------------------------------------------------------------------ pieces

function TopRow({ recipe, index, onClose }: { recipe: Recipe; index: number; onClose: () => void }) {
  return (
    <div className={b.runTop}>
      <IconWell icon="Close" label="Leave this brew" keys="escape" onClick={onClose} />
      {/* The screen's one h1 (the shell focuses it): which brew this is. */}
      <h1 className={`t-title-s ink-mid ${b.runTitle}`}>{recipe.name}</h1>
      <span className={`t-body-s ink-low ${b.stepCount}`}>
        Step {index + 1} of {recipe.steps.length}
      </span>
    </div>
  );
}

function Ring(p: RunningProps & { view: BrewEngineView; total: number; step: RecipeStep | undefined }) {
  const { recipe, engine, st, seek, layout, total, step } = p;
  const boundaries = useMemo(() => stepBoundaries(recipe), [recipe]);
  const pouring = step?.kind === "Pour" || step?.kind === "Bloom";
  const longTimes = total >= 3600;
  const paused = layout === "short" && !st.running && !st.done ? "PAUSED · " : "";
  return (
    <div className={b.ringBox}>
      <BrewRing
        engine={p.view}
        accent="var(--crema-stroke)"
        boundaries={boundaries}
        seek={seek}
        pouring={pouring}
        running={st.running}
        trackColor="var(--ink-faint)"
        liquidColor="var(--ring-liquid)"
        strokeFraction={layout === "stage" ? 0.028 : 0.035}
        headCore="var(--bg)"
        tickColor="var(--ink-low)"
        label={`Brew progress, ${formatDuration(total)} planned`}
      >
        <div className={b.ringInner}>
          <ClockLeaf engine={engine} long={total >= 600} />
          {/* Names the clock's frame: the ring and the clock are both the whole brew. */}
          <span className={`micro ink-low ${b.ofLabel}`}>
            {paused}OF {longTimes ? formatDuration(total).toUpperCase() : clock(total)}
          </span>
          <Readout engine={engine} recipe={recipe} step={step} hasScale={p.hasScale} />
        </div>
      </BrewRing>
    </div>
  );
}

function Journey({ recipe, st, total, trip }: { recipe: Recipe; st: EngineState; total: number; trip: () => number }) {
  const nodes = useMemo<JourneyNode[]>(
    () => [...journeyNodes(recipe), ...(st.bedExposedSec != null ? [{ fraction: Math.min(1, Math.max(0, st.bedExposedSec / Math.max(1, total))), kind: "actual" as const, status: "good" as const }] : [])],
    [recipe, st.bedExposedSec, total],
  );
  return <JourneyLine nodes={nodes} style="live" progress={trip} startLabel="0:00" endLabel={clock(total)} label={`Brew progress, ${clock(total)} planned`} />;
}

/** Poured / target: grams with a scale; "about 60 ml / 500" or "Pour all 500 ml" without (§4.3). */
function Readout({ engine, recipe, step, hasScale }: { engine: BrewEngine; recipe: Recipe; step: RecipeStep | undefined; hasScale: boolean }) {
  const target = step?.targetWaterG ?? Math.round(recipe.waterG);
  if (!hasScale && step?.targetWaterG != null && step.targetWaterG >= recipe.waterG && brewStyle(recipe.brewer) === "immersion") {
    return (
      <span className={b.water}>
        <span className={b.waterNow}>Pour all {Math.round(recipe.waterG)} ml</span>
      </span>
    );
  }
  const text = hasScale ? () => `${waterAt(recipe, engine.elapsedMs())} g` : () => `about ${aboutMl(waterAt(recipe, engine.elapsedMs()))} ml`;
  return (
    <span className={b.water}>
      <Leaf className={`${b.waterNow}`} initial={text()} update={(el) => writeText(el, text())} />
      <span className={`ink-low ${b.waterTarget}`}>/ {target}</span>
    </span>
  );
}

function StepCard({
  recipe,
  index,
  layout,
  hasScale,
  fahrenheit,
  firstBrew,
  experienceNew,
  waitingForTap,
  onAdvance,
  bedHelp,
}: {
  recipe: Recipe;
  index: number;
  layout: RunLayout;
  hasScale: boolean;
  fahrenheit: boolean;
  firstBrew: boolean;
  experienceNew: boolean;
  waitingForTap: boolean;
  onAdvance: () => void;
  bedHelp: boolean;
}) {
  const glossaryReady = useCatalog(["glossary"]);
  const s = recipe.steps[index];
  if (!s) return null;
  const next = recipe.steps[index + 1];
  const termId = termForStepKind(s.kind);
  const firstOfKind = recipe.steps.findIndex((x) => x.kind === s.kind) === index;
  const short = firstBrew && firstOfKind && termId && glossaryReady ? (findTerm(catalog.glossary, termId)?.short ?? null) : null;
  const before = recipe.steps.slice(0, index).reduce<number>((acc, x) => (x.targetWaterG != null ? x.targetWaterG : acc), 0);
  const jugMl = !hasScale && s.targetWaterG != null && s.targetWaterG > before ? s.targetWaterG - before : null;
  return (
    <section className={b.stepCard} aria-label="Current step">
      <m.div
        key={index}
        className={b.stepBody}
        initial={{ opacity: 0, transform: "translateY(12px)" }}
        animate={{ opacity: 1, transform: "translateY(0px)", transition: { opacity: decel(260), transform: spatialDefault } }}
      >
        <div className={b.stepHead}>
          {termId ? (
            <Term id={termId} chip hint={experienceNew && firstOfKind} className={b.kindChip}>
              {toCaps(s.kind)}
            </Term>
          ) : (
            <span className={`micro ${b.kindPill}`}>{toCaps(s.kind)}</span>
          )}
          <span className={b.grow} />
          <span className="t-body-s ink-low">
            {clock(s.startSec)}–{clock(s.endSec)}
          </span>
        </div>
        <h2 className={`step-title ${b.stepTitle}`}>{s.title}</h2>
        {jugMl != null && <p className="t-body-m">About {aboutMl(jugMl)} ml from your jug</p>}
        {s.detail && <p className={`${layout === "stage" ? "t-body-l" : "t-body-m"} ink-mid`}>{renderTemps(s.detail, fahrenheit ? "f" : "c")}</p>}
        {short && <p className={`t-body-s ${b.glossLine}`}>{short}</p>}
        {bedHelp && (
          <p className="t-body-s ink-mid">
            <Term id="bed">Bed exposed</Term>: tap when you first see the coffee bed through the water.
          </p>
        )}
        {layout === "portrait" && next && (
          <div className={b.next}>
            <HairLine />
            <span className="micro ink-low">NEXT · {clock(next.startSec)}</span>
            <span className="t-body-m ink-mid">{next.title}</span>
          </div>
        )}
      </m.div>
      {waitingForTap && (
        <button type="button" className={`${b.nextTap} t-label-l`} onClick={onAdvance} {...tooltipProps("Next step", "n")}>
          Next step
        </button>
      )}
    </section>
  );
}

/** Stage: every step as a vertical timeline; the current one lifted, the past ones marked Done. */
function StepList({ recipe, index, hasScale }: { recipe: Recipe; index: number; hasScale: boolean }) {
  const list = useRef<HTMLOListElement>(null);
  // Keep the current step in view inside the list only (scrollIntoView could move the page).
  useEffect(() => {
    const el = list.current;
    const li = el?.querySelector<HTMLElement>('[aria-current="step"]');
    if (!el || !li) return;
    const top = li.offsetTop;
    if (top < el.scrollTop || top + li.offsetHeight > el.scrollTop + el.clientHeight) el.scrollTo({ top: Math.max(0, top - 8) });
  }, [index]);
  return (
    <ol ref={list} className={b.stepList} aria-label="All steps">
      {recipe.steps.map((s, i) => {
        const state = i < index ? "done" : i === index ? "current" : "next";
        const amount = s.targetWaterG == null ? "" : hasScale ? `${s.targetWaterG} g` : `about ${aboutMl(s.targetWaterG)} ml`;
        return (
          <li key={i} className={b.listItem} data-state={state} aria-current={state === "current" ? "step" : undefined}>
            <span className={`num-inline ${b.listTime}`}>{clock(s.startSec)}</span>
            <span className={`t-title-m ${b.listTitle}`}>{s.title}</span>
            <span className="t-body-s ink-low">{state === "done" ? "Done" : amount}</span>
          </li>
        );
      })}
    </ol>
  );
}

/** Short: "NEXT · 0:45 · Up to 300 g" under the step card. */
function NextLine({ recipe, index, hasScale, bedAt }: { recipe: Recipe; index: number; hasScale: boolean; bedAt: number | null }) {
  const next = recipe.steps[index + 1];
  if (!next) {
    return bedAt != null ? <p className={`micro ${b.nextLine}`}>BED EXPOSED · {clock(bedAt)}</p> : <p className={`micro ink-low ${b.nextLine}`}>LAST STEP</p>;
  }
  const what = next.targetWaterG != null ? (hasScale ? `Up to ${next.targetWaterG} g` : `Up to about ${aboutMl(next.targetWaterG)} ml`) : next.title;
  return (
    <p className={`micro ink-low ${b.nextLine}`}>
      NEXT · {clock(next.startSec)} · {toCaps(what)}
    </p>
  );
}

function Transport({
  layout,
  running,
  done,
  finishing,
  bed,
  canSkip,
  playRef,
  onToggle,
  onSeek,
  onSkip,
  onBed,
  onFinish,
}: {
  layout: RunLayout;
  running: boolean;
  done: boolean;
  finishing: boolean;
  bed: boolean;
  canSkip: boolean;
  playRef: Ref<HTMLButtonElement>;
  onToggle: () => void;
  onSeek: (d: number) => void;
  onSkip: () => void;
  onBed: () => void;
  onFinish: () => void;
}) {
  const wellSize = layout === "short" ? 44 : 48;
  return (
    <div className={b.transport} data-layout={layout}>
      {/* The names keep the visible "5 s" so voice control can say what it sees (WCAG 2.5.3). */}
      <button type="button" className={`t-label-l ${b.seekBtn}`} aria-label="Back 5 s" {...tooltipProps("Back 5 seconds", "left")} onClick={() => onSeek(-5)}>
        −5 s
      </button>
      <PlayPause running={running} onClick={onToggle} buttonRef={playRef} />
      <button type="button" className={`t-label-l ${b.seekBtn}`} aria-label="Forward 5 s" {...tooltipProps("Forward 5 seconds", "right")} onClick={() => onSeek(5)}>
        +5 s
      </button>
      {layout === "portrait" && <span className={b.grow} />}
      {bed ? (
        <button type="button" className={`t-label-l ${b.bedPill}`} onClick={onBed} {...tooltipProps("Bed exposed", "x")}>
          Bed exposed
        </button>
      ) : (
        <IconWell icon="SkipNext" label="Next step" keys="n" onClick={onSkip} size={wellSize} enabled={canSkip} />
      )}
      {layout === "short" && (
        <button
          type="button"
          className={`t-label-l ${b.finishPill}`}
          data-done={done || undefined}
          disabled={finishing}
          onClick={onFinish}
          {...tooltipProps(done ? "Rate this brew" : "Finish now", "f")}
        >
          {done ? "Rate it" : "Finish"}
        </button>
      )}
    </div>
  );
}

function FinishButton({ layout, done, finishing, onFinish }: { layout: RunLayout; done: boolean; finishing: boolean; onFinish: () => void }) {
  if (layout === "short") return null;
  // The finish button turns crema when the brew completes, in step with the mesh.
  return (
    <PrimaryButton
      text={done ? "Rate this brew" : "Finish early"}
      icon="Check"
      height={layout === "stage" ? 48 : 52}
      onClick={onFinish}
      enabled={!finishing}
      className={done ? b.finishDone : b.finishEarly}
    />
  );
}

function PlayPause({ running, onClick, buttonRef }: { running: boolean; onClick: () => void; buttonRef: Ref<HTMLButtonElement> }) {
  const label = running ? "Pause" : "Resume";
  return (
    <button ref={buttonRef} type="button" className={b.play} onClick={onClick} aria-label={label} {...tooltipProps(label, "space")}>
      <m.span
        key={running ? "pause" : "play"}
        className={b.playIcon}
        initial={{ opacity: 0, transform: "scale(0.6)" }}
        animate={{ opacity: 1, transform: "scale(1)" }}
        transition={{ opacity: decel(140), transform: decel(220) }}
      >
        <Icon name={running ? "Pause" : "Play"} size={30} />
      </m.span>
    </button>
  );
}

function BrewStatus({ st, step, engine, finishing }: { st: EngineState; step: RecipeStep | undefined; engine: BrewEngine; finishing: boolean }) {
  const pausedAt = clock(Math.floor(st.accumulatedMs / 1000));
  if (finishing) return <StatusLine status="neutral" lead="Saving" rest={`at ${pausedAt}`} />;
  if (st.done) return <StatusLine status="good" lead="Done" rest="rate this brew" />;
  if (!st.running) return <StatusLine status="attention" lead="Paused" rest={`at ${pausedAt}`} />;
  if (!step) return <StatusLine status="live" lead="Brewing" />;
  return (
    <div className={b.statusRow}>
      <StatusLine status="live" lead={stepLead(step)} />
      <CountdownLeaf engine={engine} endSec={step.endSec} />
    </div>
  );
}

/**
 * The step-change cue (§3.5): a full-window crema flash (opacity 0 → 0.16 → 0 over 600 ms) and a
 * banner for 3 s. Web Animations on opacity and transform only, so both stay on the compositor;
 * reduced motion keeps the banner as a fade and drops the flash. Screen readers get the live
 * region instead, so both are aria-hidden.
 */
function StepCue({ cue }: { cue: StepCueEvent | null }) {
  const flash = useRef<HTMLDivElement>(null);
  const banner = useRef<HTMLDivElement>(null);
  const serial = cue?.serial ?? 0;
  useEffect(() => {
    if (serial === 0) return;
    const reduced = prefersReducedMotion();
    const runs: Animation[] = [];
    if (!reduced && flash.current) runs.push(flash.current.animate([{ opacity: 0 }, { opacity: 0.16, offset: 0.3 }, { opacity: 0 }], { duration: 600, easing: "ease-out" }));
    if (banner.current) {
      const frames = reduced
        ? [{ opacity: 0 }, { opacity: 1, offset: 0.06 }, { opacity: 1, offset: 0.92 }, { opacity: 0 }]
        : [
            { opacity: 0, transform: "translate(-50%, -16px)" },
            { opacity: 1, transform: "translate(-50%, 0px)", offset: 0.08 },
            { opacity: 1, transform: "translate(-50%, 0px)", offset: 0.92 },
            { opacity: 0, transform: "translate(-50%, -8px)" },
          ];
      runs.push(banner.current.animate(frames, { duration: 3000, easing: "cubic-bezier(0.2, 0, 0, 1)" }));
    }
    return () => runs.forEach((a) => a.cancel());
  }, [serial]);
  return (
    <>
      <div ref={flash} className={b.flash} aria-hidden="true" />
      <div ref={banner} className={`t-label-l ${b.banner}`} aria-hidden="true">
        {cue?.text ?? ""}
      </div>
    </>
  );
}

/** Short: the journey line becomes a 4 px strip along the top edge (scaleX from the frame loop). */
function ProgressStrip({ trip }: { trip: () => number }) {
  const bar = useRef<HTMLDivElement>(null);
  useFrame(() => {
    const el = bar.current;
    if (!el) return;
    const v = `scaleX(${Math.min(1, Math.max(0, trip())).toFixed(4)})`;
    if (el.style.transform !== v) el.style.transform = v;
  });
  return (
    <div className={b.strip} aria-hidden="true">
      <div ref={bar} className={b.stripBar} />
    </div>
  );
}

// ------------------------------------------------------------------ timer leaves
// Each writes textContent from the frame loop, so the digits change once a second (the water
// readout once a gram) and nothing above them ever re-renders with the clock.

function Leaf({ className, initial, update, role }: { className: string; initial: string; update: (el: HTMLElement) => void; role?: string }): ReactNode {
  const ref = useRef<HTMLSpanElement>(null);
  useFrame(() => {
    if (ref.current) update(ref.current);
  });
  return (
    <span ref={ref} className={className} role={role} aria-live={role === "timer" ? "off" : undefined}>
      {initial}
    </span>
  );
}

function ClockLeaf({ engine, long }: { engine: BrewEngine; long: boolean }) {
  const text = () => clock(Math.floor(engine.elapsedMs() / 1000));
  return <Leaf className={`${long ? "num-clock-compact" : "num-clock"} ${b.clock}`} initial={text()} update={(el) => writeText(el, text())} role="timer" />;
}

function CountdownLeaf({ engine, endSec }: { engine: BrewEngine; endSec: number }) {
  const text = () => `· ${clock(Math.max(0, endSec - Math.floor(engine.elapsedMs() / 1000)))} left`;
  return <Leaf className={`t-body-m ink-mid ${b.countdown}`} initial={text()} update={(el) => writeText(el, text())} />;
}

function OverrunLeaf({ engine, totalSec: total }: { engine: BrewEngine; totalSec: number }) {
  const text = () => {
    const s = Math.floor(engine.elapsedMs() / 1000);
    return s > total ? `+${clock(s - total)}` : "";
  };
  return <Leaf className={`micro ${b.overrun}`} initial={text()} update={(el) => writeText(el, text())} />;
}
