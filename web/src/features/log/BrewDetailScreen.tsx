/**
 * Port of ui/screens/history/BrewDetailScreen.kt: the boarding pass, the answer before the details
 * (verdict or timing), the analysis, THE BREW (planned against actual), coffee and gear, THE BED,
 * photos, taste and notes. The first frame seeds from the hot store (rule 5); the heavy sections
 * (analysis, journey, photos) sit behind fixed-height placeholders until the flight lands.
 *
 * v4 §3.8: pushed (compact, medium) or in Log's detail pane (expanded up, no Back). From an 880
 * wide pane it splits into the main column and a 340 supporting column (photos, analysis, Brew
 * again). The header carries Brew again and Compare with… (in an overflow menu when narrow).
 */
import { useLiveQuery } from "dexie-react-hooks";
import { useMemo, useRef, useState, type ReactNode } from "react";
import { usePane, useSelectedPath } from "@/app/panes";
import { nav } from "@/app/router";
import { pathOf } from "@/app/routes";
import { useTransitionSettled } from "@/app/transitionState";
import { BedShapeIcon } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { useCatalog } from "@/catalog/useCatalog";
import { db } from "@/data/db";
import type { BrewRow, GrinderRow } from "@/data/schema";
import { useBrew, useStore } from "@/data/store";
import { DetailTopBar, EyebrowLabel, HairLine, IconWell, MenuButton, NumberWithUnit, PerforatedDivider, PillButton, QuietPanel, SecondaryButton, SectionLabel, SoftCard, StatusChip, StatusLine, beanShortName, useContextMenu } from "@/design/components";
import { methodLabels, reviewSections, type BrewMethod, type ReviewSections } from "@/domain/core/brewer";
import { bedShapeInfo, bedShapeLabel, channelingLabel, sludgeLabel } from "@/domain/core/evaluation";
import { clock, grams, halves, oneDp, ratio } from "@/domain/core/format";
import { totalSec, type Recipe } from "@/domain/core/recipe";
import { BAND_WORDS, bandStatus, shotBand, shotOffBy, type ShotBand } from "@/domain/core/shotTarget";
import { bedStatus, channelingStatus, cupLabel, roastStatus, roastWord, scoreStatus, signedClock, sludgeStatus, statusColorVar, timeStatus, type Status } from "@/domain/core/status";
import { temp, tempUnit, unitsOf } from "@/domain/core/units";
import { noScaleAmounts, waterAmount } from "@/domain/core/noScale";
import { checkBrew, distrustedFields, Fields, lastPourEndSec } from "@/domain/ai";
import { reading, scaleFor, showsDialNumber, type GrindScale } from "@/domain/grind";
import { brewMarkdown } from "@/domain/report";
import { BrewAnalysisSection, useBrewVerdict } from "@/features/ai";
import { useGrindScale } from "@/features/grind";
import { PhotoStrip } from "@/features/photos";
import { InfoGrid, JourneyLine, RouteRow, TimelineCard, journeyNodes, plannedTimelineRows, pourNodes, type GridCell, type JourneyNode, type TimelineRow } from "@/viz";
import { brewAgain, toggleFavourite, useComparePicker, useDeleteBrew } from "./logActions";
import { detailWhen } from "./logState";
import { TasteStrip } from "./TasteStrip";
import l from "./log.module.css";

/** A distrusted value keeps its number but reads as questionable: attention colour and an IGNORED chip. */
function ignoredIf(c: GridCell, ignored: boolean): GridCell {
  return ignored ? { ...c, status: "attention", sub: "IGNORED", subStatus: "attention" } : c;
}

export function passCells(
  log: BrewRow,
  recipe: Recipe | null,
  grinder: GrinderRow | null,
  ts: [Status, string],
  distrusted: ReadonlySet<string>,
  units: ReturnType<typeof unitsOf>,
  // The screen passes its subscribed scale (useGrindScale), so the cell follows grinders.json landing.
  scale: GrindScale | null = grinder ? scaleFor(grinder) : null,
  // "SHOT TIME" for espresso (methodLabels, §4.9).
  timeLabel = "TIME",
  // The review's rule for which cells the method has (A3): a drip machine or a press has no
  // drawdown to time, so an empty DRAWDOWN / IN CUP is left out rather than drawn as a dash.
  sections: Pick<ReviewSections, "drawdown" | "retained"> = { drawdown: true, retained: true },
  // Espresso: shot mode's target, the one the shot was pulled against (A4).
  band: ShotBand | null = null,
): GridCell[] {
  const clicks = log.grindClicks;
  // The way the dial reads it: "dial 7.5" on a numbered ring, "6 turns + 6" on a plain one.
  let grindSub: string | null = null;
  if (clicks != null && scale) {
    const r = reading(scale, clicks);
    grindSub = r ? (showsDialNumber(scale.dial) ? `dial ${r}` : r) : null;
  }
  const grindValue = clicks != null ? String(clicks) : log.grindValue != null ? String(Math.round(log.grindValue * 100) / 100) : log.grindSetting.trim() || null;
  const grind = ignoredIf({ label: "GRIND", value: grindValue, unit: clicks != null ? "clicks" : log.grindValue != null && scale?.unit ? scale.unit : null, sub: grindSub, textValue: clicks == null && log.grindValue == null }, distrusted.has(Fields.GRIND));
  const plan = band ? `aim ${band.label}` : recipe ? `plan ${clock(totalSec(recipe))}` : null;
  const time = ignoredIf({ label: timeLabel, value: log.totalTimeSec > 0 ? clock(log.totalTimeSec) : null, sub: plan, status: ts[0] }, distrusted.has(Fields.TIME));
  const tempCell = ignoredIf({ label: "TEMP", value: log.tempC > 0 ? temp(units, log.tempC) : null, unit: tempUnit(units) }, distrusted.has(Fields.TEMP));
  const drawdown = ignoredIf({ label: "DRAWDOWN", value: log.drawdownSec != null ? clock(log.drawdownSec) : null }, distrusted.has(Fields.DRAWDOWN));
  const inCup = ignoredIf({ label: "IN CUP", value: log.beverageG != null ? grams(log.beverageG) : null, unit: "g" }, distrusted.has(Fields.BEVERAGE));
  const sixth =
    log.extractionYield != null
      ? ignoredIf({ label: "EXTRACTION", value: oneDp(log.extractionYield), unit: "%" }, distrusted.has(Fields.EY))
      : { label: "OFF ROAST", value: log.daysOffRoast != null ? String(log.daysOffRoast) : null, unit: "d", status: roastStatus(log.daysOffRoast) === "attention" ? ("attention" as const) : null };
  // A value the log does have always shows (an imported brew may carry one). IN CUP goes with
  // DRAWDOWN, as Review asks for them side by side (a Clever has no retained-water section but
  // still has a cup to weigh).
  const keepDrawdown = sections.drawdown || log.drawdownSec != null;
  const keepInCup = sections.drawdown || log.beverageG != null;
  return [grind, time, tempCell, ...(keepDrawdown ? [drawdown] : []), ...(keepInCup ? [inCup] : []), sixth];
}

/** Columns for the pass grid: four cells read as two rows of two, not three and a stray one. */
export function passColumns(count: number): number {
  return count === 4 ? 2 : Math.min(3, Math.max(1, count));
}

/**
 * Planned steps with the real events placed among them in time order. A distrusted drawdown or
 * time adds no row: nothing is drawn as if it happened when the log says otherwise.
 */
export function brewTimeline(
  log: BrewRow,
  recipe: Recipe | null,
  finish: Status,
  timeTrusted: boolean,
  bedExposedSec: number | null,
  // Espresso: the shot is measured against its band, as the status line and TIME cell are (A4).
  band: ShotBand | null = null,
  // Without a scale the planned steps read in ml, as the recipe detail shows them (A1).
  hasScale = true,
): TimelineRow[] {
  if (!recipe) return timeTrusted ? [{ time: clock(log.totalTimeSec), kind: "ACTUAL", title: "Finished", actual: true, terminal: true }] : [];
  const planned = plannedTimelineRows(recipe, hasScale);
  const steps = planned.slice(0, -1);
  const plannedDone = planned[planned.length - 1];
  const bedRow: TimelineRow | null =
    bedExposedSec != null ? { time: clock(bedExposedSec), kind: "ACTUAL", title: "Bed exposed", detail: log.drawdownSec != null ? `${clock(log.drawdownSec)} after the last pour` : null, actual: true } : null;
  const out: TimelineRow[] = [];
  let placed = false;
  steps.forEach((row, i) => {
    const start = recipe.steps[i]?.startSec ?? 0;
    if (bedRow && !placed && bedExposedSec != null && start > bedExposedSec) {
      out.push(bedRow);
      placed = true;
    }
    out.push(row);
  });
  if (bedRow && !placed) out.push(bedRow);
  const total = totalSec(recipe);
  if (timeTrusted) {
    const detail = band ? `aim ${band.label}` : `planned ${clock(total)}`;
    out.push({ time: clock(log.totalTimeSec), kind: "ACTUAL", title: "Finished", detail, actual: true, delta: signedClock(offTarget(log.totalTimeSec, total, band)), deltaStatus: finish, terminal: true });
  } else if (plannedDone) out.push(plannedDone);
  return out;
}

/** Seconds off the plan: from the band's nearer edge for a shot (0 inside it), else from the total. */
function offTarget(sec: number, total: number, band: ShotBand | null): number {
  return band ? shotOffBy(sec, band) : sec - total;
}

function bedWords(log: BrewRow): string | null {
  return log.bedShape ? bedShapeLabel(log.bedShape).toLowerCase() : null;
}

/** Web Share API when there is one, else the clipboard. */
async function shareText(text: string, title: string): Promise<"shared" | "copied" | "failed"> {
  try {
    if (typeof navigator.share === "function") {
      await navigator.share({ title, text });
      return "shared";
    }
  } catch (e) {
    // The user closed the share sheet: nothing to report.
    if (e instanceof DOMException && e.name === "AbortError") return "shared";
  }
  try {
    await navigator.clipboard.writeText(text);
    return "copied";
  } catch {
    return "failed";
  }
}

export function BrewDetailScreen({ brewUid }: { brewUid: string }) {
  useCatalog(["brewers", "grinders", "beans", "roasters", "origins", "water"]);
  const log = useBrew(brewUid);
  const pane = usePane();
  if (!log) {
    return (
      <div className={l.page}>
        {pane.inPane ? <div className={l.paneBar} /> : <DetailTopBar onBack={nav.back} />}
        <div style={{ height: 8 }} />
        <h1 className="visually-hidden">Brew</h1>
        <QuietPanel text="This brew is no longer in the log." />
      </div>
    );
  }
  return <Detail log={log} />;
}

function Detail({ log }: { log: BrewRow }) {
  const settled = useTransitionSettled();
  const pane = usePane();
  const selectedPath = useSelectedPath("log");
  const selected = pane.inPane && selectedPath === pathOf({ kind: "brewDetail", brewUid: log.uid });
  const recipe = useStore((st) => st.recipes.find((r) => r.id === log.recipeId) ?? null, undefined, log.recipeId);
  const grinder = useStore((st) => (log.grinderId != null ? (st.grinders.find((g) => g.id === log.grinderId) ?? null) : null), undefined, log.grinderId ?? -1);
  const bean = useStore((st) => (log.beanId != null ? (st.beans.find((b) => b.id === log.beanId) ?? null) : null), undefined, log.beanId ?? -1);
  const history = useStore((st) => st.brews);
  const settings = useStore((st) => st.settings);
  const units = unitsOf(settings);
  const id = log.id ?? 0;
  const photoCount = useLiveQuery(() => db.photos.where("brewLogId").equals(id).count(), [id]) ?? 0;
  const [notice, setNotice] = useState<string | null>(null);
  const del = useDeleteBrew();
  const picker = useComparePicker();
  const compareBtn = useRef<HTMLDivElement>(null);
  const brewer = Brewers.byId(log.brewerId) ?? Brewers.resolveOrNull(log.brewer);
  const labels = methodLabels(brewer?.method ?? "pourover");

  // Fields the local log check distrusts: their numbers stay visible but are marked, and nothing
  // derived from them (the bed-exposed node and row) is drawn as if it were true.
  const distrusted = useMemo(() => distrustedFields(checkBrew(log, recipe, grinder, bean), log.id), [log, recipe, grinder, bean]);
  const planned = recipe ? totalSec(recipe) : 0;
  const sections = reviewSections(brewer);
  const band = sections.style === "espresso" && recipe ? shotBand(recipe) : null;
  const bandWords = band && log.totalTimeSec > 0 ? BAND_WORDS[bandStatus(log.totalTimeSec, band)] : null;
  const ts: [Status, string] = bandWords ? [bandWords.status, bandWords.lead] : timeStatus(log.totalTimeSec, planned);
  const timeTrusted = !distrusted.has(Fields.TIME) && log.totalTimeSec > 0;
  const lastPour = lastPourEndSec(recipe);
  const bedExposedSec = distrusted.has(Fields.DRAWDOWN) || lastPour == null || log.drawdownSec == null ? null : lastPour + log.drawdownSec;
  const verdict = useBrewVerdict(id);

  const report = () => {
    const older = history.filter((h) => h.startedAt < log.startedAt).slice(0, 8);
    return brewMarkdown(log, recipe, bean, grinder, older, { photoCount });
  };
  const onShare = async () => {
    const r = await shareText(report(), `Brew log — ${log.recipeName}`);
    setNotice(r === "copied" ? "Report copied." : r === "failed" ? "Couldn't share or copy the report in this browser." : null);
  };
  const scrollToAnalysis = () => document.getElementById(`analysis-${id}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
  const openCompare = () => picker.open(log, compareBtn.current?.querySelector("button") ?? null);

  // The overflow menu on a narrow header holds what the wide header shows as words.
  const overflow = useContextMenu(
    () => [
      { label: "Brew again", icon: "Play", keys: "b", onSelect: () => brewAgain(log) },
      { label: "Compare with…", icon: "History", onSelect: () => picker.open(log, null) },
    ],
    "More for this brew",
  );

  let line: ReactNode;
  if (verdict) line = <StatusLine status={verdict.status} lead={verdict.lead} rest={verdict.detail ?? undefined} onClick={scrollToAnalysis} />;
  else if (distrusted.has(Fields.TIME) && log.totalTimeSec > 0) line = <StatusLine status="attention" lead="Time looks wrong" rest="left out of the analysis" onClick={scrollToAnalysis} />;
  else if (!recipe && log.totalTimeSec > 0) line = <StatusLine status="neutral" lead={`Finished in ${clock(log.totalTimeSec)}`} rest={bedWords(log) ?? undefined} onClick={scrollToAnalysis} />;
  else {
    // A shot says its seconds against the band, the words shot mode used (A4).
    const against = band ? `${log.totalTimeSec} s against ${band.label}` : `${clock(log.totalTimeSec)} against ${clock(planned)}`;
    const rest = [recipe && log.totalTimeSec > 0 ? against : null, bedWords(log)].filter(Boolean).join(" · ");
    line = <StatusLine status={ts[0]} lead={ts[1]} rest={rest || undefined} onClick={scrollToAnalysis} />;
  }

  const actions = (
    <div className={l.actions}>
      <PillButton text="Brew again" icon="Play" onClick={() => brewAgain(log)} className={l.wideAction} />
      <div ref={compareBtn} className={l.wideAction}>
        <PillButton text="Compare with…" icon="History" onClick={openCompare} />
      </div>
      <IconWell icon="Star" label={log.isFavourite ? "Remove from favourites" : "Add to favourites"} onClick={() => toggleFavourite(log)} tint={log.isFavourite ? "var(--crema-ink)" : undefined} size={44} />
      <IconWell icon="Share" label="Share report" onClick={() => void onShare()} size={44} />
      <IconWell icon="Edit" label="Edit brew" onClick={() => nav.push(pathOf({ kind: "review", brewUid: log.uid }))} size={44} />
      <IconWell icon="Delete" label="Delete brew" keys="delete" onClick={() => del.ask(log)} size={44} />
      <MenuButton className={l.narrowAction} label="More: Brew again, Compare with…" onOpen={overflow.openFrom} />
    </div>
  );

  return (
    <div className={l.page}>
      {pane.inPane ? (
        <div className={l.paneBar}>
          {selected && <IconWell icon="Close" label="Close" keys="escape" onClick={pane.close} size={44} />}
          <div className={l.paneBarActions}>
            {actions}
          </div>
        </div>
      ) : (
        <DetailTopBar onBack={nav.back} actions={actions} />
      )}
      {notice && (
        <p className={`t-body-s ink-mid ${l.notice}`} role="status">
          {notice}
        </p>
      )}

      <div className={l.layout}>
        <div className={l.mainCol}>
          <div className={l.o1}>
            <BoardingPass log={log} recipe={recipe} grinder={grinder} ts={ts} distrusted={distrusted} units={units} timeLabel={labels.time} waterLabel={labels.water} sections={sections} band={band} hasScale={settings.hasScale} method={brewer?.method ?? "pourover"} />
            <div className={l.statusLine}>{line}</div>
          </div>

          <div className={l.o4}>
            {settled ? <TheBrew log={log} recipe={recipe} ts={ts} timeTrusted={timeTrusted} bedExposedSec={bedExposedSec} band={band} hasScale={settings.hasScale} /> : recipe && <div className={l.placeholder} style={{ height: 320, marginTop: 24 }} aria-hidden="true" />}
          </div>

          <div className={l.o5}>
            <SectionLabel text="COFFEE & GEAR" />
            <SoftCard>
              <InfoGrid
                columns={2}
                cells={[
                  { label: "COFFEE", value: bean?.name.trim() || beanShortName(log.beanLabel) || null, sub: bean?.roaster.trim() || null, textValue: true },
                  { label: "OFF ROAST", value: log.daysOffRoast != null ? String(log.daysOffRoast) : null, unit: "d", sub: roastWord(log.daysOffRoast), status: roastStatus(log.daysOffRoast) === "attention" ? "attention" : null },
                  { label: "GRINDER", value: grinder?.model.trim() || log.grinderLabel.trim() || null, sub: grinder?.brand.trim() || null, textValue: true },
                  { label: "BREWER", value: log.brewer.trim() || null, textValue: true },
                ]}
              />
            </SoftCard>
          </div>

          <div className={l.o6}>
            <TheBed log={log} />
          </div>

          <div className={l.o8}>
            <SectionLabel text="TASTE" />
            <SoftCard>
              {distrusted.has(Fields.TASTE) && (
                <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
                  <StatusChip status="attention" text="Ignored" />
                  <span className="t-body-s ink-mid">Sliders never moved from 5</span>
                </div>
              )}
              <TasteStrip taste={log.taste} />
              {(log.descriptors.length > 0 || log.faults.length > 0) && <div style={{ height: 14 }} />}
              {log.descriptors.length > 0 && <p className="t-body-m">{log.descriptors.join(" · ")}</p>}
              {log.faults.length > 0 && <p className="t-body-m" style={{ color: "var(--fault)" }}>{log.faults.join(" · ")}</p>}
              {log.notes.trim() && <p className={l.quote} style={{ marginTop: 12 }}>{log.notes}</p>}
              {log.nextTime.trim() && (
                <>
                  <p className="micro ink-low" style={{ marginTop: 12, marginBottom: 4 }}>
                    NEXT TIME
                  </p>
                  <p className={l.notes}>{log.nextTime}</p>
                </>
              )}
            </SoftCard>
          </div>
        </div>

        <aside className={l.sideCol} aria-label="Photos and analysis">
          <div className={l.o7}>
            <SectionLabel text="PHOTOS" />
            {settled ? <PhotoStrip brewId={id} kinds={["bed", "cup"]} editable large /> : <div className={l.placeholder} style={{ height: 76 }} aria-hidden="true" />}
          </div>

          {/* No section label: the card's own eyebrow says ANALYSIS. */}
          <div className={`${l.o3} ${l.analysisSlot}`}>
            {settled ? <BrewAnalysisSection brewId={id} /> : <div className={l.placeholder} style={{ height: 220 }} aria-hidden="true" />}
          </div>

          <div className={`${l.o9} ${l.againSlot}`}>
            <SecondaryButton text="Brew again" icon="Play" onClick={() => brewAgain(log)} />
            <p className="t-body-s ink-mid">Same coffee, grind and dose.</p>
          </div>
        </aside>
      </div>
      {del.element}
      {picker.element}
      {overflow.element}
    </div>
  );
}

/** The boarding pass: when, what, how it scored, the dose-to-water route, then the numbers. */
function BoardingPass({
  log,
  recipe,
  grinder,
  ts,
  distrusted,
  units,
  timeLabel,
  waterLabel,
  sections,
  band,
  hasScale,
  method,
}: {
  log: BrewRow;
  recipe: Recipe | null;
  grinder: GrinderRow | null;
  ts: [Status, string];
  distrusted: ReadonlySet<string>;
  units: ReturnType<typeof unitsOf>;
  timeLabel: string;
  waterLabel: string;
  sections: ReviewSections;
  band: ShotBand | null;
  hasScale: boolean;
  method: BrewMethod;
}) {
  const nodes = useMemo(() => (recipe ? pourNodes(recipe) : []), [recipe]);
  const scale = useGrindScale(grinder);
  const cells = passCells(log, recipe, grinder, ts, distrusted, units, scale, timeLabel, sections, band);
  const rated = log.overall > 0;
  const r = ratio(log.doseG, log.waterG);
  // Without a scale the amounts read as the brew and Review said them (A1): water in ml, the
  // coffee in level tablespoons (espresso's and moka's dose is the basket's, so it keeps grams).
  const water = waterAmount(log.waterG, hasScale);
  const tbsp = hasScale ? null : noScaleAmounts(log.doseG, log.waterG, method).coffeeTbsp;
  const coffee = tbsp != null ? { value: halves(tbsp), unit: "tbsp" } : { value: grams(log.doseG), unit: "g" };
  return (
    <SoftCard padding={0}>
      <div className={l.passTop}>
        <div className={l.passHead}>
          <EyebrowLabel text={detailWhen(log.startedAt)} />
          <StatusChip status={scoreStatus(log.overall)} text={cupLabel(log.overall)} />
        </div>
        <div className={l.passTitle}>
          <div>
            <h1 className={`t-headline-m ${l.clamp2}`}>{log.recipeName.trim() || "Brew"}</h1>
            {log.recipeAuthor.trim() && <p className={`t-body-s ink-mid ${l.ellipsis}`}>{log.recipeAuthor}</p>}
          </div>
          <NumberWithUnit value={rated ? String(log.overall) : "—"} unit={rated ? "/10" : undefined} numeral="num-display" color={rated ? statusColorVar(scoreStatus(log.overall)) : "var(--ink-low)"} />
        </div>
        <RouteRow mode="done" from={coffee.value} fromUnit={coffee.unit} fromLabel="COFFEE" to={water.value} toUnit={water.unit} toLabel={waterLabel} nodes={nodes} midLabel={distrusted.has(Fields.RATIO) ? `${r} · ignored` : r} />
      </div>
      <PerforatedDivider />
      <div className={l.passGrid}>
        <InfoGrid cells={cells} columns={passColumns(cells.length)} />
      </div>
    </SoftCard>
  );
}

/** THE BREW: the trip drawn as a line, then the planned steps with what actually happened. */
function TheBrew({
  log,
  recipe,
  ts,
  timeTrusted,
  bedExposedSec,
  band,
  hasScale,
}: {
  log: BrewRow;
  recipe: Recipe | null;
  ts: [Status, string];
  timeTrusted: boolean;
  bedExposedSec: number | null;
  band: ShotBand | null;
  hasScale: boolean;
}) {
  const rows = useMemo(() => brewTimeline(log, recipe, ts[0], timeTrusted, bedExposedSec, band, hasScale), [log, recipe, ts, timeTrusted, bedExposedSec, band, hasScale]);
  if (!recipe && rows.length === 0) return null;
  const total = recipe ? Math.max(1, totalSec(recipe)) : 1;
  const actualEnd = recipe && timeTrusted ? log.totalTimeSec / total : null;
  const maxX = Math.max(1, actualEnd ?? 1);
  const nodes: JourneyNode[] = recipe
    ? [
        ...journeyNodes(recipe),
        // Neutral, not green: the node marks an event, and colour here would claim a judgement.
        ...(bedExposedSec != null ? [{ fraction: bedExposedSec / total, label: `BED ${clock(bedExposedSec)}`, kind: "actual" as const, status: "neutral" as const, priority: 1 }] : []),
      ].filter((n) => n.fraction == null || (n.fraction >= 0 && n.fraction <= maxX))
    : [];
  return (
    <>
      <SectionLabel text="THE BREW" />
      <SoftCard>
        {recipe && (
          <JourneyLine
            nodes={nodes}
            style="historical"
            progress={1}
            totalSec={total}
            startLabel="0:00"
            endLabel={clock(total)}
            actualEnd={actualEnd}
            endStatus={timeTrusted ? ts[0] : null}
            actualLabel={timeTrusted ? `${clock(log.totalTimeSec)} · ${ts[0] === "good" ? "on time" : signedClock(offTarget(log.totalTimeSec, total, band))}` : null}
            label={timeTrusted ? `Planned ${clock(total)}, finished at ${clock(log.totalTimeSec)}` : `Planned ${clock(total)}`}
          />
        )}
        {rows.length > 0 && (
          <div style={{ marginTop: recipe ? 8 : 0 }}>
            {/* The whole instruction: a finished brew is where you check what the method said. */}
            <TimelineCard rows={rows} detailMaxLines={99} />
          </div>
        )}
      </SoftCard>
    </>
  );
}

/** THE BED, when anything about it was recorded. The shape is drawn, never typed. */
function TheBed({ log }: { log: BrewRow }) {
  const recorded = log.bedShape != null || log.sludge != null || log.channeling != null || log.bedNotes.trim() !== "";
  if (!recorded) return null;
  const info = bedShapeInfo(log.bedShape);
  const st = bedStatus(log.bedShape);
  return (
    <>
      <SectionLabel text="THE BED" />
      <SoftCard>
        {info && (
          <div className={l.bedRow}>
            <BedShapeIcon shape={log.bedShape} size={48} tint={st ? statusColorVar(st) : "var(--ink-high)"} decorative />
            <div>
              <p className="t-title-m">{info.label}</p>
              <p className="t-body-s ink-mid">{info.reading}</p>
            </div>
          </div>
        )}
        {(log.sludge != null || log.channeling != null) && (
          <>
            {info && (
              <>
                <div style={{ height: 12 }} />
                <HairLine />
              </>
            )}
            <InfoGrid
              columns={2}
              cells={[
                { label: "SURFACE", value: log.sludge ? sludgeLabel(log.sludge) : null, status: sludgeStatus(log.sludge), textValue: true },
                { label: "CHANNELLING", value: log.channeling ? channelingLabel(log.channeling) : null, status: channelingStatus(log.channeling), textValue: true },
              ]}
            />
          </>
        )}
        {log.bedNotes.trim() && <p className={l.notes} style={{ marginTop: info || log.sludge || log.channeling ? 12 : 0 }}>{log.bedNotes}</p>}
      </SoftCard>
    </>
  );
}

