/**
 * Port of ui/ai/BrewAnalysisSection.kt: a one-line log check, the analysis card in whichever state
 * applies, then the full log check under the answer so the change stays in the first screenful.
 * Owns its AI settings sheet, so no route is needed.
 */
import { useState } from "react";
import { nav } from "@/app/router";
import { pathOf } from "@/app/routes";
import { useStore } from "@/data/store";
import { PrimaryButton, SecondaryButton, StatusChip, HairLine, EyebrowLabel, QuietButton } from "@/design/components";
import { quickRead } from "@/domain/core/quickRead";
import { ratio } from "@/domain/core/format";
import { temp, tempUnit, unitsOf } from "@/domain/core/units";
import type { Status } from "@/domain/core/status";
import { Fields, fmtValue, GrindMoves, GrindValueMoves, staleText, valueMove, type BrewAnalysis, type DataFlag } from "@/domain/ai";
import { AiSettingsSheet } from "./AiSettingsSection";
import { AnalysisPanel, ChangeHero, EmptyStateRow, ErrorBody, KeepWell, LoadingBody, LocalSkeleton, LogCheckCard, LogCheckLine, OfflineRead, OutcomeGrid, RawReplyBody, SmallDripper, UsageFooter, WarningRows, arrows, brewScope, effectiveVerdict, headlineWithoutVerdict, noKeyLead, verdictChip, verdictStatus, type HeroMove } from "./AnalysisCard";
import { runner, useRun, type Target } from "./runner";
import { useLatestAnalysis, type StoredAnalysis } from "./stored";
import { useAnalysis, type AnalysisUi } from "./useAnalysis";
import s from "./ai.module.css";


type Card = "loading" | "running" | "error" | "result" | "raw" | "no-key" | "blocked" | "ready";

// First match wins: a run in flight beats a failure, which beats what is stored.
function cardOf(u: AnalysisUi): Card {
  if (u.runningSince != null) return "running";
  if (u.error) return "error";
  if (u.stored) return u.stored.parsed && u.stored.brew ? "result" : "raw";
  if (u.loading) return "loading";
  if (!u.settings.ready) return "no-key";
  if (u.blocker) return "blocked";
  return "ready";
}

function rerunEnabled(u: AnalysisUi): boolean {
  return u.runningSince == null && u.settings.ready && !u.settings.needsTest && u.blocker == null;
}

/** What the brew log itself contributes: the grinder's short name, the KEEP numbers, the offline read. */
function useBrewDetails(brewId: number) {
  const log = useStore((st) => st.brews.find((b) => b.id === brewId) ?? null, undefined, brewId);
  const grinder = useStore((st) => (log?.grinderId != null ? (st.grinders.find((g) => g.id === log.grinderId) ?? null) : null), undefined, log?.grinderId ?? -1);
  const recipe = useStore((st) => (log ? (st.recipes.find((r) => r.id === log.recipeId) ?? null) : null), undefined, log?.recipeId ?? "");
  const units = unitsOf(useStore((st) => st.settings));
  if (!log) return null;
  const short = grinder?.model.trim() ? grinder.model.toUpperCase() : log.grinderLabel.trim() ? log.grinderLabel.trim().split(" ").pop()!.toUpperCase() : null;
  const clicks = log.grindClicks != null && log.grindClicks > 0 ? String(log.grindClicks) : null;
  return {
    log,
    grinderShort: short,
    quickRead: quickRead(log, recipe),
    keep: {
      grind: clicks ?? (log.grindValue != null ? fmtValue(log.grindValue) : log.grindSetting.trim() || null),
      grindUnit: clicks ? "clicks" : null,
      temp: log.tempC > 0 ? temp(units, log.tempC) : null,
      tempUnit: tempUnit(units),
      ratio: log.doseG > 0 && log.waterG > 0 ? ratio(log.doseG, log.waterG) : null,
    },
  };
}

export function BrewAnalysisSection({ brewId }: { brewId: number }) {
  const target: Target = { kind: "brew", brewId };
  const u = useAnalysis(target);
  const d = useBrewDetails(brewId);
  const [sheet, setSheet] = useState(false);
  const card = cardOf(u);
  const flags = u.preview?.flags ?? [];
  const analyse = () => {
    runner.clearFailure(target);
    runner.start(target);
  };
  const openSettings = () => setSheet(true);
  const onFix = d ? () => nav.push(pathOf({ kind: "review", brewUid: d.log.uid })) : null;

  return (
    <div className={s.stack}>
      {flags.length > 0 && (
        <>
          <LogCheckLine flags={flags} />
          <div style={{ height: 10 }} />
        </>
      )}
      <AnalysisPanel eyebrow="ANALYSIS" chip={<BrewChip card={card} u={u} />} id={`analysis-${brewId}`}>
        {/* Keyed by state, so each state fades in once (opacity only). */}
        <div key={card} className={s.fadeIn}>
          {card === "loading" && <LocalSkeleton />}
          {card === "running" && (
            <LoadingBody
              lead="Reading this brew"
              detail={[u.preview && u.preview.brewCount > 1 ? `+ ${u.preview.brewCount - 1} before it` : null, u.settings.modelLabel || null].filter(Boolean).join(" · ")}
              since={u.runningSince ?? 0}
              onCancel={() => runner.cancel(target)}
            />
          )}
          {card === "error" && u.error && (
            <div className={s.stack}>
              <ErrorBody error={u.error} onRetry={analyse} onOpenSettings={openSettings} onDismiss={() => runner.clearFailure(target)} />
              {/* A failed re-run never hides the last good answer. */}
              {u.stored && (
                <>
                  <div style={{ height: 14 }} />
                  <HairLine />
                  <div style={{ height: 14 }} />
                  <StoredBody u={u} stored={u.stored} details={d} onRerun={analyse} />
                </>
              )}
            </div>
          )}
          {(card === "result" || card === "raw") && u.stored && <StoredBody u={u} stored={u.stored} details={d} onRerun={analyse} />}
          {card === "no-key" && <NoKeyBody u={u} quickRead={d?.quickRead ?? []} onAddKey={openSettings} />}
          {card === "blocked" && u.blocker && (
            <div className={s.stack}>
              <p className={s.title}>{u.blocker.title}</p>
              <p className={s.body13} style={{ marginTop: 4 }}>
                {u.blocker.message}
              </p>
            </div>
          )}
          {card === "ready" && <ReadyBody u={u} quickRead={d?.quickRead ?? []} onAnalyse={analyse} onOpenSettings={openSettings} />}
        </div>
      </AnalysisPanel>
      {flags.length > 0 && (
        <>
          <div className={s.gap12} />
          <LogCheckCard flags={flags} onFix={onFix} />
        </>
      )}
      {sheet && (
        <AiSettingsSheet onClose={() => setSheet(false)} />
      )}
    </div>
  );
}

function BrewChip({ card, u }: { card: Card; u: AnalysisUi }) {
  switch (card) {
    case "loading": return null;
    case "running": return <StatusChip status="live" text="ANALYSING" />;
    case "error": return <StatusChip status="fault" text="FAILED" />;
    case "result": {
      const v = u.stored?.brew ? effectiveVerdict(u.stored.brew) : "";
      return <StatusChip status={verdictStatus(v)} text={verdictChip(v)} />;
    }
    case "raw": return <StatusChip status="attention" text="UNREAD" />;
    case "no-key": return <StatusChip status="neutral" text="NOT SET UP" />;
    case "blocked": return <StatusChip status="neutral" text="UNAVAILABLE" />;
    case "ready": return <StatusChip status="neutral" text={u.settings.needsTest ? "TEST FIRST" : "READY"} />;
  }
}

type Details = ReturnType<typeof useBrewDetails>;

function StoredBody({ u, stored, details, onRerun }: { u: AnalysisUi; stored: StoredAnalysis; details: Details; onRerun: () => void }) {
  const stale = u.staleReason ? staleText(u.staleReason) : null;
  if (stored.parsed && stored.brew) return <BrewResultBody u={u} stored={stored} a={stored.brew} details={details} stale={stale} onRerun={onRerun} />;
  return (
    <div className={s.stack}>
      <RawReplyBody raw={stored.rawText} />
      <UsageFooter stored={stored} staleText={stale} onRerun={onRerun} rerunEnabled={rerunEnabled(u)} />
    </div>
  );
}

/** Upper-cased here, from words that never hold a µ. */
function changeLabel(variable: string, grinderShort: string | null): string | null {
  if (variable === "grind") return ["GRIND", grinderShort].filter(Boolean).join(" · ");
  if (variable === "none") return "NO CHANGE";
  if (!variable) return null;
  return variable.toUpperCase();
}

/** Phrases that tie a free-text warning to a checked field. Kept narrow on purpose. */
const FIELD_WORDS: Record<string, readonly string[]> = {
  [Fields.TASTE]: ["slider"],
  [Fields.BEVERAGE]: ["cup weight", "stayed in the grounds"],
  [Fields.EY]: ["extraction yield"],
  [Fields.ROAST]: ["roast date"],
};

/** Drops warnings about a field the log check card already flags for this brew. */
function notCoveredBy(lines: readonly string[], flags: readonly DataFlag[]): string[] {
  const words = [...new Set(flags.map((f) => f.field))].flatMap((f) => FIELD_WORDS[f] ?? []);
  if (!words.length) return [...lines];
  return lines.filter((w) => !words.some((x) => w.toLowerCase().includes(x)));
}

function BrewResultBody({ u, stored, a, details, stale, onRerun }: { u: AnalysisUi; stored: StoredAnalysis; a: BrewAnalysis; details: Details; stale: string | null; onRerun: () => void }) {
  const [why, setWhy] = useState(false);
  const variable = a.change.variable;
  const gm = stored.grindMove;
  const vm = gm ? null : stored.valueMove;
  const vmv = gm || vm ? null : valueMove(a.change);
  const hero: HeroMove | null = gm
    ? { from: gm.from != null ? String(gm.from) : null, to: String(gm.to), unit: "clicks" }
    : vm
      ? { from: vm.from != null ? fmtValue(vm.from) : null, to: fmtValue(vm.to), unit: vm.unit || null }
      : vmv
        ? { from: vmv.from, to: vmv.to, unit: vmv.unit || null }
        : null;
  // The line under the hero, never a repeat of what the hero already says.
  const summary = gm
    ? GrindMoves.caption(gm) || a.change.instruction || null
    : vm
      ? [GrindValueMoves.caption(vm), a.change.instruction].filter(Boolean).join(" · ") || null
      : variable === "none"
        ? a.keep || null
        : vmv
          ? a.change.instruction || null
          : null;
  const headline = arrows(headlineWithoutVerdict(a.headline));
  const warnings = notCoveredBy([...a.dataWarnings, ...stored.localWarnings], u.preview?.flags ?? []).slice(0, 3);
  const long = a.diagnosis.length > 260;

  return (
    <div className={s.stack}>
      {headline.trim() && (
        <>
          <p className={`${s.verdictHeadline} ${s.clamp3}`}>{headline}</p>
          <div style={{ height: 14 }} />
        </>
      )}
      {variable === "none" && details ? (
        // "Change nothing" is already the status line above the card; what's left is which numbers to keep.
        <KeepWell {...details.keep} />
      ) : (
        <ChangeHero
          eyebrow="THE ONE CHANGE"
          variableLabel={changeLabel(variable, details?.grinderShort ?? null)}
          move={hero}
          instruction={variable === "none" ? "Change nothing" : a.change.instruction.trim() || a.change.to.trim() || "No single change suggested"}
          summary={summary}
        />
      )}
      {(a.diagnosis.trim() || a.evidence.length > 0) && (
        <>
          <div style={{ height: 14 }} />
          <div className={s.why}>
            <EyebrowLabel text="WHY" />
            <span className="micro ink-low">· {a.confidence.toUpperCase()} CONFIDENCE</span>
          </div>
          {a.diagnosis.trim() && <p className={`t-body-m ink-mid ${why ? "" : s.clamp4}`}>{arrows(a.diagnosis)}</p>}
          {why && a.evidence.length > 0 && (
            <ul className={s.fadeIn} style={{ marginTop: 6 }}>
              {a.evidence.map((line, i) => (
                <li key={i}>
                  {i > 0 && <HairLine />}
                  <p className={s.body13} style={{ padding: "6px 0" }}>
                    {arrows(line)}
                  </p>
                </li>
              ))}
            </ul>
          )}
          {(why || long || a.evidence.length > 0) && (
            <div style={{ marginLeft: -8 }}>
              <QuietButton text={why ? "Less" : "More"} onClick={() => setWhy((w) => !w)} />
            </div>
          )}
        </>
      )}
      {(a.expectIfRight.trim() || a.ifWrong.trim()) && (
        <>
          <div style={{ height: 14 }} />
          <OutcomeGrid ifRight={a.expectIfRight} ifWrong={a.ifWrong} />
        </>
      )}
      {a.keep.trim() && variable !== "none" && (
        <div className={s.keepLine} style={{ marginTop: 10 }}>
          <span style={{ paddingTop: 3 }}>
            <EyebrowLabel text="KEEP" />
          </span>
          <p className={s.body13}>{arrows(a.keep)}</p>
        </div>
      )}
      {warnings.length > 0 && (
        <div style={{ marginTop: 10 }}>
          <WarningRows lines={warnings} />
        </div>
      )}
      <UsageFooter stored={stored} staleText={stale} onRerun={onRerun} rerunEnabled={rerunEnabled(u)} />
    </div>
  );
}

function NoKeyBody({ u, quickRead, onAddKey }: { u: AnalysisUi; quickRead: readonly string[]; onAddKey: () => void }) {
  const before = Math.max(0, (u.preview?.brewCount ?? 1) - 1);
  return (
    <div className={s.stack}>
      <EmptyStateRow art={<SmallDripper />}>
        <p className={s.title}>Get a verdict on this brew</p>
        <p className={s.body14} style={{ marginTop: 4 }}>
          {noKeyLead(u.settings.host, u.settings.keyHost, u.settings.providerName)} and a model reads {brewScope(before)}, then suggests one change.
        </p>
      </EmptyStateRow>
      <div className={s.gap12} />
      <div>
        <SecondaryButton text={u.settings.provider ? "Add key" : "Set up analysis"} onClick={onAddKey} />
      </div>
      {quickRead.length > 0 && (
        <>
          <div className={s.gap16} />
          <OfflineRead lines={quickRead} collapsible={false} />
        </>
      )}
    </div>
  );
}

function ReadyBody({ u, quickRead, onAnalyse, onOpenSettings }: { u: AnalysisUi; quickRead: readonly string[]; onAnalyse: () => void; onOpenSettings: () => void }) {
  const p = u.preview;
  const before = Math.max(0, (p?.brewCount ?? 1) - 1);
  const photos = p && p.photoCount > 0 ? ` with ${p.photoCount === 1 ? "1 photo" : `${p.photoCount} photos`}` : "";
  let sends = `Sends ${brewScope(before)}${photos}`;
  if (p && p.approxPromptTokens > 0) sends += ` (~${(Math.round(p.approxPromptTokens / 100) / 10).toFixed(1)}k tokens)`;
  sends += ` to ${u.settings.providerName || u.settings.host}`;
  if (u.estimateLabel) sends += ` · ${u.estimateLabel}`;
  sends += ` · ${u.settings.modelLabel}`;
  return (
    <div className={s.stack}>
      <EmptyStateRow art={<SmallDripper />}>
        <p className={s.title}>One change for next time</p>
        <p className={s.body13} style={{ marginTop: 4 }}>
          {sends}
        </p>
        <PhotoNote u={u} />
      </EmptyStateRow>
      <div style={{ height: 14 }} />
      {u.settings.needsTest ? (
        <>
          <p className={s.body13}>{`The app can't tell whether ${u.settings.host} accepts requests from a browser, so test the connection first.`}</p>
          <div className={s.gap8} />
          <SecondaryButton text="Test in AI settings" onClick={onOpenSettings} icon="Tune" />
        </>
      ) : (
        // The one primary action on Brew detail.
        <PrimaryButton text="Analyse brew" onClick={onAnalyse} height={52} className={s.fullWidth} />
      )}
      {quickRead.length > 0 && (
        <>
          <div style={{ height: 10 }} />
          <OfflineRead lines={quickRead} collapsible />
        </>
      )}
    </div>
  );
}

/** A§9.2: what happens to this brew's photos. */
function PhotoNote({ u }: { u: AnalysisUi }) {
  const n = u.preview?.photoCount ?? 0;
  if (n > 0 || !u.settings.ready) return null;
  if (!u.settings.modelSeesImages) {
    return (
      <p className={s.body12} style={{ marginTop: 4 }}>
        Photos stay in this browser — {u.settings.modelLabel} can't see images.
      </p>
    );
  }
  return null;
}

// ------------------------------------------------------------------ verdict for the pass

export interface VerdictSummary {
  readonly status: Status;
  readonly lead: string;
  readonly detail: string | null;
}

/**
 * The analysis in one line for the status line under the boarding pass. A change to make is the
 * card's job, so only "Analysing" and "Change nothing" are said here; otherwise null.
 */
export function useBrewVerdict(brewId: number): VerdictSummary | null {
  const target: Target = { kind: "brew", brewId };
  const run = useRun(target);
  const st = useLatestAnalysis(target);
  if (run.runningSince != null) return { status: "live", lead: "Analysing", detail: "reading this brew" };
  if (!st || !st.parsed || !st.brew) return null;
  if (st.brew.change.variable !== "none" || st.grindMove != null) return null;
  return { status: verdictStatus(effectiveVerdict(st.brew)), lead: "Change nothing", detail: null };
}

