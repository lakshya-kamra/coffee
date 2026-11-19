/**
 * Port of BrewScreen.kt + BrewViewModel.kt (web-spec §11 Brew, §12). Setup pane (dose, grind,
 * temperature, coffee, prep) over a calm mesh; Start hands over to the running pane: the mesh by
 * mood, the canvas ring with the clock, the live journey line, the step card and the transport.
 *
 * React renders only on discrete engine changes (start, pause, step, bed exposed, seek, done).
 * The clock digits, countdown, water readout, overrun, ring and journey line are leaves fed by
 * the shared frame loop (rule 4). A reload mid-brew resumes from kv activeBrew (§12.5).
 */
import { animate } from "motion";
import { AnimatePresence, m, useMotionValue, type MotionValue } from "motion/react";
import { memo, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { nav } from "@/app/router";
import { CoffeeMug, Dripper } from "@/art";
import { repo, type ActiveBrew } from "@/data/repo";
import type { BeanRow, BrewRow, GrinderRow } from "@/data/schema";
import { grinderDisplay } from "@/data/schema";
import { settings as settingsStore, type Settings } from "@/data/settings";
import { persistOnce } from "@/data/storage";
import { getSnapshot, store, useRecipe, useSettings } from "@/data/store";
import {
  DetailTopBar,
  EyebrowLabel,
  FieldWell,
  HairLine,
  Icon,
  IconWell,
  PillButton,
  PrimaryButton,
  QuietButton,
  RadioRow,
  SecondaryButton,
  SelectableChip,
  SoftCard,
  StatusLine,
  TickingNumber,
  toCaps,
} from "@/design/components";
import { decel, effectsDefault, settle, spatialDefault } from "@/design/motion";
import { brewerArt, brewerShortName } from "@/domain/core/brewer";
import { clock, epochDayOf, grams, ratio, timeLabel } from "@/domain/core/format";
import { scaledToDose, stepBoundaries, totalSec, waterAt, waterAtF, type Recipe, type RecipeStep } from "@/domain/core/recipe";
import { roastStatus, roastWord, scoreStatus } from "@/domain/core/status";
import { temp, tempUnit, unitsOf } from "@/domain/core/units";
import { describe, format as formatGrind } from "@/domain/grind";
import { DialReadingInput, GrindStartHint, useGrindScale } from "@/features/grind";
import { BrewRing, ConfirmSheet, JourneyLine, MeshGradient, journeyNodes, stepMood, useFrame, writeText, type BrewEngineView, type BrewMood, type JourneyNode } from "@/viz";
import { CuePlayer, cueOffsetsSec, vibrateStep } from "./cues";
import { BrewEngine, type EngineState } from "./engine";
import { buildBrewRow, grindPreference, initialSetup, minClicks, prefillGrind, usesClicks, type Setup } from "./setup";
import { ScreenLock, wakeLockSupported } from "./wakeLock";
import b from "./brew.module.css";

/** Brewers that actually look like a cone with a bed in it. */
const POUR_OVER = new Set(["V60", "Chemex", "Kalita", "Switch"]);
/** How much of the setup mesh settles into the canvas behind the sticky Start bar. */
const SETUP_FLOOR = 112;

// Material Symbols Rounded "Remove" (Apache-2.0, web/NOTICE): the design icon set has no minus.
const MINUS = "M240-440q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h480q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H240Z";

function Stepper({ dir, label, onClick }: { dir: -1 | 1; label: string; onClick: () => void }) {
  if (dir === 1) return <IconWell icon="Add" label={label} onClick={onClick} size={44} onCanvas={false} />;
  return (
    <button type="button" className={b.minus} aria-label={label} title={label} onClick={onClick}>
      <svg width={20} height={20} viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
        <path d={MINUS} fill="currentColor" />
      </svg>
    </button>
  );
}

// ---------------------------------------------------------------------- screen

export function BrewScreen({ recipeId }: { recipeId: string }) {
  const base = useRecipe(recipeId);
  // Read once: a running brew is not re-seeded by its own kv writes.
  const [active, setActive] = useState<ActiveBrew | null>(() => getSnapshot().activeBrew);
  if (!base) {
    // A brew whose recipe was deleted can never be finished; without this it would stay "in progress" for good.
    const orphan = active != null && active.recipeId === recipeId;
    return (
      <MissingPane
        onDiscard={
          orphan
            ? () => {
                void repo.setActiveBrew(null);
                setActive(null);
              }
            : null
        }
      />
    );
  }
  if (active && active.recipeId !== recipeId) {
    const startedAt = active.startedAtWall - active.accumulatedMs;
    return (
      <>
        <BrewSession recipe={base} resume={null} />
        <ConfirmSheet
          title={`Finish the brew you started at ${timeLabel(startedAt)} first?`}
          body="It's still running. Discarding it keeps nothing from it."
          confirmText="Resume"
          cancelText="Discard"
          onClose={() => undefined}
          onDismiss={nav.back}
          onConfirm={() => nav.replace(`/brew/${encodeURIComponent(active.recipeId)}`)}
          onCancel={() => {
            void repo.setActiveBrew(null);
            setActive(null);
          }}
        />
      </>
    );
  }
  return <BrewSession recipe={base} resume={active} />;
}

/**
 * Resolves once the hot store has the new brew (its live query has run), so Review's first frame
 * finds the row instead of flashing "no longer in the log". Gives up after 2 s.
 */
function storeHasBrew(uid: string): Promise<void> {
  return new Promise((resolve) => {
    const has = () => getSnapshot().brews.some((x) => x.uid === uid);
    if (has()) return resolve();
    const timer = setTimeout(done, 2000);
    const unsub = store.subscribe(() => has() && done());
    function done() {
      clearTimeout(timer);
      unsub();
      resolve();
    }
  });
}

function MissingPane({ onDiscard }: { onDiscard: (() => void) | null }) {
  return (
    <div className={b.missing}>
      <DetailTopBar onBack={nav.back} />
      <div className={b.missingBody}>
        <CoffeeMug fill={0.08} size={72} showSteam={false} />
        <h1 className="t-headline-s">That recipe is gone.</h1>
        {onDiscard && (
          <>
            <p className={`t-body-m ink-mid ${b.center}`}>A brew you started with it is still marked as in progress.</p>
            <SecondaryButton text="Discard that brew" onClick={onDiscard} />
          </>
        )}
        <QuietButton text="Back" onClick={nav.back} />
      </div>
    </div>
  );
}

function useEngineState(engine: BrewEngine): EngineState {
  return useSyncExternalStore(
    (fn) => engine.subscribe(fn),
    () => engine.state,
    () => engine.state,
  );
}

function BrewSession({ recipe: base, resume }: { recipe: Recipe; resume: ActiveBrew | null }) {
  const settings = useSettings();
  const units = unitsOf(settings);
  // Lists read once at open, like BrewViewModel's init: the setup never reshuffles under a thumb.
  const [data] = useState(() => {
    const snap = getSnapshot();
    return {
      beans: snap.beans.filter((x) => !x.isArchived),
      grinders: snap.grinders.filter((x) => !x.isArchived),
      brews: snap.brews,
    };
  });
  const [setup, setSetup] = useState<Setup>(() => {
    if (resume) return { ...resume.setup };
    return initialSetup(base, data.beans, data.grinders, settings, data.brews);
  });
  const recipe = useMemo(() => scaledToDose(base, setup.doseG), [base, setup.doseG]);
  const [engine] = useState(() => {
    const e = new BrewEngine(scaledToDose(base, setup.doseG), { autoAdvance: settings.autoAdvanceSteps });
    if (resume) e.restore(resume);
    return e;
  });
  const st = useEngineState(engine);
  const [finishing, setFinishing] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [announce, setAnnounce] = useState("");
  const seek = useMotionValue(0);
  const cues = useRef<CuePlayer | null>(null);
  const lock = useRef<ScreenLock | null>(null);

  useEffect(() => {
    engine.setRecipe(recipe);
  }, [engine, recipe]);
  useEffect(() => {
    engine.setAutoAdvance(settings.autoAdvanceSteps);
  }, [engine, settings.autoAdvanceSteps]);

  // ------------------------------------------------------------ the clock's side effects
  // Step detection every frame while visible; a timer at the next boundary while hidden.
  useFrame(() => engine.tick(), st.started && st.running);
  useEffect(() => {
    if (!st.started || !st.running) return;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const arm = () => {
      if (timer) clearTimeout(timer);
      timer = null;
      if (!document.hidden) return;
      const ms = engine.msToNextBoundary();
      if (ms != null) timer = setTimeout(() => {
        engine.tick();
        arm();
      }, ms + 20);
    };
    const onVis = () => {
      if (!document.hidden) engine.reanchorToWall();
      engine.tick();
      arm();
    };
    const onShow = () => onVis();
    document.addEventListener("visibilitychange", onVis);
    window.addEventListener("pageshow", onShow);
    arm();
    return () => {
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVis);
      window.removeEventListener("pageshow", onShow);
    };
  }, [engine, st.started, st.running]);

  // Cues on a step change: one buzz, one announcement.
  const hapticsOn = settings.haptics;
  useEffect(
    () =>
      engine.onStep((i) => {
        if (hapticsOn) vibrateStep();
        const s = engine.getRecipe().steps[i];
        if (s) setAnnounce(stepLead(s));
      }),
    [engine, hapticsOn],
  );

  // Sound: scheduled on the audio clock, rescheduled on pause, resume and seek.
  const soundOn = settings.soundCues;
  useEffect(() => {
    if (!soundOn || !st.started) {
      cues.current?.cancel();
      return;
    }
    if (!cues.current) cues.current = new CuePlayer();
    if (st.running) cues.current.schedule(cueOffsetsSec(engine.getRecipe(), engine.elapsedMs()));
    else cues.current.cancel();
  }, [engine, soundOn, st.started, st.running, st.seekSerial]);

  // Wake lock follows running, with a minute's grace on pause.
  const keepOn = settings.keepScreenOn;
  useEffect(() => {
    if (!keepOn || !st.started) {
      lock.current?.release();
      return;
    }
    lock.current ??= new ScreenLock();
    if (st.running) lock.current.hold();
    else lock.current.paused();
  }, [keepOn, st.started, st.running]);
  useEffect(
    () => () => {
      cues.current?.close();
      lock.current?.dispose();
    },
    [],
  );

  // Surviving a reload: every discrete change of a started brew is written to kv activeBrew.
  useEffect(() => {
    if (!st.started || finishing) return;
    const snap = engine.snapshot();
    void repo.setActiveBrew({ recipeId: base.id, ...snap, setup: { ...setup } });
  }, [engine, base.id, setup, finishing, st.started, st.running, st.stepIndex, st.seekSerial, st.bedExposedSec]);

  // ------------------------------------------------------------ actions
  const seekBy = (fn: () => void) => {
    const before = engine.elapsedMs() + seek.get();
    fn();
    // The digits snap; the ring glides from where it was drawn.
    seek.set(before - engine.elapsedMs());
    animate(seek, 0, settle);
  };

  const start = () => {
    if (soundOn) (cues.current ??= new CuePlayer()).unlock();
    engine.start();
    void settingsStore.set("lastRecipeId", base.id);
  };
  const toggle = () => {
    if (!st.running && soundOn) (cues.current ??= new CuePlayer()).unlock();
    engine.toggle();
  };

  const finish = async () => {
    if (finishing) return;
    setFinishing(true);
    const result = engine.finish();
    const bean = data.beans.find((x) => x.id === setup.beanId) ?? null;
    const grinder = data.grinders.find((x) => x.id === setup.grinderId) ?? null;
    const row = buildBrewRow({ recipe, setup, bean, grinder, settings, result });
    await repo.insertBrew(row);
    await repo.setActiveBrew(null);
    await storeHasBrew(row.uid);
    void persistOnce();
    lock.current?.release();
    cues.current?.close();
    nav.replace(`/review/${encodeURIComponent(row.uid)}`);
  };

  /**
   * The one way out of a started brew that logs nothing. Without it a brew started by mistake
   * could only be "finished early" into the log, and kv activeBrew outlived the screen.
   */
  const discard = () => {
    lock.current?.release();
    cues.current?.cancel();
    // reset() ends `started`, so the effect above writes nothing after this delete.
    engine.reset();
    void repo.setActiveBrew(null);
    nav.back();
  };

  const mood: BrewMood = !st.started ? "idle" : st.done ? "done" : stepMood(recipe.steps[st.stepIndex]?.kind ?? "Prep");

  return (
    <div className={b.screen}>
      <MeshGradient mood={mood} intensity={st.started ? 0.6 : 0.3} floor={st.started ? 0 : SETUP_FLOOR} />
      <AnimatePresence initial={false}>
        {st.started ? (
          <m.div key="run" className={b.pane} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={decel(320)}>
            <RunningPane
              recipe={recipe}
              engine={engine}
              st={st}
              seek={seek}
              finishing={finishing}
              autoAdvance={settings.autoAdvanceSteps}
              onClose={() => !finishing && setLeaving(true)}
              onFinish={() => void finish()}
              onToggle={toggle}
              onSeek={(d) => seekBy(() => engine.addSeconds(d))}
              onSkip={() => seekBy(() => engine.skipToNextStep())}
            />
          </m.div>
        ) : (
          <m.div key="setup" className={b.pane} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={decel(320)}>
            <SetupPane recipe={recipe} base={base} setup={setup} setSetup={setSetup} data={data} settings={settings} unitsF={units.fahrenheit} onStart={start} />
          </m.div>
        )}
      </AnimatePresence>
      <p className="visually-hidden" aria-live="polite">
        {announce}
      </p>
      {leaving && (
        <ConfirmSheet
          title="Leave this brew?"
          body="Its timer keeps going, and Today shows the way back. Discarding keeps nothing from it."
          confirmText="Keep it going"
          cancelText="Discard"
          onClose={() => setLeaving(false)}
          onConfirm={nav.back}
          onCancel={discard}
        />
      )}
    </div>
  );
}

function stepLead(s: RecipeStep): string {
  if (s.kind === "Bloom" && s.targetWaterG != null) return `Bloom to ${s.targetWaterG} g`;
  if (s.kind === "Pour" && s.targetWaterG != null) return `Pour to ${s.targetWaterG} g`;
  if (s.kind === "Drawdown") return "Drawdown";
  return s.title;
}

// ------------------------------------------------------------------------ setup

interface SetupData {
  beans: readonly BeanRow[];
  grinders: readonly GrinderRow[];
  brews: readonly BrewRow[];
}

function SetupPane({
  recipe,
  base,
  setup,
  setSetup,
  data,
  settings,
  unitsF,
  onStart,
}: {
  recipe: Recipe;
  base: Recipe;
  setup: Setup;
  setSetup: (fn: (s: Setup) => Setup) => void;
  data: SetupData;
  settings: Settings;
  unitsF: boolean;
  onStart: () => void;
}) {
  const art = brewerArt(recipe.brewer);
  const ready = setup.doseG > 0;
  return (
    <div className={b.setupScroll}>
      <div className={`column ${b.setupColumn}`}>
        <div className={b.setupTop}>
          <IconWell icon="Close" label="Back" onClick={nav.back} />
          <EyebrowLabel text="SET UP" />
          <span className={`micro ${b.brewerPill}`}>{toCaps(recipe.brewer.label)}</span>
        </div>
        <div className={b.hero} aria-hidden="true">
          {POUR_OVER.has(art) ? <Dripper waterLevel={0.55} size={104} /> : <CoffeeMug fill={0.65} size={104} />}
        </div>
        <h1 className={`t-headline-m ${b.center}`}>{recipe.name}</h1>
        <p className={`t-body-s ink-mid ${b.center}`}>{recipe.author}</p>

        <div className={b.cards}>
          <DoseCard setup={setup} recipe={recipe} onDose={(d) => setSetup((s) => ({ ...s, doseG: Math.max(1, d) }))} />
          <GrindCard recipe={recipe} base={base} setup={setup} setSetup={setSetup} data={data} />
          <TempCard tempC={setup.tempC} fahrenheit={unitsF} onTemp={(c) => setSetup((s) => ({ ...s, tempC: Math.min(100, Math.max(60, c)) }))} />
          <CoffeeCard
            beans={data.beans}
            selected={setup.beanId}
            onSelect={(id) => {
              setSetup((s) => ({ ...s, beanId: id }));
              void settingsStore.set("defaultBeanId", id);
            }}
          />
          {recipe.prep.length > 0 && (
            <SoftCard>
              <EyebrowLabel text="BEFORE YOU START" />
              <ol className={b.prep}>
                {recipe.prep.map((line, i) => (
                  <li key={i}>
                    <span className="num-inline ink-low">{i + 1}</span>
                    <span className={b.prepText}>{line}</span>
                  </li>
                ))}
              </ol>
            </SoftCard>
          )}
          {settings.keepScreenOn && !wakeLockSupported() && <p className="t-body-s ink-low">Your browser may dim the screen during a brew.</p>}
        </div>
        <div className={b.sticky}>
          {/* Ink, not crema: nothing is live yet. Crema arrives with the running dial. */}
          <PrimaryButton text="Start brew" icon="Play" onClick={onStart} enabled={ready} className={b.inverse} />
        </div>
      </div>
    </div>
  );
}

function DoseCard({ setup, recipe, onDose }: { setup: Setup; recipe: Recipe; onDose: (d: number) => void }) {
  return (
    <SoftCard>
      <EyebrowLabel text="DOSE" />
      <div className={b.stepRow}>
        <Stepper dir={-1} label="Less" onClick={() => onDose(Math.max(1, setup.doseG - 0.5))} />
        <div className={b.stepMid}>
          {/* Large, not Display: the grind below is the one hero on this screen. */}
          <span className={b.nwu}>
            <TickingNumber value={grams(setup.doseG)} numeral="num-large" label={`${grams(setup.doseG)} grams`} />
            <span className={`num-unit ${b.unitL}`}>g</span>
          </span>
          <span className={`t-body-m ink-mid ${b.center}`}>
            → {grams(recipe.waterG)} g water · {ratio(setup.doseG, recipe.waterG)}
          </span>
        </div>
        <Stepper dir={1} label="More" onClick={() => onDose(setup.doseG + 0.5)} />
      </div>
    </SoftCard>
  );
}

function GrindCard({ recipe, base, setup, setSetup, data }: { recipe: Recipe; base: Recipe; setup: Setup; setSetup: (fn: (s: Setup) => Setup) => void; data: SetupData }) {
  const grinder = data.grinders.find((g) => g.id === setup.grinderId) ?? null;
  const scale = useGrindScale(grinder);
  const method = recipe.brewer.method;
  const [typing, setTyping] = useState(false);
  const recipeLogs = useMemo(() => data.brews.filter((l) => l.recipeId === base.id), [data.brews, base.id]);
  const last = recipeLogs.find((l) => l.grinderId === setup.grinderId) ?? recipeLogs[0] ?? null;

  const setGrinder = (id: number) => {
    setTyping(false);
    setSetup((s) => prefillGrind({ ...s, grinderId: id, grindClicks: null, grindSetting: "" }, base, data.grinders, data.brews));
    void settingsStore.set("defaultGrinderId", id);
  };
  const adjust = (delta: number) =>
    setSetup((s) => {
      const g = data.grinders.find((x) => x.id === s.grinderId);
      const pref = g ? grindPreference(base, g) : null;
      const current = s.grindClicks ?? (pref != null ? Math.round(pref) : 0);
      return { ...s, grindClicks: Math.max(minClicks(g), current + delta) };
    });
  const setClicks = (v: number | null) =>
    setSetup((s) => {
      const floor = minClicks(data.grinders.find((x) => x.id === s.grinderId));
      return { ...s, grindClicks: v == null ? null : Math.min(Math.max(floor, Math.round(v)), Math.max(floor, 999)) };
    });

  return (
    <SoftCard>
      <div className={b.cardHead}>
        <EyebrowLabel text="GRIND" />
        {data.grinders.length === 1 && grinder && <span className={`micro ink-low ${b.ellipsis}`}>{toCaps(grinderDisplay(grinder))}</span>}
      </div>
      {data.grinders.length > 1 && (
        <div className={b.chipRow} role="group" aria-label="Grinder">
          {data.grinders.map((g) => (
            <SelectableChip key={g.id} text={grinderDisplay(g)} selected={g.id === setup.grinderId} onClick={() => setGrinder(g.id!)} />
          ))}
        </div>
      )}
      <div className={b.gap12} />
      {grinder && usesClicks(grinder) && scale ? (
        typing ? (
          <DialReadingInput scale={scale} value={setup.grindClicks} onValue={setClicks} />
        ) : (
          <ClickDial clicks={setup.grindClicks} grinder={grinder} caption={setup.grindClicks == null ? "Set your grind" : describe(scale, setup.grindClicks)} onAdjust={adjust} />
        )
      ) : (
        <>
          {data.grinders.length === 0 && <p className="t-body-s ink-mid">No grinders yet. Type a setting, or add one under Gear.</p>}
          <FieldWell label="SETTING" value={setup.grindSetting} onChange={(v) => setSetup((s) => ({ ...s, grindSetting: v }))} placeholder={recipe.grind} />
        </>
      )}
      {grinder && usesClicks(grinder) && scale && (
        <QuietButton text={typing ? "Use the stepper" : "Type it as the dial reads"} onClick={() => setTyping((t) => !t)} className={b.quietLeft} />
      )}
      {scale && (
        <GrindStartHint
          scale={scale}
          method={method}
          onUse={(v) => (usesClicks(grinder) ? setClicks(v) : setSetup((s) => ({ ...s, grindSetting: formatGrind(scale, v) })))}
          isCurrent={(v) => (usesClicks(grinder) ? setup.grindClicks === v : setup.grindSetting.trim() === formatGrind(scale, v))}
        />
      )}
      {last && (
        <>
          <div className={b.gap12} />
          <HairLine />
          <div className={b.gap12} />
          {/* The verdict takes the status colour; the grind that earned it is the detail. */}
          <StatusLine
            status={scoreStatus(last.overall)}
            lead={last.overall > 0 ? `Last time ${last.overall}/10` : "Last time unrated"}
            rest={`${last.grindClicks != null ? `${last.grindClicks} clicks` : last.grindSetting.trim() || "no grind"} on the ${brewerShortName(recipe.brewer)}`}
          />
        </>
      )}
      <p className={`t-body-s ink-low ${b.recipeSays}`}>Recipe says: {recipe.grind}</p>
    </SoftCard>
  );
}

/**
 * Grind position for click grinders: a number you can nudge with your thumb mid-setup. Stored as
 * an integer so a report can say "go three clicks finer" and mean something. An unset grind is a
 * dash, never 0 (0 is burr contact).
 */
function ClickDial({ clicks, grinder, caption, onAdjust }: { clicks: number | null; grinder: GrinderRow; caption: string; onAdjust: (d: number) => void }) {
  const down = grinder.lowerIsFiner ? "finer" : "coarser";
  const up = grinder.lowerIsFiner ? "coarser" : "finer";
  return (
    <div className={b.dial}>
      <div className={b.stepRow}>
        <div className={b.stepSide}>
          <Stepper dir={-1} label={`One click ${down}`} onClick={() => onAdjust(-1)} />
          <span className="micro ink-low">{toCaps(down)}</span>
        </div>
        <div className={b.stepMid}>
          <span className={b.nwu} style={{ color: clicks == null ? "var(--ink-low)" : undefined }}>
            <TickingNumber value={clicks == null ? "—" : String(clicks)} numeral="num-display" label={clicks == null ? "Grind not set" : `${clicks} clicks`} />
            {clicks != null && <span className={`num-unit ${b.unitXL}`}>clicks</span>}
          </span>
          <span className={`t-body-s ink-mid ${b.center}`}>{caption}</span>
        </div>
        <div className={b.stepSide}>
          <Stepper dir={1} label={`One click ${up}`} onClick={() => onAdjust(1)} />
          <span className="micro ink-low">{toCaps(up)}</span>
        </div>
      </div>
      <div className={b.pills}>
        {[-5, -1, 1, 5].map((d) => (
          <PillButton key={d} text={d < 0 ? `−${-d}` : `+${d}`} onClick={() => onAdjust(d)} className={b.pill} />
        ))}
      </div>
    </div>
  );
}

function TempCard({ tempC, fahrenheit, onTemp }: { tempC: number; fahrenheit: boolean; onTemp: (c: number) => void }) {
  const u = { fahrenheit, ounces: false };
  // In °F mode the stepper still moves 1 °C and shows the rounded °F (A§7.8).
  return (
    <SoftCard>
      <div className={b.tempRow}>
        <div className={b.grow}>
          <EyebrowLabel text="WATER TEMP" />
          <span className={b.nwu}>
            <TickingNumber value={temp(u, tempC)} numeral="num-large" label={`${temp(u, tempC)} ${tempUnit(u)}`} />
            <span className={`num-unit ${b.unitL}`}>{tempUnit(u)}</span>
          </span>
        </div>
        <Stepper dir={-1} label="Cooler" onClick={() => onTemp(tempC - 1)} />
        <Stepper dir={1} label="Hotter" onClick={() => onTemp(tempC + 1)} />
      </div>
    </SoftCard>
  );
}

function CoffeeCard({ beans, selected, onSelect }: { beans: readonly BeanRow[]; selected: number | null; onSelect: (id: number) => void }) {
  // Read once: days off roast only needs to be right to the day while this screen is open.
  const [today] = useState(() => epochDayOf(Date.now()));
  return (
    <SoftCard>
      <EyebrowLabel text="COFFEE" />
      {beans.length === 0 ? (
        <p className={`t-body-m ink-mid ${b.gap8}`}>No beans yet. Add one under Gear.</p>
      ) : (
        <div role="radiogroup" aria-label="Coffee">
          {beans.map((bean) => {
            const days = bean.roastedOnEpochDay != null ? Math.max(0, today - bean.roastedOnEpochDay) : null;
            const word = roastWord(days);
            return (
              <RadioRow
                key={bean.id}
                name="brew-bean"
                title={bean.name}
                selected={bean.id === selected}
                onClick={() => onSelect(bean.id!)}
                subtitle={[bean.roaster.trim() || null, days != null ? `${days} d off roast` : "roast date unknown"].filter(Boolean).join(" · ")}
                note={word ?? undefined}
                noteStatus={roastStatus(days) === "attention" ? "attention" : undefined}
              />
            );
          })}
        </div>
      )}
    </SoftCard>
  );
}

// ---------------------------------------------------------------------- running

const RunningPane = memo(function RunningPane({
  recipe,
  engine,
  st,
  seek,
  finishing,
  autoAdvance,
  onClose,
  onFinish,
  onToggle,
  onSeek,
  onSkip,
}: {
  recipe: Recipe;
  engine: BrewEngine;
  st: EngineState;
  seek: MotionValue<number>;
  finishing: boolean;
  autoAdvance: boolean;
  onClose: () => void;
  onFinish: () => void;
  onToggle: () => void;
  onSeek: (d: number) => void;
  onSkip: () => void;
}) {
  const total = totalSec(recipe);
  const step = recipe.steps[st.stepIndex];
  const pouring = step?.kind === "Pour" || step?.kind === "Bloom";
  const waterG = Math.max(1, recipe.waterG);
  // One whole-brew timebase for the ring and the journey line, so the two drawings of progress
  // never disagree with each other or with the clock.
  const view: BrewEngineView = useMemo(
    () => ({
      elapsedMs: () => engine.elapsedMs(),
      totalMs: () => Math.max(1, total) * 1000,
      waterFractionAt: (ms: number) => waterAtF(recipe, ms) / waterG,
    }),
    [engine, recipe, total, waterG],
  );
  const trip = useMemo(() => () => (engine.elapsedMs() + seek.get()) / (Math.max(1, total) * 1000), [engine, seek, total]);
  const boundaries = useMemo(() => stepBoundaries(recipe), [recipe]);
  const nodes = useMemo<JourneyNode[]>(
    () => [...journeyNodes(recipe), ...(st.bedExposedSec != null ? [{ fraction: Math.min(1, Math.max(0, st.bedExposedSec / Math.max(1, total))), kind: "actual" as const, status: "good" as const }] : [])],
    [recipe, st.bedExposedSec, total],
  );
  const showBed = step?.kind === "Drawdown" || st.done;
  const waitingForTap = !autoAdvance && st.clockStepIndex > st.stepIndex;

  return (
    <div className={`column ${b.run}`}>
      <div className={b.runTop}>
        <IconWell icon="Close" label="Leave this brew" onClick={onClose} />
        <span className={`t-title-s ink-mid ${b.runTitle}`}>{recipe.name}</span>
        <span className="micro ink-low">
          STEP {st.stepIndex + 1}/{recipe.steps.length}
        </span>
      </div>

      <BrewStatus st={st} step={step} engine={engine} finishing={finishing} />

      <div className={b.ringBox}>
        <BrewRing
          engine={view}
          accent="var(--crema-stroke)"
          boundaries={boundaries}
          seek={seek}
          pouring={pouring}
          running={st.running}
          trackColor="var(--ink-faint)"
          liquidColor="var(--ring-liquid)"
          strokeFraction={0.035}
          headCore="var(--bg)"
          tickColor="var(--ink-low)"
        >
          <div className={b.ringInner}>
            <ClockLeaf engine={engine} compact={total >= 600} />
            {/* Names the clock's frame: the ring and the clock are both the whole brew. */}
            <span className="micro ink-low">OF {clock(total)}</span>
            <WaterLeaf engine={engine} recipe={recipe} target={step?.targetWaterG ?? Math.round(recipe.waterG)} />
          </div>
        </BrewRing>
      </div>

      <JourneyLine nodes={nodes} style="live" progress={trip} startLabel="0:00" endLabel={clock(total)} label={`Brew progress, ${clock(total)} planned`} />
      <OverrunLeaf engine={engine} totalSec={total} />

      <div className={b.stepCard}>
        <AnimatePresence initial={false}>
          <m.div
            key={st.stepIndex}
            className={b.stepLayer}
            initial={{ opacity: 0, transform: "translateY(33%)" }}
            animate={{ opacity: 1, transform: "translateY(0%)", transition: { opacity: decel(260), transform: spatialDefault } }}
            exit={{ opacity: 0, transform: "translateY(-33%)", transition: decel(160) }}
          >
            <StepCardBody recipe={recipe} index={st.stepIndex} />
          </m.div>
        </AnimatePresence>
        {waitingForTap && (
          <button type="button" className={`${b.nextTap} t-label-l`} onClick={() => engine.advanceStep()}>
            Next step
          </button>
        )}
      </div>

      {showBed && (
        <m.div className={b.bedRow} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={effectsDefault}>
          {st.bedExposedSec == null ? (
            <SecondaryButton text="Bed exposed" onClick={() => engine.markBedExposed()} onCanvas />
          ) : (
            <StatusLine status="good" lead="Bed exposed" rest={`at ${clock(st.bedExposedSec)}`} />
          )}
        </m.div>
      )}

      <div className={b.transport}>
        <PillButton text="−5s" onClick={() => onSeek(-5)} className={b.seek} />
        <PlayPause running={st.running} onClick={onToggle} />
        <PillButton text="+5s" onClick={() => onSeek(5)} className={b.seek} />
        <span className={b.grow} />
        <IconWell icon="SkipNext" label="Skip to next step" onClick={onSkip} size={48} />
      </div>

      {/* The finish button turns crema when the brew completes, in step with the mesh. */}
      <PrimaryButton
        text={st.done ? "Rate this brew" : "Finish early"}
        icon="Check"
        height={52}
        onClick={onFinish}
        enabled={!finishing}
        className={st.done ? b.finishDone : b.finishEarly}
      />
    </div>
  );
});

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

function StepCardBody({ recipe, index }: { recipe: Recipe; index: number }) {
  const s = recipe.steps[index];
  const next = recipe.steps[index + 1];
  if (!s) return null;
  return (
    <div className={b.stepBody}>
      <div className={b.stepHead}>
        <span className={`micro ${b.kindPill}`}>{toCaps(s.kind)}</span>
        <span className={b.grow} />
        <span className="t-body-s ink-low">
          {clock(s.startSec)}–{clock(s.endSec)}
        </span>
      </div>
      <h2 className={`step-title ${b.clamp2}`}>{s.title}</h2>
      <p className={`t-body-m ink-mid ${b.clamp3}`}>{s.detail}</p>
      <span className={b.grow} />
      {next && (
        <div className={b.next}>
          <HairLine />
          <span className="micro ink-low">NEXT · {clock(next.startSec)}</span>
          <span className={`t-body-m ink-mid ${b.ellipsis}`}>{next.title}</span>
        </div>
      )}
    </div>
  );
}

function PlayPause({ running, onClick }: { running: boolean; onClick: () => void }) {
  return (
    <button type="button" className={b.play} onClick={onClick} aria-label={running ? "Pause" : "Resume"}>
      <AnimatePresence initial={false}>
        <m.span
          key={running ? "pause" : "play"}
          className={b.playIcon}
          initial={{ opacity: 0, transform: "scale(0.6)" }}
          animate={{ opacity: 1, transform: "scale(1)" }}
          exit={{ opacity: 0, transform: "scale(0.6)" }}
          transition={{ transform: settle, opacity: decel(140) }}
        >
          <Icon name={running ? "Pause" : "Play"} size={30} />
        </m.span>
      </AnimatePresence>
    </button>
  );
}

// ------------------------------------------------------------------ timer leaves
// Each writes textContent from the frame loop, so the digits change once a second (the water
// readout once a gram) and nothing above them ever re-renders with the clock.

function Leaf({ className, initial, update, ariaHidden = false, role }: { className: string; initial: string; update: (el: HTMLElement) => void; ariaHidden?: boolean; role?: string }): ReactNode {
  const ref = useRef<HTMLSpanElement>(null);
  useFrame(() => {
    if (ref.current) update(ref.current);
  });
  return (
    <span ref={ref} className={className} aria-hidden={ariaHidden || undefined} role={role} aria-live={role === "timer" ? "off" : undefined}>
      {initial}
    </span>
  );
}

function ClockLeaf({ engine, compact }: { engine: BrewEngine; compact: boolean }) {
  const text = () => clock(Math.floor(engine.elapsedMs() / 1000));
  return <Leaf className={`${compact ? "num-clock-compact" : "num-clock"} ${b.clock}`} initial={text()} update={(el) => writeText(el, text())} role="timer" />;
}

function CountdownLeaf({ engine, endSec }: { engine: BrewEngine; endSec: number }) {
  const text = () => `· ${clock(Math.max(0, endSec - Math.floor(engine.elapsedMs() / 1000)))} left`;
  return <Leaf className={`t-body-m ink-mid ${b.countdown}`} initial={text()} update={(el) => writeText(el, text())} />;
}

function WaterLeaf({ engine, recipe, target }: { engine: BrewEngine; recipe: Recipe; target: number }) {
  const text = () => `${waterAt(recipe, engine.elapsedMs())} g`;
  return (
    <span className={b.water}>
      <Leaf className="num-small" initial={text()} update={(el) => writeText(el, text())} />
      <span className={`t-body-m ink-low ${b.waterTarget}`}>/ {target}</span>
    </span>
  );
}

function OverrunLeaf({ engine, totalSec: total }: { engine: BrewEngine; totalSec: number }) {
  const text = () => {
    const s = Math.floor(engine.elapsedMs() / 1000);
    return s > total ? `+${clock(s - total)}` : "";
  };
  return <Leaf className={`micro ${b.overrun}`} initial={text()} update={(el) => writeText(el, text())} />;
}

