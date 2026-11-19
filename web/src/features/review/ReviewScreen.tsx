/**
 * Port of ReviewScreen.kt + ReviewViewModel.kt (web-spec §11 Review): the numbers header with the
 * grind and time correctable in place, OVERALL, TASTE, DRAWDOWN, THE BED, PHOTOS (A§9.2), NOTES
 * and the offline FIRST READ. Edits autosave, debounced 400 ms and run after any transition
 * settles, so a Dexie write never re-renders a list mid-flight (rule 5).
 */
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { nav, router } from "@/app/router";
import { pathOf } from "@/app/routes";
import { afterTransition, useTransitionSettled } from "@/app/transitionState";
import { BedShapeIcon, Dripper } from "@/art";
import { repo } from "@/data/repo";
import type { BrewRow, GrinderRow } from "@/data/schema";
import { useBrew, useGrinders, useRecipe } from "@/data/store";
import {
  AxisSlider,
  ChipFlow,
  DotRating,
  EyebrowLabel,
  FieldWell,
  HairLine,
  KeyValueRow,
  PrimaryButton,
  QuietButton,
  SecondaryButton,
  SectionLabel,
  SelectableChip,
  SoftCard,
  StatusChip,
  StatusLine,
  TickingNumber,
  toCaps,
} from "@/design/components";
import { BED_SHAPES, BrewMath, CHANNELING, Descriptors, FLOW_FEEL, SLUDGE, TASTE_AXES, bedShapeInfo, withTaste, type BedShape } from "@/domain/core/evaluation";
import { clock, grams, oneDp } from "@/domain/core/format";
import { quickRead } from "@/domain/core/quickRead";
import { totalSec } from "@/domain/core/recipe";
import { bedStatus, channelingStatus, cupLabel, flowStatus, scoreStatus, statusColorVar, statusContainerVar, sludgeStatus, timeStatus } from "@/domain/core/status";
import { describe } from "@/domain/grind";
import { startBrewAnalysis, useAiSettings } from "@/features/ai";
import { describeClicks } from "@/features/brew/setup";
import { DialReadingInput, useGrindScale } from "@/features/grind";
import { PhotoStrip } from "@/features/photos";
import { InfoGrid } from "@/viz";
import { cleanClockText, dialHelper, digitsOnly, grindWarning, parseClock, parseDecimal, tasteUntouched, toggleIn } from "./reviewModel";
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
    time: initial.totalTimeSec > 0 ? clock(initial.totalTimeSec) : "",
  }));
  const grinders = useGrinders({ includeArchived: true });
  const grinder = grinders.find((g) => g.id === log.grinderId) ?? null;
  const recipe = useRecipe(log.recipeId);
  const settled = useTransitionSettled();
  const leaving = useRef(false);
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

  const scale = useGrindScale(grinder);
  const method = recipe?.brewer.method ?? null;
  const grindFlag = grindWarning(log, grinder, scale, method);

  return (
    <div className={`column ${v.form}`}>
      <NumbersHeader log={log} text={text} setText={setText} edit={edit} grinder={grinder} target={recipe ? totalSec(recipe) : 0} grindFlag={grindFlag} />
      <OverallSection log={log} edit={edit} />
      <TasteSection log={log} edit={edit} />
      <DrawdownSection log={log} text={text} setText={setText} edit={edit} />
      <BedSection log={log} edit={edit} />

      <SectionLabel text="PHOTOS" />
      <SoftCard>
        <p className="t-body-s ink-mid">A photo of the bed says more than any shape on this list.</p>
        <div className={v.gap12} />
        {settled && log.id != null ? <PhotoStrip brewId={log.id} kinds={["bed", "cup"]} editable /> : <div className={v.photoPlaceholder} aria-hidden="true" />}
      </SoftCard>

      <NotesSection log={log} text={text} setText={setText} edit={edit} />

      <SectionLabel text="FIRST READ · OFFLINE" />
      <SoftCard>
        {quickRead(log, recipe).map((line, i) => (
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

      <div className={v.sticky}>
        {canAnalyse ? (
          <div className={v.stickyRow}>
            <SecondaryButton text="Save" height={56} onCanvas className={v.stickySave} onClick={() => void done()} />
            <PrimaryButton text="Save & analyse" className={v.stickyAnalyse} onClick={() => void saveAndAnalyse()} />
          </div>
        ) : (
          <PrimaryButton text="Save brew" onClick={() => void done()} />
        )}
      </div>
    </div>
  );
}

type TextState = { drawdown: string; beverage: string; tds: string; grind: string; time: string };
type SetText = (fn: (t: TextState) => TextState) => void;
type Edit = (fn: (l: BrewRow) => BrewRow) => void;

function Warning({ text }: { text: string }) {
  return (
    <p className={`t-body-s ${v.warning}`} role="status">
      {text}
    </p>
  );
}

// ------------------------------------------------------------------ numbers header

function NumbersHeader({ log, text, setText, edit, grinder, target, grindFlag }: { log: BrewRow; text: TextState; setText: SetText; edit: Edit; grinder: GrinderRow | null; target: number; grindFlag: string | null }) {
  // Opens by itself when the brew arrived with a grind the analysis would ignore, which is the
  // only place it can be corrected.
  const [editing, setEditing] = useState(grindFlag != null);
  const [dialTyping, setDialTyping] = useState(false);
  const ts = timeStatus(log.totalTimeSec, target);
  const inClicks = grinder ? grinder.settingStyle === "clicks" : log.grindClicks != null;
  const scale = useGrindScale(grinder);
  const parsed = parseClock(text.time);
  const timeValid = !text.time.trim() || parsed != null;

  const setClicks = (clicks: number | null) =>
    edit((l) => ({ ...l, grindClicks: clicks, grindSetting: clicks != null ? (grinder ? describeClicks(grinder, clicks) : `${clicks} clicks`) : "" }));

  return (
    <>
      <div className={v.headTop}>
        <EyebrowLabel text="HOW WAS IT?" />
        <h1 className={`t-headline-l ${v.title}`}>{log.recipeName.trim() || "That brew"}</h1>
      </div>
      <div className={v.gridCard}>
        <InfoGrid
          compact
          columns={4}
          cells={[
            { label: "DOSE", value: grams(log.doseG), unit: "g" },
            { label: "WATER", value: grams(log.waterG), unit: "g" },
            {
              label: "GRIND",
              value: log.grindClicks != null ? String(log.grindClicks) : log.grindSetting.trim() || null,
              unit: log.grindClicks != null ? "clk" : null,
              textValue: log.grindClicks == null,
              status: grindFlag ? "attention" : null,
              sub: grindFlag ? "CHECK" : null,
              subStatus: grindFlag ? "attention" : null,
            },
            { label: "TIME", value: log.totalTimeSec > 0 ? clock(log.totalTimeSec) : null, status: ts[0] },
          ]}
        />
        {editing && (
          <div className={v.editBox}>
            <HairLine />
            <div className={v.fieldRow}>
              {inClicks ? (
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
                  label="TOTAL TIME"
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
          <StatusLine status={ts[0]} lead={ts[1]} rest={target > 0 && log.totalTimeSec > 0 ? `${clock(log.totalTimeSec)} against ${clock(target)}` : undefined} />
        </div>
        <QuietButton text={editing ? "Done" : "Edit"} icon={editing ? undefined : "Edit"} onClick={() => setEditing((e) => !e)} />
      </div>
    </>
  );
}

// ------------------------------------------------------------------ overall, taste

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
          {rated ? <StatusChip status={st} text={cupLabel(log.overall)} /> : <span className="t-body-s ink-low">Tap a dot</span>}
        </div>
        <div className={v.gap12} />
        <DotRating value={log.overall} onChange={(n) => edit((l) => ({ ...l, overall: n }))} />
      </SoftCard>
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

function DrawdownSection({ log, text, setText, edit }: { log: BrewRow; text: TextState; setText: SetText; edit: Edit }) {
  const expected = BrewMath.expectedBeverageG(log.doseG, log.waterG);
  return (
    <>
      <SectionLabel text="DRAWDOWN" />
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
        {log.beverageG != null && <KeyValueRow k="Retained by the bed" value={`${grams(log.waterG - log.beverageG)} g`} />}
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
      <SectionLabel text="THE BED" />
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
        <span className={`micro ink-low ${v.chipLabel}`}>CHANNELLING</span>
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

// ------------------------------------------------------------------ notes

function NotesSection({ log, text, setText, edit }: { log: BrewRow; text: TextState; setText: SetText; edit: Edit }) {
  const ey = log.extractionYield;
  return (
    <>
      <SectionLabel text="NOTES" />
      <SoftCard>
        <FieldWell label="NOTES" value={log.notes} multiline placeholder="What stood out" onChange={(s) => edit((l) => ({ ...l, notes: s }))} />
        <div className={v.gap12} />
        <FieldWell label="NEXT TIME" value={log.nextTime} placeholder="The one thing to change" onChange={(s) => edit((l) => ({ ...l, nextTime: s }))} />
        <div className={v.gap12} />
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
        {ey != null && <KeyValueRow k="Extraction yield" value={`${oneDp(ey)} %`} />}
      </SoftCard>
    </>
  );
}
