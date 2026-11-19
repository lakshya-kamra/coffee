/**
 * Port of ui/ai/TrendAnalysisSection.kt: "what goes wrong repeatedly" across the whole log, for
 * Insights. Same states and visuals as the brew card; owns its AI settings sheet.
 */
import { useMemo, useState } from "react";
import { nav } from "@/app/router";
import { pathOf } from "@/app/routes";
import { useStore } from "@/data/store";
import { EyebrowLabel, HairLine, PrimaryButton, QuietButton, SecondaryButton, StatusChip, StatusDot } from "@/design/components";
import { brewRowIds, followBrews, staleText, TREND_MAX_BREWS, type AiError, type Experiment, type Pattern, type TrendAnalysis } from "@/domain/ai";
import { dayMonthCaps } from "@/domain/insights/charts";
import { InfoGrid, TimelineCard, type TimelineRow } from "@/viz";
import { AiSettingsSheet } from "./AiSettingsSection";
import { AnalysisPanel, EmptyStateRow, ErrorBody, LoadingBody, LocalSkeleton, RawReplyBody, SmallMug, UsageFooter, ValueSeries, WarningRows, arrows, noKeyLead, plural, strengthLabel, toneStatus, trajectoryChip, trajectoryStatus } from "./AnalysisCard";
import { runner, type Target } from "./runner";
import type { StoredAnalysis } from "./stored";
import { useAnalysis, type AnalysisUi } from "./useAnalysis";
import s from "./ai.module.css";


const TREND: Target = { kind: "trend" };
// Beyond this many dates a pattern row turns into a wall of grey pills; the rest are a "+N".
const MAX_DATE_CHIPS = 3;

type Card = "loading" | "running" | "error" | "result" | "raw" | "no-key" | "blocked" | "ready";

function cardOf(u: AnalysisUi): Card {
  if (u.runningSince != null) return "running";
  if (u.error) return "error";
  if (u.stored) return u.stored.parsed && u.stored.trend ? "result" : "raw";
  if (u.loading) return "loading";
  if (!u.settings.ready) return "no-key";
  if (u.blocker) return "blocked";
  return "ready";
}

/** NotEnoughData carries `needed` and `have` beside the shared AiError fields. */
function numberIn(e: AiError, key: "needed" | "have"): number | null {
  const v: unknown = (e as unknown as Record<string, unknown>)[key];
  return typeof v === "number" ? v : null;
}

function rerunEnabled(u: AnalysisUi): boolean {
  return u.runningSince == null && u.settings.ready && !u.settings.needsTest && u.blocker == null;
}

interface LogInfo {
  readonly total: number;
  readonly capped: number;
  readonly rated: number;
  /** Brew id → uid and "14 SEP", for the tappable date chips under each pattern. */
  readonly byId: ReadonlyMap<number, { uid: string; label: string }>;
  readonly idByUid: ReadonlyMap<string, number>;
}

function useLogInfo(): LogInfo & { newSince: (at: number) => number } {
  const brews = useStore((st) => st.brews);
  return useMemo(() => {
    const byId = new Map<number, { uid: string; label: string }>();
    const idByUid = new Map<string, number>();
    for (const b of brews) {
      if (b.id == null) continue;
      byId.set(b.id, { uid: b.uid, label: dayMonthCaps(b.startedAt) });
      idByUid.set(b.uid, b.id);
    }
    const capped = brews.slice(0, TREND_MAX_BREWS);
    return {
      total: brews.length,
      capped: capped.length,
      rated: capped.filter((b) => b.overall > 0).length,
      byId,
      idByUid,
      newSince: (at: number) => brews.filter((b) => b.startedAt > at).length,
    };
  }, [brews]);
}

function openBrew(info: LogInfo, id: number) {
  const b = info.byId.get(id);
  if (b) nav.push(pathOf({ kind: "brewDetail", brewUid: b.uid }));
}

export function TrendAnalysisSection() {
  const u = useAnalysis(TREND);
  const info = useLogInfo();
  const [sheet, setSheet] = useState(false);
  const card = cardOf(u);
  const analyse = () => {
    runner.clearFailure(TREND);
    runner.start(TREND);
  };
  const openSettings = () => setSheet(true);
  const newBrews = u.stored ? info.newSince(u.stored.newestBrewAt) : 0;
  const stale = u.staleReason === "edited" || (u.staleReason == null && newBrews > 0) ? (newBrews > 0 ? `${plural(newBrews, "new brew")} since · re-run` : "Log edited since · re-run") : u.staleReason ? staleText(u.staleReason) : null;

  return (
    <>
      <AnalysisPanel eyebrow="PATTERNS" chip={<TrendChip card={card} u={u} />}>
        <div key={card} className={s.fadeIn}>
          {card === "loading" && <LocalSkeleton />}
          {card === "running" && <LoadingBody lead={`Reading ${plural(u.preview?.brewCount ?? info.capped, "brew")}`} detail={u.settings.modelLabel} since={u.runningSince ?? 0} onCancel={() => runner.cancel(TREND)} />}
          {card === "error" && u.error && (
            <div className={s.stack}>
              <ErrorBody error={u.error} onRetry={analyse} onOpenSettings={openSettings} onDismiss={() => runner.clearFailure(TREND)} />
              {u.stored && (
                <>
                  <div style={{ height: 14 }} />
                  <HairLine />
                  <div style={{ height: 14 }} />
                  <StoredBody u={u} stored={u.stored} info={info} stale={stale} onRerun={analyse} />
                </>
              )}
            </div>
          )}
          {(card === "result" || card === "raw") && u.stored && <StoredBody u={u} stored={u.stored} info={info} stale={stale} onRerun={analyse} />}
          {card === "no-key" && (
            <div className={s.stack}>
              <EmptyStateRow art={<SmallMug fill={0.7} />}>
                <p className={s.title}>Find what keeps going wrong</p>
                <p className={s.body14} style={{ marginTop: 4 }}>
                  {noKeyLead(u.settings.host, u.settings.keyHost, u.settings.providerName)} and a model reads your whole log and names the patterns.
                </p>
              </EmptyStateRow>
              <div className={s.gap12} />
              <div>
                <SecondaryButton text={u.settings.provider ? "Add key" : "Set up analysis"} onClick={openSettings} />
              </div>
            </div>
          )}
          {card === "blocked" && <BlockedBody u={u} />}
          {card === "ready" && <ReadyBody u={u} info={info} onAnalyse={analyse} onOpenSettings={openSettings} />}
        </div>
      </AnalysisPanel>
      {sheet && (
        <AiSettingsSheet onClose={() => setSheet(false)} />
      )}
    </>
  );
}

function TrendChip({ card, u }: { card: Card; u: AnalysisUi }) {
  switch (card) {
    case "loading": return null;
    case "running": return <StatusChip status="live" text="ANALYSING" />;
    case "error": return <StatusChip status="fault" text="FAILED" />;
    case "result": {
      const t = u.stored?.trend?.trajectory ?? "";
      return <StatusChip status={trajectoryStatus(t)} text={trajectoryChip(t)} />;
    }
    case "raw": return <StatusChip status="attention" text="UNREAD" />;
    case "no-key": return <StatusChip status="neutral" text="NOT SET UP" />;
    case "blocked": {
      const n = u.blocker ? numberIn(u.blocker, "needed") : null;
      return <StatusChip status="neutral" text={n != null ? `NEEDS ${n} BREWS` : "UNAVAILABLE"} />;
    }
    case "ready": return <StatusChip status="neutral" text={u.settings.needsTest ? "TEST FIRST" : "READY"} />;
  }
}

function StoredBody({ u, stored, info, stale, onRerun }: { u: AnalysisUi; stored: StoredAnalysis; info: LogInfo; stale: string | null; onRerun: () => void }) {
  if (stored.parsed && stored.trend) return <TrendResultBody u={u} stored={stored} t={stored.trend} info={info} stale={stale} onRerun={onRerun} />;
  return (
    <div className={s.stack}>
      <RawReplyBody raw={stored.rawText} />
      <UsageFooter stored={stored} staleText={stale} onRerun={onRerun} rerunEnabled={rerunEnabled(u)} />
    </div>
  );
}

function TrendResultBody({ u, stored, t, info, stale, onRerun }: { u: AnalysisUi; stored: StoredAnalysis; t: TrendAnalysis; info: LogInfo; stale: string | null; onRerun: () => void }) {
  const [more, setMore] = useState(false);
  const [all, setAll] = useState(false);
  const shown = all ? t.patterns : t.patterns.slice(0, 3);
  const warnings = [...t.dataWarnings, ...stored.localWarnings].slice(0, 3);
  // The read's brew numbers are the row ids it was made with; after a restore they are followed
  // through the brews' uids instead.
  const rowIds = brewRowIds(stored.brewUids, (uid) => info.idByUid.get(uid));
  return (
    <div className={s.stack}>
      <p className={`${s.verdictHeadline} ${s.clamp3}`}>{t.headline.trim() ? arrows(t.headline) : "What your log says"}</p>
      {t.summary.trim() && (
        <>
          <p className={`${s.body14} ${more ? "" : s.clamp4}`} style={{ marginTop: 6 }}>
            {arrows(t.summary)}
          </p>
          {(more || t.summary.length > 260) && (
            <div style={{ marginLeft: -8 }}>
              <QuietButton text={more ? "Less" : "More"} onClick={() => setMore((m) => !m)} />
            </div>
          )}
        </>
      )}
      {t.patterns.length > 0 && (
        <>
          <div className={s.gap12} />
          <EyebrowLabel text="FINDINGS" />
          <ul>
            {shown.map((p, i) => (
              <li key={i}>
                {i > 0 && <HairLine inset={18} />}
                <PatternRow p={p} info={info} rowIds={rowIds} />
              </li>
            ))}
          </ul>
          {t.patterns.length > 3 && (
            <div style={{ marginLeft: -8 }}>
              <QuietButton text={all ? "Fewer findings" : `All ${t.patterns.length} findings`} onClick={() => setAll((a) => !a)} />
            </div>
          )}
        </>
      )}
      {(t.changedOften.length > 0 || t.neverChanged.length > 0) && (
        <>
          <div className={s.gap12} />
          <InfoGrid
            columns={2}
            cells={[
              { label: "YOU KEEP MOVING", value: t.changedOften.join(" · ") || null, textValue: true },
              { label: "NEVER MOVED", value: t.neverChanged.join(" · ") || null, textValue: true },
            ]}
          />
        </>
      )}
      {(t.experiment.goal.trim() || t.experiment.brews.length > 0) && (
        <>
          <div style={{ height: 14 }} />
          <ExperimentWell ex={t.experiment} />
        </>
      )}
      {t.perCoffee.length > 0 && (
        <>
          <div className={s.gap12} />
          <EyebrowLabel text="PER COFFEE" />
          {t.perCoffee.map((n, i) => (
            <div key={i}>
              {i > 0 && <HairLine />}
              <div style={{ padding: "8px 0" }}>
                {n.bean.trim() && <p className={s.body14} style={{ color: "var(--ink-high)", fontWeight: 500 }}>{n.bean}</p>}
                <p className={s.body13}>{arrows(n.note)}</p>
              </div>
            </div>
          ))}
        </>
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

function PatternRow({ p, info, rowIds }: { p: Pattern; info: LogInfo; rowIds: ReadonlyMap<number, number> | null }) {
  // Only brews that still exist get a chip; a deleted one has nothing to open.
  const dated = followBrews(p.brewIds, rowIds).map((id) => [id, info.byId.get(id)] as const).filter((x): x is readonly [number, { uid: string; label: string }] => x[1] != null);
  return (
    <div className={s.pattern}>
      <span className={s.patternDot}>
        <StatusDot status={toneStatus(p.tone)} />
      </span>
      <div>
        {p.title.trim() && <p className="t-title-m">{p.title}</p>}
        {p.detail.trim() && <p className={s.body13}>{arrows(p.detail)}</p>}
        <div className={s.chips}>
          <span className="micro ink-low">{strengthLabel(p.strength)}</span>
          {dated.slice(0, MAX_DATE_CHIPS).map(([id, b]) => (
            <button key={id} type="button" className={`micro ${s.dateChip}`} onClick={() => openBrew(info, id)} aria-label={`Open the brew from ${b.label}`}>
              {b.label}
            </button>
          ))}
          {dated.length > MAX_DATE_CHIPS && <span className="micro ink-low">+{dated.length - MAX_DATE_CHIPS}</span>}
        </div>
      </div>
    </div>
  );
}

/** A value that changes from one experiment brew to the next: one per brew, and its unit. */
interface Varied {
  values: string[];
  unit: string;
}

const SETTING_VALUE = /(\d+(?:\.\d+)?)\s*(°C|clicks|clk|g|s)\b/g;
const PLAIN_BREW_LABEL = /^brew\s*\d+$/i;
const SETTING_SEPARATOR = /\s*[,;·]\s*/;

/** The one thing the experiment varies: grind clicks first, else the first unit that differs. */
export function variedValue(ex: Experiment): Varied | null {
  const brews = ex.brews;
  if (brews.length < 2) return null;
  const clicks = brews.map((b) => b.grindClicks);
  if (clicks.every((c) => c != null) && new Set(clicks).size > 1) return { values: clicks.map(String), unit: "clk" };
  const parsed = brews.map((b) => {
    const m = new Map<string, string>();
    for (const x of b.settings.matchAll(SETTING_VALUE)) {
      const unit = x[2] === "clicks" ? "clk" : x[2]!;
      if (!m.has(unit)) m.set(unit, x[1]!);
    }
    return m;
  });
  const units = [...parsed[0]!.keys()].filter((u) => parsed.every((p) => p.has(u)));
  const unit = units.find((u) => new Set(parsed.map((p) => p.get(u))).size > 1);
  if (!unit) return null;
  return { values: parsed.map((p) => p.get(unit)!), unit };
}

/** A brew's settings with the value under test lifted out, only when it is a list item of its own. */
export function withoutTested(settings: string, value: string, unit: string): string | null {
  const units = unit === "clk" ? "(?:clicks|clk)" : unit.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const tested = new RegExp(`^${value.replace(/\./g, "\\.")}\\s*${units}$`);
  const parts = settings.trim().split(SETTING_SEPARATOR).filter((p) => p.trim() !== "");
  if (parts.filter((p) => tested.test(p)).length !== 1) return null;
  const rest = parts.filter((p) => !tested.test(p)).join(", ");
  return rest || null;
}

function ExperimentWell({ ex }: { ex: Experiment }) {
  const varied = variedValue(ex);
  const rows: TimelineRow[] = ex.brews.map((b, i) => {
    const lifted = varied ? withoutTested(b.settings, varied.values[i]!, varied.unit) : null;
    return {
      time: `#${i + 1}`,
      // "#1" already numbers the row, so a label that only says "Brew 1" is dropped.
      kind: b.label.trim() && !PLAIN_BREW_LABEL.test(b.label.trim()) ? b.label : null,
      title: arrows(lifted ?? b.settings).trim() || "—",
      detail: b.expect.trim() ? `Taste for: ${arrows(b.expect)}` : null,
      // The bold column carries the value under test, never one held constant.
      trailing: lifted != null && varied ? `${varied.values[i]} ${varied.unit}` : null,
      terminal: i === ex.brews.length - 1,
    };
  });
  return (
    <div className={s.well}>
      <div className={s.wellHead}>
        <EyebrowLabel text="THE ONE EXPERIMENT" />
        {ex.brews.length > 0 && <span className="micro ink-low">{ex.brews.length === 1 ? "ON THE NEXT BREW" : `OVER THE NEXT ${ex.brews.length} BREWS`}</span>}
      </div>
      {varied && (
        <div style={{ marginTop: 8 }}>
          <ValueSeries values={varied.values} unit={varied.unit === "clk" ? "clicks" : varied.unit} />
        </div>
      )}
      {ex.goal.trim() && (
        <p className={varied ? s.body14 : s.title} style={{ marginTop: varied ? 4 : 8, color: "var(--ink-high)", fontWeight: 500 }}>
          {arrows(ex.goal)}
        </p>
      )}
      {rows.length > 0 && (
        <div style={{ marginTop: 8 }}>
          <TimelineCard rows={rows} />
        </div>
      )}
      {ex.decide.trim() && (
        <>
          <div style={{ marginTop: 4 }}>
            <EyebrowLabel text="HOW TO READ IT" />
          </div>
          <p className={s.body14} style={{ marginTop: 4 }}>
            {arrows(ex.decide)}
          </p>
        </>
      )}
    </div>
  );
}

function BlockedBody({ u }: { u: AnalysisUi }) {
  const b = u.blocker;
  const needed = b ? numberIn(b, "needed") : null;
  const have = b ? numberIn(b, "have") : null;
  if (needed != null && have != null) {
    const nb = { needed, have };
    // The mug fills as rated brews come in: a third per brew until patterns unlock.
    const fill = Math.min(1, Math.max(0, nb.have / Math.max(1, nb.needed)));
    return (
      <div className={s.stack}>
        <EmptyStateRow art={<SmallMug fill={fill} />}>
          <p className={s.title}>Not enough brews yet</p>
          <p className={s.body13} style={{ marginTop: 4 }}>
            Patterns need at least {nb.needed} rated brews. You have {nb.have}.
          </p>
        </EmptyStateRow>
        <div className={s.gap12} />
        <PrimaryButton text="Find patterns" onClick={() => undefined} enabled={false} height={52} className={s.fullWidth} />
      </div>
    );
  }
  if (!b) return null;
  return (
    <div className={s.stack}>
      <p className={s.title}>{b.title}</p>
      <p className={s.body13} style={{ marginTop: 4 }}>
        {b.message}
      </p>
    </div>
  );
}

function ReadyBody({ u, info, onAnalyse, onOpenSettings }: { u: AnalysisUi; info: LogInfo; onAnalyse: () => void; onOpenSettings: () => void }) {
  const p = u.preview;
  const count = p?.brewCount ?? info.capped;
  let sends = info.total > count ? `Sends your latest ${count} brews` : `Sends all ${count} brews`;
  sends += ` (${p?.ratedCount ?? info.rated} rated) to ${u.settings.providerName || u.settings.host}`;
  if (u.estimateLabel) sends += ` · ${u.estimateLabel}`;
  sends += ` · ${u.settings.modelLabel}`;
  const flagged = new Set((p?.flags ?? []).filter((f) => f.severity === "Distrust").map((f) => f.brewId)).size;
  return (
    <div className={s.stack}>
      <EmptyStateRow art={<SmallMug fill={0.7} />}>
        <p className={s.title}>Read the whole log</p>
        <p className={s.body13} style={{ marginTop: 4 }}>
          {sends}
        </p>
        {flagged > 0 && (
          <p className={s.body12} style={{ marginTop: 4 }}>
            {plural(flagged, "brew has", "brews have")} fields the analysis will ignore.
          </p>
        )}
      </EmptyStateRow>
      <div style={{ height: 14 }} />
      {u.settings.needsTest ? (
        <>
          <p className={s.body13}>{`The app can't tell whether ${u.settings.host} accepts requests from a browser, so test the connection first.`}</p>
          <div className={s.gap8} />
          <SecondaryButton text="Test in AI settings" onClick={onOpenSettings} icon="Tune" />
        </>
      ) : (
        // The one primary action on Insights.
        <PrimaryButton text="Find patterns" onClick={onAnalyse} height={52} className={s.fullWidth} />
      )}
    </div>
  );
}
