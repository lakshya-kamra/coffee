/**
 * Port of BrewScreen.kt + BrewViewModel.kt (web-spec §11 Brew, §12; v4 §3.4, §3.5, §4.9, §4.10).
 * Setup pane (dose, grind, temperature, coffee, prep) over a calm mesh; Start hands over to the
 * running pane (portrait, stage or short, chosen live from the window) or, for espresso, to shot
 * mode. /brew/:id/again/:uid opens the setup prefilled from that brew.
 *
 * React renders only on discrete engine changes (start, pause, step, bed exposed, seek, done).
 * The clock digits, countdown, water readout, overrun, ring and journey line are leaves fed by
 * the shared frame loop (rule 4). A reload mid-brew resumes from kv activeBrew (§12.5).
 */
import { animate } from "motion";
import { AnimatePresence, m, useMotionValue } from "motion/react";
import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState, useSyncExternalStore } from "react";
import { nav } from "@/app/router";
import { useShortcuts, type Binding } from "@/app/shortcuts";
import { showToast } from "@/app/toast";
import { useWindowClass } from "@/app/windowClass";
import { CoffeeMug } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { repo, type ActiveBrew } from "@/data/repo";
import type { BrewRow } from "@/data/schema";
import { settings as settingsStore } from "@/data/settings";
import { persistOnce } from "@/data/storage";
import { getSnapshot, store, useBrew, useRecipe, useSettings } from "@/data/store";
import { ConfirmSheet, DetailTopBar, QuietButton, SecondaryButton } from "@/design/components";
import { decel, settle } from "@/design/motion";
import { timeLabel } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { stepsInUnit, unitsOf } from "@/domain/core/units";
import { MeshGradient, stepMood, useFrame, type BrewMood } from "@/viz";
import { AGAIN_TITLE, againSetup, oneOffRecipe } from "./again";
import { Announcer, doneAnnouncement, stepAnnouncement, stepLead, TEN_LEFT, tenLeftDue, TIME_FOR_NEXT } from "./announce";
import { CuePlayer, cueOffsetsSec, vibrateStep } from "./cues";
import { BrewEngine, type EngineState } from "./engine";
import { runLayoutFor, setupLayoutFor } from "./layout";
import { RunningPane, type StepCueEvent } from "./RunningPane";
import { SetupPane, type SetupData } from "./SetupPane";
import { buildBrewRow, initialSetup, setupRecipe, withSetupDefaults, type Setup } from "./setup";
import { initialShot, shotReducer, shotYield } from "./shot";
import { ShotPane } from "./ShotPane";
import { ScreenLock } from "./wakeLock";
import b from "./brew.module.css";

/** How much of the setup mesh settles into the canvas behind the sticky Start bar. */
const SETUP_FLOOR = 112;
/** The same for the portrait timer's sticky transport, so its backdrop never shows as a band. */
const DOCK_FLOOR = 150;

// ---------------------------------------------------------------------- screen

export function BrewScreen({ recipeId, againUid }: { recipeId: string; againUid?: string }) {
  const stored = useRecipe(recipeId);
  const again = useBrew(againUid ?? "");
  useCatalog(["brewers"]);
  // A deleted recipe can still be brewed again: a one-off from the brew's own numbers (§4.10).
  const oneOff = useMemo(() => (stored || !again ? null : oneOffRecipe(again, Brewers.resolve(again.brewerId ?? again.brewer))), [stored, again]);
  const base = stored ?? oneOff;
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
        <BrewSession recipe={base} resume={null} again={again} oneOff={oneOff != null} />
        <ConfirmSheet
          title={`Finish the brew you started at ${timeLabel(startedAt)} first?`}
          body="It's still running. Discarding it keeps nothing from it."
          confirmText="Resume"
          cancelText="Discard"
          safe="confirm"
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
  return <BrewSession recipe={base} resume={active} again={again} oneOff={oneOff != null} />;
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

/** The polite live region the Announcer writes to (A4). */
function LiveRegion({ announcer }: { announcer: Announcer }) {
  const ref = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    announcer.attach(ref.current);
    return () => announcer.attach(null);
  }, [announcer]);
  return <p ref={ref} className="visually-hidden" aria-live="polite" />;
}

function useEngineState(engine: BrewEngine): EngineState {
  return useSyncExternalStore(
    (fn) => engine.subscribe(fn),
    () => engine.state,
    () => engine.state,
  );
}

function BrewSession({ recipe: base, resume, again, oneOff }: { recipe: Recipe; resume: ActiveBrew | null; again: BrewRow | null; oneOff: boolean }) {
  const settings = useSettings();
  const units = unitsOf(settings);
  const wc = useWindowClass();
  const runLayout = runLayoutFor(wc);
  const setupLayout = setupLayoutFor(wc);
  // Lists read once at open, like BrewViewModel's init: the setup never reshuffles under a thumb.
  const [data] = useState<SetupData>(() => {
    const snap = getSnapshot();
    return {
      beans: snap.beans.filter((x) => !x.isArchived),
      grinders: snap.grinders.filter((x) => !x.isArchived),
      brews: snap.brews,
    };
  });
  const [setup, setSetup] = useState<Setup>(() => {
    if (resume) return withSetupDefaults(resume.setup);
    if (again) return againSetup(base, again, data.beans, data.grinders, settings, data.brews);
    return initialSetup(base, data.beans, data.grinders, settings, data.brews);
  });
  const recipe = useMemo(() => setupRecipe(base, setup), [base, setup]);
  // What the running screen shows: step titles and details in the person's unit (§4.15). The
  // engine keeps the stored recipe; only words differ.
  const unit: "c" | "f" = units.fahrenheit ? "f" : "c";
  const shownRecipe = useMemo(() => (unit === "c" ? recipe : { ...recipe, steps: stepsInUnit(recipe.steps, unit) }), [recipe, unit]);
  const unitRef = useRef(unit);
  useLayoutEffect(() => {
    unitRef.current = unit;
  });
  const [engine] = useState(() => {
    const e = new BrewEngine(setupRecipe(base, setup), { autoAdvance: settings.autoAdvanceSteps });
    if (resume) e.restore(resume);
    return e;
  });
  const st = useEngineState(engine);
  const espresso = base.brewer.method === "espresso";
  const [shotOpen, setShotOpen] = useState(false);
  const [shot, dispatchShot] = useReducer(shotReducer, recipe.waterG, initialShot);
  const [finishing, setFinishing] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [confirmFinish, setConfirmFinish] = useState(false);
  const [cue, setCue] = useState<StepCueEvent | null>(null);
  const seek = useMotionValue(0);
  const cues = useRef<CuePlayer | null>(null);
  const lock = useRef<ScreenLock | null>(null);
  const [announcer] = useState(() => new Announcer());
  const firstBrew = data.brews.length === 0;

  useEffect(() => {
    engine.setRecipe(recipe);
  }, [engine, recipe]);
  useEffect(() => {
    engine.setAutoAdvance(settings.autoAdvanceSteps);
  }, [engine, settings.autoAdvanceSteps]);

  // ------------------------------------------------------------ the clock's side effects
  // Step detection every frame while visible; a timer at the next boundary while hidden.
  // The same frame also says "10 seconds left" once in each step longer than 30 s (A4).
  const tenSaid = useRef(new Set<number>());
  useFrame(() => {
    engine.tick();
    const i = engine.state.clockStepIndex;
    if (!tenSaid.current.has(i) && tenLeftDue(engine.getRecipe().steps[i], engine.elapsedMs() / 1000)) {
      tenSaid.current.add(i);
      announcer.say(TEN_LEFT);
    }
  }, st.started && st.running);
  useEffect(() => {
    if (!st.started || !st.running) return;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const arm = () => {
      if (timer) clearTimeout(timer);
      timer = null;
      if (!document.hidden) return;
      const ms = engine.msToNextBoundary();
      if (ms != null)
        timer = setTimeout(() => {
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

  // Cues on a step change: one buzz, one announcement, the flash and the banner (§3.5, A4).
  const hapticsOn = settings.haptics;
  useEffect(
    () =>
      engine.onStep((i) => {
        if (hapticsOn) vibrateStep();
        const steps = stepsInUnit(engine.getRecipe().steps, unitRef.current);
        announcer.say(stepAnnouncement(steps, i));
        const s = steps[i];
        if (s) setCue((c) => ({ serial: (c?.serial ?? 0) + 1, text: `NEXT STEP · ${stepLead(s)}` }));
      }),
    [engine, hapticsOn, announcer],
  );
  // The other two moments A4 names: auto-advance off reaching a boundary, and the end.
  useEffect(() => {
    let prev = engine.state;
    return engine.subscribe((next) => {
      const was = prev;
      prev = next;
      if (!next.started) return;
      const waiting = next.clockStepIndex > next.stepIndex;
      const wasWaiting = was.started && was.clockStepIndex > was.stepIndex;
      if (waiting && !wasWaiting && next.running) {
        if (hapticsOn) vibrateStep();
        announcer.say(TIME_FOR_NEXT);
        const s = engine.getRecipe().steps[next.stepIndex + 1];
        if (s) setCue((c) => ({ serial: (c?.serial ?? 0) + 1, text: `NEXT STEP · ${stepLead(s)}` }));
      }
      if (next.done && !was.done) announcer.say(doneAnnouncement(engine.elapsedMs() / 1000));
    });
  }, [engine, hapticsOn, announcer]);
  useEffect(() => () => announcer.dispose(), [announcer]);

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

  // Wake lock follows running (or a shot on screen), with a minute's grace on pause.
  const keepOn = settings.keepScreenOn;
  const awake = st.started || shotOpen;
  const holding = st.running || shot.phase === "pulling";
  useEffect(() => {
    if (!keepOn || !awake) {
      lock.current?.release();
      return;
    }
    lock.current ??= new ScreenLock();
    if (holding) lock.current.hold();
    else lock.current.paused();
  }, [keepOn, awake, holding]);
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
    void settingsStore.set("lastRecipeId", base.id);
    if (espresso) {
      setShotOpen(true);
      return;
    }
    if (soundOn) (cues.current ??= new CuePlayer()).unlock();
    engine.start();
    announcer.say(stepAnnouncement(engine.getRecipe().steps, engine.state.stepIndex));
  };
  const toggle = () => {
    if (!st.running && soundOn) (cues.current ??= new CuePlayer()).unlock();
    engine.toggle();
  };
  const waitingForTap = st.started && !settings.autoAdvanceSteps && st.clockStepIndex > st.stepIndex;
  const skip = () => (waitingForTap ? engine.advanceStep() : seekBy(() => engine.skipToNextStep()));
  const drawdownNow = recipe.steps[st.stepIndex]?.kind === "Drawdown" && st.bedExposedSec == null;

  const finish = async (shotResult?: { sec: number; yieldG: number | null }) => {
    if (finishing) return;
    setFinishing(true);
    const result = shotResult ? { totalTimeSec: shotResult.sec, drawdownSec: null } : engine.finish();
    const bean = data.beans.find((x) => x.id === setup.beanId) ?? null;
    const grinder = data.grinders.find((x) => x.id === setup.grinderId) ?? null;
    const row = buildBrewRow({ recipe, setup, bean, grinder, settings, result, beverageG: shotResult?.yieldG ?? null });
    await repo.insertBrew(row);
    await repo.setActiveBrew(null);
    await storeHasBrew(row.uid);
    void persistOnce();
    lock.current?.release();
    cues.current?.close();
    nav.replace(`/review/${encodeURIComponent(row.uid)}`);
  };
  const askFinish = () => {
    if (finishing) return;
    if (st.done) void finish();
    else setConfirmFinish(true);
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

  const toggleChime = () => {
    const on = !soundOn;
    void settingsStore.update({ soundCues: on, soundCuesAsked: true });
    if (on) (cues.current ??= new CuePlayer()).unlock();
    showToast({ text: on ? "Chime on for each step" : "Chime off", durationMs: 2500 });
  };

  // ------------------------------------------------------------ keyboard (§2.7)
  const running = st.started && !shotOpen;
  const runKeys: Binding[] = [
    { keys: "space", label: "Play or pause", group: "Brewing", run: toggle },
    // Arrows stay with a focused control (a slider, a radio); Space on a button presses it.
    { keys: "left", label: "Back 5 seconds", group: "Brewing", run: () => seekBy(() => engine.addSeconds(-5)), skipOnControls: true },
    { keys: "right", label: "Forward 5 seconds", group: "Brewing", run: () => seekBy(() => engine.addSeconds(5)), skipOnControls: true },
    { keys: "n", label: "Next step", group: "Brewing", run: skip },
    { keys: "down", label: "Next step", group: "Brewing", run: skip, hidden: true, skipOnControls: true },
    { keys: "x", label: "Bed exposed (during the drawdown)", group: "Brewing", run: () => drawdownNow && engine.markBedExposed() },
    { keys: "f", label: "Finish", group: "Brewing", run: askFinish },
    { keys: "m", label: soundOn ? "Chime off" : "Chime on", group: "Brewing", run: toggleChime },
    { keys: "escape", label: "Leave this brew", group: "Brewing", run: () => !finishing && setLeaving(true) },
  ];
  useShortcuts("brew", runKeys, running && !finishing);

  const shotToggle = () => dispatchShot({ type: "toggle", now: performance.now() });
  const shotClose = () => (shot.phase === "ready" ? setShotOpen(false) : setLeaving(true));
  const shotKeys: Binding[] = [
    { keys: "space", label: shot.phase === "pulling" ? "Stop shot" : "Start shot", group: "Brewing", run: shotToggle },
    { keys: "escape", label: shot.phase === "ready" ? "Back to set up" : "Leave this shot", group: "Brewing", run: shotClose },
  ];
  useShortcuts("brew-shot", shotKeys, shotOpen && !finishing);

  const mood: BrewMood = shotOpen ? (shot.phase === "pulling" ? "pouring" : shot.phase === "stopped" ? "done" : "idle") : !st.started ? "idle" : st.done ? "done" : stepMood(recipe.steps[st.stepIndex]?.kind ?? "Prep");
  const pane = shotOpen ? "shot" : st.started ? "run" : "setup";

  return (
    <div className={b.screen}>
      <MeshGradient mood={mood} intensity={pane === "setup" ? 0.3 : 0.6} floor={pane === "setup" && setupLayout === "single" ? SETUP_FLOOR : pane === "run" && runLayout === "portrait" ? DOCK_FLOOR : 0} />
      <AnimatePresence initial={false}>
        <m.div key={pane} className={b.pane} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={decel(320)}>
          {pane === "run" ? (
            <RunningPane
              recipe={shownRecipe}
              engine={engine}
              st={st}
              seek={seek}
              layout={runLayout}
              finishing={finishing}
              waitingForTap={waitingForTap}
              hasScale={settings.hasScale}
              fahrenheit={units.fahrenheit}
              firstBrew={firstBrew}
              experienceNew={settings.experience === "new"}
              cue={cue}
              onClose={() => !finishing && setLeaving(true)}
              onFinish={() => void finish()}
              onToggle={toggle}
              onSeek={(d) => seekBy(() => engine.addSeconds(d))}
              onSkip={skip}
              onAdvance={() => engine.advanceStep()}
              onBed={() => engine.markBedExposed()}
            />
          ) : pane === "shot" ? (
            <ShotPane
              recipe={recipe}
              shot={shot}
              layout={runLayout}
              finishing={finishing}
              onToggle={shotToggle}
              onAgain={() => dispatchShot({ type: "again" })}
              onYield={(text) => dispatchShot({ type: "yield", text })}
              onRate={() => void finish({ sec: shot.shotSec ?? 0, yieldG: shotYield(shot) })}
              onClose={shotClose}
            />
          ) : (
            <SetupPane
              recipe={recipe}
              base={base}
              setup={setup}
              setSetup={setSetup}
              data={data}
              settings={settings}
              units={units}
              layout={setupLayout}
              title={oneOff ? AGAIN_TITLE : recipe.name}
              eyebrow={again ? "BREW AGAIN" : "SET UP"}
              onStart={start}
            />
          )}
        </m.div>
      </AnimatePresence>
      {/* A4: polite, written by the Announcer (cleared before each write). */}
      <LiveRegion announcer={announcer} />
      {leaving && (
        <ConfirmSheet
          title={shotOpen ? "Leave this shot?" : "Leave this brew?"}
          body={shotOpen ? "Nothing from it is saved." : "Its timer keeps going, and Today shows the way back. Discarding keeps nothing from it."}
          confirmText={shotOpen ? "Stay" : "Keep it going"}
          cancelText="Discard"
          safe="confirm"
          onClose={() => setLeaving(false)}
          onConfirm={shotOpen ? () => undefined : nav.back}
          onCancel={shotOpen ? nav.back : discard}
        />
      )}
      {confirmFinish && (
        <ConfirmSheet
          title="Finish now?"
          body="The brew is saved with the time so far, and you rate it next."
          confirmText="Finish"
          cancelText="Keep brewing"
          safe="cancel"
          onClose={() => setConfirmFinish(false)}
          onConfirm={() => void finish()}
        />
      )}
    </div>
  );
}
