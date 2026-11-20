/**
 * Port of ReviewScreen.kt + ReviewViewModel.kt (web-spec §11 Review; v4 §3.6, §4.4). Quick or
 * detailed by settings.reviewMode: Overall, "ANYTHING OFF?" and notes always; taste, drawdown and
 * the bed under "More detail" in quick mode, and only for the methods that have them (CORE
 * reviewSections); TDS, beverage weight and extraction under "Advanced". At 840 and wider the
 * form is two columns: what happened on the left (numbers, the trip, coffee, photos), how it
 * tasted on the right with a sticky action bar.
 *
 * Edits autosave, debounced 400 ms and run after any transition settles, so a Dexie write never
 * re-renders a list mid-flight (rule 5). Ctrl/Cmd+Enter saves, Escape asks to discard, 1-9 and 0
 * set the score.
 */
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { nav, router } from "@/app/router";
import { pathOf } from "@/app/routes";
import { isTypingTarget, useShortcuts, type Binding } from "@/app/shortcuts";
import { afterTransition, useTransitionSettled } from "@/app/transitionState";
import { BedShapeIcon, Dripper } from "@/art";
import { repo } from "@/data/repo";
import type { BrewRow, GrinderRow } from "@/data/schema";
import { useBeans, useBrew, useGrinders, useRecipe, useSettings } from "@/data/store";
import {
  AxisSlider,
  ChipFlow,
  ConfirmSheet,
  DotRating,
  EyebrowLabel,
  FieldWell,
  HairLine,
  Icon,
  KeyValueRow,
  PrimaryButton,
  QuietButton,
  SecondaryButton,
  SectionLabel,
  SelectableChip,
  SoftCard,
  StatusChip,
  StatusLine,
  Term,
  TickingNumber,
  toCaps,
} from "@/design/components";
import { brewerOf, checkBrew, distrustedFields, Fields } from "@/domain/ai";
import { methodLabels, reviewSections } from "@/domain/core/brewer";
import { BED_SHAPES, BrewMath, CHANNELING, Descriptors, FLOW_FEEL, SLUDGE, TASTE_AXES, bedShapeInfo, withTaste, type BedShape } from "@/domain/core/evaluation";
import { clock, daysOffRoastLabel, formatDuration, grams, oneDp, ratio } from "@/domain/core/format";
import { quickRead } from "@/domain/core/quickRead";
import { totalSec, type Recipe } from "@/domain/core/recipe";
import { bedStatus, channelingStatus, cupLabel, flowStatus, scoreStatus, signedClock, statusColorVar, statusContainerVar, sludgeStatus, timeStatus } from "@/domain/core/status";
import { unitsOf } from "@/domain/core/units";
import { describe } from "@/domain/grind";
import { startBrewAnalysis, useAiSettings } from "@/features/ai";
import { describeClicks } from "@/features/brew/setup";
import { DialReadingInput, useGrindScale } from "@/features/grind";
import { PhotoStrip } from "@/features/photos";
import { InfoGrid, JourneyLine, journeyNodes } from "@/viz";
import { cleanClockText, dialHelper, digitsOnly, grindWarning, parseClock, parseDecimal, QUICK_FAULTS, quickOn, reviewPlan, tasteUntouched, toggleIn, toggleQuick } from "./reviewModel";
import v from "./review.module.css";

export function ReviewScreen({ brewUid }: { brewUid: string }) {
  const row = useBrew(brewUid);
  if (!row) return <MissingBrew />;
  return <ReviewForm key={row.uid} initial={row} />;
}

function MissingBrew() {
  return (
    <div className={`column ${v.missing}`}>
      <span className={v.missingArt}>
        <Dripper waterLevel={0} dripping={false} size={88} />
      </span>
      <h1 className="t-title-l">This brew is no longer in the log.</h1>
      <QuietButton text="Back to Today" onClick={() => nav.popToRoot("today")} />
    </div>
  );
}

/** Review "Done" (web-spec §6.2): back to the log entry it was opened from, else to Today. */
function leave(uid: string) {
  const s = router.get();
  const below = s.stack[s.stack.length - 2];
  if (below && pathOf(below) === `/log/${encodeURIComponent(uid)}`) nav.back();
  else nav.popToRoot("today");
}

function ReviewForm({ initial }: { initial: BrewRow }) {
  const [log, setLog] = useState<BrewRow>(initial);
  const [text, setText] = useState(() => ({
    drawdown: initial.drawdownSec?.toString() ?? "",
    beverage: initial.beverageG != null ? String(BrewMath.round1(initial.beverageG)) : "",
    tds: initial.tdsPercent?.toString() ?? "",
    grind: initial.grindClicks?.toString() ?? "",
    blade: initial.grindKind === "blade" && initial.grindValue != null ? String(initial.grindValue) : "",
    time: initial.totalTimeSec > 0 ? clock(initial.totalTimeSec) : "",
  }));
  const settings = useSettings();
  const grinders = useGrinders({ includeArchived: true });
  const beans = useBeans({ includeArchived: true });
  const grinder = grinders.find((g) => g.id === log.grinderId) ?? null;
  const bean = beans.find((b) => b.id === log.beanId) ?? null;
  const recipe = useRecipe(log.recipeId);
  const brewer = brewerOf(log, recipe);
  const sections = reviewSections(brewer);
  const labels = methodLabels(brewer?.method ?? "pourover");
  const espresso = sections.style === "espresso";
  const [more, setMore] = useState(false);
  const plan = reviewPlan(sections, settings.reviewMode, more);
  const settled = useTransitionSettled();
  const leaving = useRef(false);
  const [discarding, setDiscarding] = useState(false);
  const ai = useAiSettings();
  // A service that still needs its test stays on the plain Save: the log entry says "TEST FIRST".
  const canAnalyse = ai.ready && !ai.needsTest && log.id != null;

  // ------------------------------------------------------------ autosave
  const dirty = useRef(false);
  const latest = useRef(log);
  useLayoutEffect(() => {
    latest.current = log;
  });
  const flush = () => {
    if (!dirty.current) return Promise.resolve();
    dirty.current = false;
    return repo.updateBrew(latest.current);
  };
  useEffect(() => {
    if (!dirty.current) return;
    const id = setTimeout(() => afterTransition(() => void flush()), 400);
    return () => clearTimeout(id);
  }, [log]);
  useEffect(() => {
    // A backgrounded tab can be discarded without another event, so nothing stays unsaved in one.
    const save = () => {
      if (document.visibilityState === "hidden") void flush();
    };
    const leavePage = () => void flush();
    document.addEventListener("visibilitychange", save);
    window.addEventListener("pagehide", leavePage);
    return () => {
      document.removeEventListener("visibilitychange", save);
      window.removeEventListener("pagehide", leavePage);
      // Unmount: save whatever is still pending.
      void flush();
    };
    // Mount-only: flush reads refs, so the first closure stays right.
  }, []);

  const edit = (fn: (l: BrewRow) => BrewRow) => {
    dirty.current = true;
    setLog(fn);
  };

  const done = async () => {
    if (leaving.current) return;
    leaving.current = true;
    dirty.current = true;
    await flush();
    leave(log.uid);
  };

  /** ReviewViewModel.saveAndAnalyse: save, start the analysis, land on the brew where it shows running. */
  const saveAndAnalyse = async () => {
    if (leaving.current || log.id == null) return;
    leaving.current = true;
    dirty.current = true;
    await flush();
    startBrewAnalysis(log.id);
    const s = router.get();
    const below = s.stack[s.stack.length - 2];
    const detail = `/log/${encodeURIComponent(log.uid)}`;
    if (below && pathOf(below) === detail) nav.back();
    else nav.replace(detail);
  };

  /** Escape: nothing changed leaves at once; otherwise ask, and a discard puts the brew back as the timer left it. */
  const askDiscard = () => {
    if (leaving.current) return;
    if (JSON.stringify(log) === JSON.stringify(initial)) {
      leaving.current = true;
      leave(log.uid);
    } else setDiscarding(true);
  };
  const discard = async () => {
    if (leaving.current) return;
    leaving.current = true;
    dirty.current = false;
    await repo.updateBrew(initial);
    leave(log.uid);
  };

  // ------------------------------------------------------------ keyboard (§2.7, §3.6)
  // One handler for the ten digit keys: the key itself says the score (0 is 10).
  const onScoreKey = (e: KeyboardEvent) => {
    const d = Number(e.key);
    if (Number.isInteger(d)) edit((l) => ({ ...l, overall: d === 0 ? 10 : d }));
  };
  const keys: Binding[] = [
    { keys: "mod+enter", label: "Save brew", group: "Review", run: () => void done() },
    {
      keys: "escape",
      label: "Discard changes",
      group: "Review",
      run: () => {
        // In a field, Escape first lets go of it; the next one asks.
        const a = document.activeElement;
        if (isTypingTarget(a)) (a as HTMLElement).blur();
        else askDiscard();
      },
    },
    ...SCORE_KEYS.map((k): Binding => ({ keys: k, label: "Overall score 1 to 10", display: "1-9, 0", group: "Review", hidden: k !== "1", run: onScoreKey })),
  ];
  useShortcuts("review", keys, !discarding);

  const scale = useGrindScale(grinder);
  const method = recipe?.brewer.method ?? brewer?.method ?? null;
  const grindFlag = grindWarning(log, grinder, scale, method);
  const timeIgnored = useMemo(() => distrustedFields(checkBrew(log, recipe, grinder, bean), log.id).has(Fields.TIME), [log, recipe, grinder, bean]);
  const read = quickRead(log, recipe, { brewer, hasScale: settings.hasScale, fahrenheit: unitsOf(settings).fahrenheit, timeIgnored });
  const advancedHas = log.tdsPercent != null || (plan.inCupInAdvanced && log.beverageG != null);

  return (
    <div className={v.page}>
      <div className={v.grid}>
        <div className={v.left}>
          <div className={v.o1}>
            <NumbersHeader
              log={log}
              text={text}
              setText={setText}
              edit={edit}
              grinder={grinder}
              target={recipe ? totalSec(recipe) : 0}
              grindFlag={grindFlag}
              labels={labels}
              espresso={espresso}
            />
          </div>
          {recipe && !espresso && (
            <div className={v.o2}>
              <Trip log={log} recipe={recipe} />
            </div>
          )}
          {log.beanLabel.trim() && (
            <div className={v.o3}>
              <SectionLabel text="COFFEE" />
              <SoftCard>
                <p className="t-title-m">{log.beanLabel}</p>
                {log.daysOffRoast != null && (
                  <p className="t-body-s ink-mid">
                    <Term id="days-off-roast">{daysOffRoastLabel(log.daysOffRoast)}</Term>
                  </p>
                )}
              </SoftCard>
            </div>
          )}
          <div className={v.o7}>
            <SectionLabel text="PHOTOS" />
            <SoftCard>
              <p className="t-body-s ink-mid">{sections.bed ? "A photo of the bed says more than any shape on this list." : "A photo of the cup helps you remember this one."}</p>
              <div className={v.gap12} />
              {settled && log.id != null ? <PhotoStrip brewId={log.id} kinds={["bed", "cup"]} editable /> : <div className={v.photoPlaceholder} aria-hidden="true" />}
            </SoftCard>
          </div>
        </div>

        <div className={v.right}>
          <div className={v.o4}>
            <OverallSection log={log} edit={edit} />
          </div>
          <div className={v.o5}>
            <QuickToggles log={log} edit={edit} />
          </div>
          <div className={v.o6}>
            {plan.moreDetail && (
              <Disclosure open={more} onToggle={() => setMore((m) => !m)} title="More detail" subtitle={sectionsWords(sections)} />
            )}
            {plan.taste && <TasteSection log={log} edit={edit} />}
            {plan.drawdown && <DrawdownSection log={log} text={text} setText={setText} edit={edit} retained={plan.retained} />}
            {plan.bed && <BedSection log={log} edit={edit} />}
          </div>
          <div className={v.o8}>
            <AdvancedSection log={log} text={text} setText={setText} edit={edit} plan={plan} startOpen={advancedHas || settings.reviewMode === "detailed"} />
          </div>
          <div className={v.o9}>
            <NotesSection log={log} edit={edit} />
          </div>
          <div className={v.o10}>
            <SectionLabel text="FIRST READ · OFFLINE" />
            <SoftCard>
              {read.map((line, i) => (
                <div key={i}>
                  {i > 0 && <HairLine />}
                  <p className={v.read}>{line}</p>
                </div>
              ))}
              <p className={`t-body-s ink-low ${v.readFoot}`}>
                {canAnalyse ? "Offline pattern matching, not a verdict. Save & analyse for a real read." : "Offline pattern matching, not a verdict. Open it in the log to analyse it, or set up analysis in Gear › App."}
              </p>
            </SoftCard>
            <div className={v.after}>
              <QuietButton
                text="Open in log"
                icon="History"
                onClick={() => {
                  if (leaving.current) return;
                  leaving.current = true;
                  dirty.current = true;
                  void flush().then(() => nav.replace(`/log/${encodeURIComponent(log.uid)}`));
                }}
              />
            </div>
          </div>

          <div className={`${v.o11} ${v.sticky}`}>
            {canAnalyse ? (
              <div className={v.stickyRow}>
                <SecondaryButton text="Save brew" height={56} onCanvas className={v.stickySave} onClick={() => void done()} />
                <PrimaryButton text="Save and analyse" className={v.stickyAnalyse} onClick={() => void saveAndAnalyse()} />
              </div>
            ) : (
              <PrimaryButton text="Save brew" onClick={() => void done()} className={v.saveOne} />
            )}
          </div>
        </div>
      </div>
      {discarding && (
        <ConfirmSheet
          title="Discard your changes?"
          body="The brew stays in the log as it was when the timer stopped."
          confirmText="Keep editing"
          cancelText="Discard"
          safe="confirm"
          onClose={() => setDiscarding(false)}
          onConfirm={() => undefined}
          onCancel={() => void discard()}
        />
      )}
    </div>
  );
}

const SCORE_KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"] as const;

type TextState = { drawdown: string; beverage: string; tds: string; grind: string; blade: string; time: string };
type SetText = (fn: (t: TextState) => TextState) => void;
type Edit = (fn: (l: BrewRow) => BrewRow) => void;

function sectionsWords(s: { drawdown: boolean; bed: boolean }): string {
  if (s.drawdown) return "Taste, flow, the coffee bed";
  if (s.bed) return "Taste, the coffee bed";
  return "Taste and the full list of faults";
}

function Warning({ text }: { text: string }) {
  return (
    <p className={`t-body-s ${v.warning}`} role="status">
      {text}
    </p>
  );
}

/** A section label that explains its word (§4.5). */
function TermLabel({ id, text }: { id: string; text: string }) {
  return (
    <div className={v.termLabel}>
      <h2 className="eyebrow">
        <Term id={id}>{text}</Term>
      </h2>
    </div>
  );
}

function Disclosure({ open, onToggle, title, subtitle }: { open: boolean; onToggle: () => void; title: string; subtitle: string }) {
  return (
    <button type="button" className={v.disclosure} aria-expanded={open} onClick={onToggle}>
      <span className={v.disclosureText}>
        <span className="t-title-m">{title}</span>
        <span className="t-body-s ink-mid">{subtitle}</span>
      </span>
      <span className={v.chevron} data-open={open || undefined} aria-hidden="true">
        <Icon name="ChevronLeft" size={20} />
      </span>
    </button>
  );
}

// ------------------------------------------------------------------ numbers header

function NumbersHeader({
  log,
  text,
  setText,
  edit,
  grinder,
  target,
  grindFlag,
  labels,
  espresso,
}: {
  log: BrewRow;
  text: TextState;
  setText: SetText;
  edit: Edit;
  grinder: GrinderRow | null;
  target: number;
  grindFlag: string | null;
  labels: ReturnType<typeof methodLabels>;
  espresso: boolean;
}) {
  // Opens by itself when the brew arrived with a grind the analysis would ignore, which is the
  // only place it can be corrected.
  const [editing, setEditing] = useState(grindFlag != null);
  const [dialTyping, setDialTyping] = useState(false);
  const ts = timeStatus(log.totalTimeSec, target);
  const preground = log.grindKind === "preground";
  const blade = log.grindKind === "blade";
  const inClicks = !preground && !blade && (grinder ? grinder.settingStyle === "clicks" : log.grindClicks != null);
  const scale = useGrindScale(grinder);
  const parsed = parseClock(text.time);
  const timeValid = !text.time.trim() || parsed != null;
  const yieldG = log.beverageG ?? log.waterG;
  const timeText = (sec: number) => (labels.longTimes ? formatDuration(sec) : clock(sec));

  const setClicks = (clicks: number | null) =>
    edit((l) => ({ ...l, grindClicks: clicks, grindSetting: clicks != null ? (grinder ? describeClicks(grinder, clicks) : `${clicks} clicks`) : "" }));

  const grindCell = {
    label: "GRIND",
    value: log.grindClicks != null ? String(log.grindClicks) : log.grindSetting.trim() || null,
    unit: log.grindClicks != null ? "clk" : null,
    textValue: log.grindClicks == null,
    status: grindFlag ? ("attention" as const) : null,
    sub: grindFlag ? "CHECK" : null,
    subStatus: grindFlag ? ("attention" as const) : null,
  };
  const timeCell = { label: labels.time, value: log.totalTimeSec > 0 ? timeText(log.totalTimeSec) : null, status: ts[0] };
  // Espresso reads DOSE, YIELD, RATIO, SHOT TIME (§4.9); the grind moves to the edit box.
  const cells = espresso
    ? [
        { label: "DOSE", value: grams(log.doseG), unit: "g" },
        { label: labels.water, value: grams(yieldG), unit: "g" },
        { label: "RATIO", value: ratio(log.doseG, yieldG), textValue: true },
        timeCell,
      ]
    : [{ label: "DOSE", value: grams(log.doseG), unit: "g" }, { label: labels.water, value: grams(log.waterG), unit: "g" }, grindCell, timeCell];

  return (
    <>
      <div className={v.headTop}>
        <EyebrowLabel text="HOW WAS IT?" />
        <h1 className={`t-headline-l ${v.title}`}>{log.recipeName.trim() || "That brew"}</h1>
      </div>
      <div className={v.gridCard}>
        <InfoGrid compact columns={4} cells={cells} />
        {editing && (
          <div className={v.editBox}>
            <HairLine />
            <div className={v.fieldRow}>
              {preground ? (
                <div className={v.grow}>
                  <FieldWell label="GRIND" value={log.grindSetting} onChange={(s) => edit((l) => ({ ...l, grindSetting: s }))} helper="Pre-ground: as it came in the bag." />
                </div>
              ) : blade ? (
                <div className={v.grow}>
                  <FieldWell
                    label="GRIND FOR"
                    value={text.blade}
                    suffix="s"
                    numeric
                    onChange={(s) => {
                      const d = digitsOnly(s, 3);
                      setText((t) => ({ ...t, blade: d }));
                      edit((l) => ({ ...l, grindValue: d ? Number(d) : null, grindSetting: d ? `${d} s` : "" }));
                    }}
                  />
                </div>
              ) : inClicks ? (
                dialTyping && scale ? (
                  <div className={v.grow}>
                    <DialReadingInput
                      scale={scale}
                      value={log.grindClicks}
                      onValue={(n) => {
                        const c = n == null ? null : Math.round(n);
                        setText((t) => ({ ...t, grind: c?.toString() ?? "" }));
                        setClicks(c);
                      }}
                    />
                  </div>
                ) : (
                  <div className={v.grow}>
                    <FieldWell
                      label="GRIND"
                      value={text.grind}
                      numeric
                      suffix="clicks"
                      helper={log.grindClicks != null && scale ? (dialHelper(describe(scale, log.grindClicks)) ?? undefined) : undefined}
                      onChange={(s) => {
                        const d = digitsOnly(s, 3);
                        setText((t) => ({ ...t, grind: d }));
                        setClicks(d ? Number(d) : null);
                      }}
                    />
                  </div>
                )
              ) : (
                <div className={v.grow}>
                  <FieldWell label="GRIND" value={log.grindSetting} placeholder="setting" onChange={(s) => edit((l) => ({ ...l, grindSetting: s }))} />
                </div>
              )}
              <div className={v.grow}>
                <FieldWell
                  label={labels.time === "SHOT TIME" ? "SHOT TIME" : "TOTAL TIME"}
                  value={text.time}
                  placeholder="m:ss"
                  numeric
                  error={timeValid ? null : "Use m:ss, like 3:28"}
                  // Echo the reading when it was typed without a colon, e.g. 328 → 3:28.
                  helper={parsed != null && clock(parsed) !== text.time ? `reads as ${clock(parsed)}` : undefined}
                  onChange={(s) => {
                    const cleaned = cleanClockText(s);
                    setText((t) => ({ ...t, time: cleaned }));
                    // A half-typed value that cannot be read leaves the saved time alone.
                    const sec = cleaned.trim() ? parseClock(cleaned) : 0;
                    if (sec != null) edit((l) => ({ ...l, totalTimeSec: sec }));
                  }}
                />
              </div>
              {espresso && (
                <div className={v.grow}>
                  <FieldWell
                    label="YIELD"
                    value={text.beverage}
                    suffix="g"
                    numeric
                    helper="In the cup"
                    onChange={(s) => {
                      setText((t) => ({ ...t, beverage: s }));
                      const bev = parseDecimal(s);
                      edit((l) => ({ ...l, beverageG: bev, extractionYield: BrewMath.extractionYield(l.doseG, bev, l.tdsPercent) }));
                    }}
                  />
                </div>
              )}
            </div>
            {inClicks && scale && scale.dial.kind !== "plain" && (
              <QuietButton text={dialTyping ? "Type total clicks" : "Type it as the dial reads"} onClick={() => setDialTyping((d) => !d)} className={v.quietLeft} />
            )}
            {grindFlag && <Warning text={grindFlag} />}
          </div>
        )}
      </div>
      <div className={v.timeRow}>
        <div className={v.grow}>
          <StatusLine status={ts[0]} lead={ts[1]} rest={target > 0 && log.totalTimeSec > 0 ? `${timeText(log.totalTimeSec)} against ${timeText(target)}` : undefined} />
        </div>
        <QuietButton text={editing ? "Done" : "Edit"} icon={editing ? undefined : "Edit"} onClick={() => setEditing((e) => !e)} />
      </div>
      {espresso && log.grindSetting.trim() && !editing && <p className="t-body-s ink-mid">Grind: {log.grindSetting}</p>}
    </>
  );
}

/** The trip actual against planned (§3.6 left column). */
function Trip({ log, recipe }: { log: BrewRow; recipe: Recipe }) {
  const total = Math.max(1, totalSec(recipe));
  const ts = timeStatus(log.totalTimeSec, total);
  const has = log.totalTimeSec > 0;
  return (
    <>
      <SectionLabel text="THE BREW" />
      <SoftCard>
        <JourneyLine
          nodes={journeyNodes(recipe)}
          style="historical"
          progress={1}
          totalSec={total}
          startLabel="0:00"
          endLabel={clock(total)}
          actualEnd={has ? log.totalTimeSec / total : null}
          endStatus={has ? ts[0] : null}
          actualLabel={has ? `${clock(log.totalTimeSec)} · ${ts[0] === "good" ? "on time" : signedClock(log.totalTimeSec - total)}` : null}
          label={has ? `Planned ${clock(total)}, finished at ${clock(log.totalTimeSec)}` : `Planned ${clock(total)}`}
        />
      </SoftCard>
    </>
  );
}

// ------------------------------------------------------------------ overall, quick toggles, taste

function OverallSection({ log, edit }: { log: BrewRow; edit: Edit }) {
  const rated = log.overall > 0;
  const st = scoreStatus(log.overall);
  return (
    <>
      <SectionLabel text="OVERALL" />
      <SoftCard>
        <div className={v.overallTop}>
          <span className={v.nwu} style={{ color: rated ? statusColorVar(st) : "var(--ink-low)" }}>
            <TickingNumber value={rated ? String(log.overall) : "—"} numeral="num-display" label={rated ? `${log.overall} of 10` : "Not rated"} />
            <span className={`num-unit ${v.unitXL}`}>/10</span>
          </span>
          <span className={v.grow} />
          {rated ? <StatusChip status={st} text={cupLabel(log.overall)} /> : <span className="t-body-s ink-low">Tap a dot, or press 1 to 0</span>}
        </div>
        <div className={v.gap12} />
        <DotRating value={log.overall} onChange={(n) => edit((l) => ({ ...l, overall: n }))} />
      </SoftCard>
    </>
  );
}

/** "ANYTHING OFF?" (§4.4): four plain toggles that store faults; the sliders stay untouched. */
function QuickToggles({ log, edit }: { log: BrewRow; edit: Edit }) {
  return (
    <>
      <SectionLabel text="ANYTHING OFF?" />
      <div className={v.chips} role="group" aria-label="Anything off?">
        {QUICK_FAULTS.map((q) => (
          <SelectableChip key={q.label} text={q.label} selected={quickOn(log.faults, q.label)} onClick={() => edit((l) => ({ ...l, faults: toggleQuick(l.faults, q.label) }))} />
        ))}
      </div>
    </>
  );
}

function TasteSection({ log, edit }: { log: BrewRow; edit: Edit }) {
  const descriptors = new Set(log.descriptors);
  const faults = new Set(log.faults);
  return (
    <>
      <SectionLabel text="TASTE" />
      <SoftCard>
        <div className={v.sliders}>
          {TASTE_AXES.map((a) => (
            <AxisSlider key={a.key} label={a.label} hint={a.range} value={log.taste[a.key]} onChange={(n) => edit((l) => ({ ...l, taste: withTaste(l.taste, a.key, n) }))} />
          ))}
        </div>
        {/* Only once a score is in: before that, untouched sliders are a form not yet filled. */}
        {log.overall > 0 && tasteUntouched(log.taste) && <Warning text="Move at least one slider, or the analysis will treat taste as missing." />}
        <span className={`micro ink-low ${v.chipLabel}`}>TASTED OF</span>
        <ChipFlow label="Tasted of" options={Descriptors.POSITIVE} selected={descriptors} onToggle={(o) => edit((l) => ({ ...l, descriptors: toggleIn(l.descriptors, o) }))} />
        <span className={`micro ink-low ${v.chipLabel}`}>FAULTS</span>
        <ChipFlow label="Faults" options={Descriptors.FAULTS} selected={faults} onToggle={(o) => edit((l) => ({ ...l, faults: toggleIn(l.faults, o) }))} />
      </SoftCard>
    </>
  );
}

// ------------------------------------------------------------------ drawdown

function InCupField({ text, setText, edit }: { text: TextState; setText: SetText; edit: Edit }) {
  return (
    <FieldWell
      label="IN CUP"
      value={text.beverage}
      suffix="g"
      numeric
      onChange={(s) => {
        setText((t) => ({ ...t, beverage: s }));
        const bev = parseDecimal(s);
        edit((l) => ({ ...l, beverageG: bev, extractionYield: BrewMath.extractionYield(l.doseG, bev, l.tdsPercent) }));
      }}
    />
  );
}

function DrawdownSection({ log, text, setText, edit, retained }: { log: BrewRow; text: TextState; setText: SetText; edit: Edit; retained: boolean }) {
  const expected = BrewMath.expectedBeverageG(log.doseG, log.waterG);
  return (
    <>
      <TermLabel id="drawdown" text="DRAWDOWN" />
      <SoftCard>
        <div className={v.fieldRow}>
          <div className={v.grow}>
            <FieldWell
              label="DRAWDOWN"
              value={text.drawdown}
              suffix="s"
              numeric
              onChange={(s) => {
                const d = digitsOnly(s, 4);
                setText((t) => ({ ...t, drawdown: d }));
                edit((l) => ({ ...l, drawdownSec: d ? Number(d) : null }));
              }}
            />
          </div>
          <div className={v.grow}>
            <InCupField text={text} setText={setText} edit={edit} />
          </div>
        </div>
        <span className={`micro ink-low ${v.chipLabel}`}>FLOW</span>
        <div className={v.chips} role="group" aria-label="Flow">
          {FLOW_FEEL.map((f) => (
            <SelectableChip key={f.id} text={f.label} selected={log.flowFeel === f.id} status={flowStatus(f.id) ?? undefined} onClick={() => edit((l) => ({ ...l, flowFeel: l.flowFeel === f.id ? null : f.id }))} />
          ))}
        </div>
        <div className={v.gap8} />
        <KeyValueRow k="Expected in cup" value={`~${grams(expected)} g`} />
        {retained && log.beverageG != null && <KeyValueRow k="Retained by the bed" value={`${grams(log.waterG - log.beverageG)} g`} />}
      </SoftCard>
    </>
  );
}

// ------------------------------------------------------------------ the bed

function BedSection({ log, edit }: { log: BrewRow; edit: Edit }) {
  const info = bedShapeInfo(log.bedShape);
  const set = (shape: BedShape) => edit((l) => ({ ...l, bedShape: l.bedShape === shape ? null : shape }));
  return (
    <>
      <TermLabel id="bed" text="THE BED" />
      <SoftCard>
        <div className={v.bedGrid} role="radiogroup" aria-label="The bed">
          {BED_SHAPES.map((s) => {
            const selected = log.bedShape === s.id;
            const st = bedStatus(s.id);
            const ink = selected ? (st ? statusColorVar(st) : "var(--ink-high)") : "var(--ink-mid)";
            const fill = selected ? (st ? statusContainerVar(st) : "var(--surface3)") : "var(--surface2)";
            return (
              <button
                key={s.id}
                type="button"
                role="radio"
                aria-checked={selected}
                className={v.bedTile}
                data-selected={selected || undefined}
                style={{ background: fill, borderColor: selected ? ink : "transparent" }}
                onClick={() => set(s.id)}
              >
                <BedShapeIcon shape={s.id} size={28} tint={ink} cut={fill} decorative />
                <span className={`micro ${v.bedLabel}`}>{toCaps(s.label)}</span>
              </button>
            );
          })}
        </div>
        {info && <p className={`t-body-m ${v.reading}`}>{info.reading}</p>}

        <span className={`micro ink-low ${v.chipLabel}`}>SURFACE</span>
        <div className={v.chips} role="group" aria-label="Surface">
          {SLUDGE.map((s) => (
            <SelectableChip key={s.id} text={s.label} selected={log.sludge === s.id} status={sludgeStatus(s.id) ?? undefined} onClick={() => edit((l) => ({ ...l, sludge: l.sludge === s.id ? null : s.id }))} />
          ))}
        </div>
        <span className={`micro ink-low ${v.chipLabel}`}>
          <Term id="channelling">CHANNELLING</Term>
        </span>
        <div className={v.chips} role="group" aria-label="Channelling">
          {CHANNELING.map((c) => (
            <SelectableChip key={c.id} text={c.label} selected={log.channeling === c.id} status={channelingStatus(c.id) ?? undefined} onClick={() => edit((l) => ({ ...l, channeling: l.channeling === c.id ? null : c.id }))} />
          ))}
        </div>
        <div className={v.gap16} />
        <FieldWell label="NOTES ON THE BED" value={log.bedNotes} multiline placeholder="Anything else you saw" onChange={(s) => edit((l) => ({ ...l, bedNotes: s }))} />
      </SoftCard>
    </>
  );
}

// ------------------------------------------------------------------ advanced, notes

/** TDS, beverage weight and extraction: always behind "Advanced" (§4.4). */
function AdvancedSection({ log, text, setText, edit, plan, startOpen }: { log: BrewRow; text: TextState; setText: SetText; edit: Edit; plan: ReturnType<typeof reviewPlan>; startOpen: boolean }) {
  const [open, setOpen] = useState(startOpen);
  const ey = log.extractionYield;
  return (
    <>
      <div className={v.gap16} />
      <Disclosure open={open} onToggle={() => setOpen((o) => !o)} title="Advanced" subtitle="TDS and extraction, if you have a refractometer" />
      {open && (
        <SoftCard>
          <div className={v.fieldRow}>
            <div className={v.grow}>
              <FieldWell
                label="TDS"
                value={text.tds}
                placeholder="optional"
                suffix="%"
                numeric
                helper="Only if you have a refractometer."
                onChange={(s) => {
                  setText((t) => ({ ...t, tds: s }));
                  const tds = parseDecimal(s);
                  edit((l) => ({ ...l, tdsPercent: tds, extractionYield: BrewMath.extractionYield(l.doseG, l.beverageG, tds) }));
                }}
              />
            </div>
            {plan.inCupInAdvanced && (
              <div className={v.grow}>
                <InCupField text={text} setText={setText} edit={edit} />
              </div>
            )}
          </div>
          <p className="t-body-s ink-mid">
            <Term id="tds">TDS</Term> and <Term id="extraction">extraction</Term>: what a refractometer reading tells you.
          </p>
          {ey != null && <KeyValueRow k="Extraction yield" value={`${oneDp(ey)} %`} />}
          {plan.inCupInAdvanced && plan.retained && log.beverageG != null && <KeyValueRow k="Retained by the bed" value={`${grams(log.waterG - log.beverageG)} g`} />}
        </SoftCard>
      )}
    </>
  );
}

function NotesSection({ log, edit }: { log: BrewRow; edit: Edit }) {
  return (
    <>
      <SectionLabel text="NOTES" />
      <SoftCard>
        <FieldWell label="NOTES" value={log.notes} multiline placeholder="What stood out" onChange={(s) => edit((l) => ({ ...l, notes: s }))} />
        <div className={v.gap12} />
        <FieldWell label="NEXT TIME" value={log.nextTime} placeholder="The one thing to change" onChange={(s) => edit((l) => ({ ...l, nextTime: s }))} />
      </SoftCard>
    </>
  );
}
