/**
 * Port of ui/ai/AnalysisCard.kt: one visual language for the brew card and the patterns card.
 * The state machines live in the sections; everything here only draws what it is given.
 */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { CoffeeMug, Dripper } from "@/art";
import { EyebrowLabel, HairLine, NumberWithUnit, QuietButton, SecondaryButton, SkeletonBar, SoftCard, StatusChip, StatusDot, StatusLine, VerticalHairline } from "@/design/components";
import type { Status } from "@/domain/core/status";
import { fieldLabel, Fields, type AiError, type BrewAnalysis, type DataFlag } from "@/domain/ai";
import { InfoGrid } from "@/viz";
import { Glyph } from "./glyphs";
import { footerTime, usageLabel, type StoredAnalysis } from "./stored";
import s from "./ai.module.css";

// ------------------------------------------------------------------ verdict words

export function verdictStatus(v: string): Status {
  switch (v) {
    case "dialled_in": return "good";
    case "close": return "neutral";
    case "needs_change": return "attention";
    case "fault": return "fault";
    default: return "neutral";
  }
}

export function verdictChip(v: string): string {
  switch (v) {
    case "dialled_in": return "DIALLED IN";
    case "close": return "CLOSE";
    case "needs_change": return "NEEDS A TWEAK";
    case "fault": return "SOMETHING'S WRONG";
    default: return "READ";
  }
}

// A verdict word opening the headline as its own clause ("Dialled in: …", "Close. …").
const VERDICT_OPENER = /^(dial{1,2}ed in|close|needs a tweak|needs (a )?change|something['’]?s wrong|fault)(?:\s*[:;.!—–]+|\s+-+)(?:\s+|$)/i;

/** The headline without a verdict word at its start: the chip already says the verdict. */
export function headlineWithoutVerdict(headline: string): string {
  const h = headline.trim();
  const m = VERDICT_OPENER.exec(h);
  if (!m) return h;
  const rest = h.slice(m[0].length).trim();
  return rest ? rest[0]!.toUpperCase() + rest.slice(1) : rest;
}

/** Some replies skip the verdict. The change itself still says whether anything needs to move. */
export function effectiveVerdict(a: BrewAnalysis): string {
  return a.verdict || (a.change.variable === "none" ? "dialled_in" : "needs_change");
}

export function trajectoryStatus(t: string): Status {
  switch (t) {
    case "improving": return "good";
    case "steady": return "neutral";
    case "stuck": return "attention";
    case "erratic": return "fault";
    default: return "neutral";
  }
}

export function trajectoryChip(t: string): string {
  return ["improving", "steady", "stuck", "erratic"].includes(t) ? t.toUpperCase() : "READ";
}

export function toneStatus(tone: string): Status {
  return tone === "good" || tone === "attention" || tone === "fault" ? tone : "neutral";
}

export function strengthLabel(strength: string): string {
  return strength === "strong" ? "STRONG" : strength === "too_little_data" ? "TOO LITTLE DATA" : "WEAK";
}

export function plural(n: number, one: string, many: string = one + "s"): string {
  return n === 1 ? `1 ${one}` : `${n} ${many}`;
}

/** "this brew and the 8 before it", worded properly for none and one. */
export function brewScope(before: number): string {
  if (before <= 0) return "this brew";
  if (before === 1) return "this brew and the one before it";
  return `this brew and the ${before} before it`;
}

/**
 * Model text as the app writes it: replies use an ASCII "->", every other move in the app a real
 * arrow, with no-break spaces so "66 → 64" never opens a line with the arrow. Display only.
 */
export function arrows(text: string): string {
  return text.split(" -> ").join(" → ").split(" → ").join(" → ");
}

/** The opening of the no-key copy: names the host a key is needed for, or where the key belongs. */
export function noKeyLead(host: string, keyHost: string, providerName: string): string {
  if (keyHost && host && keyHost !== host) return `Your key is saved for ${keyHost}. Paste one for ${host}`;
  if (!providerName) return "Choose an AI service, add your key";
  return `Add your key for ${providerName}`;
}

// -------------------------------------------------------------------------- panel

export function AnalysisPanel({ eyebrow, chip, children, id }: { eyebrow: string; chip: ReactNode; children: ReactNode; id?: string }) {
  return (
    <section id={id} className={s.panel} aria-label={eyebrow === "ANALYSIS" ? "Analysis" : "Patterns"}>
      <SoftCard>
        <div className={s.panelHead}>
          <Glyph name="spark" size={14} className={s.spark} />
          <EyebrowLabel text={eyebrow} />
          {chip}
        </div>
        {children}
      </SoftCard>
    </section>
  );
}

/** Holds roughly the height of the ready state while the first local read finishes. */
export function LocalSkeleton() {
  return (
    <div className={s.stack} aria-hidden="true">
      <SkeletonBar widthFraction={0.6} height={18} />
      <div className={s.gap8} />
      <SkeletonBar widthFraction={0.9} height={14} />
      <div style={{ height: 6 }} />
      <SkeletonBar widthFraction={0.75} height={14} />
      <div style={{ height: 14 }} />
      <SkeletonBar widthFraction={1} height={52} radius={18} />
    </div>
  );
}

// --------------------------------------------------------------------- empty states

/** Title and body on the left, a small piece of coffee art on the right. */
export function EmptyStateRow({ art, children }: { art: ReactNode; children: ReactNode }) {
  return (
    <div className={s.emptyRow}>
      <div>{children}</div>
      <div className={s.art} aria-hidden="true">
        {art}
      </div>
    </div>
  );
}

export function SmallDripper() {
  return <Dripper size={56} waterLevel={0.35} dripping={false} />;
}

export function SmallMug({ fill }: { fill: number }) {
  return <CoffeeMug size={56} fill={fill} showSteam={false} body="var(--art-shell)" liquid="var(--art-bed)" crema="var(--art-water)" />;
}

// ---------------------------------------------------------------------- the change

/** The arrow at 70 % of the numeral, as Android, so it reads as large text. */
function arrowSize(numeral: "num-large" | "num-medium"): { fontSize: string; lineHeight: string } {
  return numeral === "num-large" ? { fontSize: "1.75rem", lineHeight: "2.75rem" } : { fontSize: "1.125rem", lineHeight: "1.875rem" };
}

function FromTo({ from, to, unit }: { from: string; to: string; unit: string | null }) {
  // Long values (1:16 → 1:15) drop a size so the hero row never wraps.
  const numeral = from.length + to.length > 7 ? "num-medium" : "num-large";
  return (
    <span className={s.fromTo}>
      <span className={numeral} style={{ color: "var(--ink-low)" }}>
        {from}
      </span>
      <span className={`${numeral} ${s.arrow}`} style={arrowSize(numeral)} aria-label="to">
        →
      </span>
      <NumberWithUnit value={to} unit={unit ?? undefined} numeral={numeral} />
    </span>
  );
}

/** A hero row of values that differ from brew to brew ("100 → 94 → 97 °C"). */
export function ValueSeries({ values, unit }: { values: readonly string[]; unit: string | null }) {
  const numeral = values.reduce((n, v) => n + v.length, 0) > 9 ? "num-medium" : "num-large";
  return (
    <span className={s.fromTo}>
      {values.map((v, i) => (
        <span key={i} className={s.fromTo}>
          {i > 0 && (
            <span className={`${numeral} ${s.arrow}`} style={arrowSize(numeral)}>
              →
            </span>
          )}
          <span className={numeral}>{v}</span>
        </span>
      ))}
      {unit && <span className="num-unit ink-mid">{unit}</span>}
    </span>
  );
}

export interface HeroMove {
  readonly from: string | null;
  readonly to: string;
  readonly unit: string | null;
}

export function ChangeHero({ eyebrow, variableLabel, move, instruction, summary }: { eyebrow: string; variableLabel: string | null; move: HeroMove | null; instruction: string; summary: string | null }) {
  return (
    <div className={s.hero}>
      <div className={s.heroHead}>
        <EyebrowLabel text={eyebrow} />
        {/* Callers pass it already upper-cased, so a µ never turns into a Greek capital. */}
        {variableLabel && <span className={`micro ${s.heroVar}`}>{variableLabel}</span>}
      </div>
      {move?.from != null ? (
        <FromTo from={move.from} to={move.to} unit={move.unit} />
      ) : move ? (
        <NumberWithUnit value={move.to} unit={move.unit ?? undefined} numeral="num-large" />
      ) : (
        <p className={`${s.heroText} ${s.clamp3}`}>{arrows(instruction)}</p>
      )}
      {summary?.trim() && <p className={`${s.body14} ${s.clamp3}`} style={{ marginTop: 4 }}>{arrows(summary)}</p>}
    </div>
  );
}

// ----------------------------------------------------------------------- outcomes

function OutcomeCell({ status, label, text }: { status: Status; label: string; text: string }) {
  return (
    <div>
      <div className={s.outcomeHead}>
        <StatusDot status={status} size={6} />
        <EyebrowLabel text={label} />
      </div>
      {/* Never cut short: these are the two sentences the user acts on next brew. */}
      <p className={s.body14} style={{ color: text.trim() ? "var(--ink-high)" : "var(--ink-low)" }}>
        {text.trim() ? arrows(text) : "—"}
      </p>
    </div>
  );
}

export function OutcomeGrid({ ifRight, ifWrong }: { ifRight: string; ifWrong: string }) {
  return (
    <div className={s.outcomes}>
      <OutcomeCell status="good" label="IF IT WORKS" text={ifRight} />
      <VerticalHairline />
      <OutcomeCell status="attention" label="IF IT DOESN'T" text={ifWrong} />
    </div>
  );
}

export function WarningRows({ lines }: { lines: readonly string[] }) {
  return (
    <ul className={s.stack}>
      {lines.slice(0, 3).map((line, i) => (
        <li key={i} className={s.warnRow}>
          <Glyph name="warning" size={16} className={s.warnIcon} />
          <span className={s.body13}>{arrows(line)}</span>
        </li>
      ))}
    </ul>
  );
}

// ------------------------------------------------------------------------- footer

export function UsageFooter({ stored, staleText, onRerun, rerunEnabled }: { stored: StoredAnalysis; staleText: string | null; onRerun: () => void; rerunEnabled: boolean }) {
  // Short enough for one line beside Re-run.
  const line1 = stored.isSample ? "SAMPLE · NOT A MODEL REPLY" : stored.modelLabel.toUpperCase();
  const photos = stored.photoCount > 0 ? plural(stored.photoCount, "photo") : null;
  const line2 = [stored.isSample ? null : `via ${stored.providerName}`, usageLabel(stored.usage), footerTime(stored.createdAt), photos].filter((x): x is string => !!x).join(" · ");
  return (
    <div className={s.footer}>
      <HairLine />
      {staleText && (
        <p className={s.stale}>
          <StatusDot status="attention" size={6} />
          {staleText}
        </p>
      )}
      <div className={s.footerRow}>
        <div>
          <p className={`micro ink-low ${s.ellipsis}`}>{line1}</p>
          <p className={s.body12} style={{ fontSize: "0.6875rem", lineHeight: "0.9375rem", marginTop: 2 }}>
            {line2}
          </p>
        </div>
        {staleText ? <SecondaryButton text="Re-run" onClick={onRerun} icon="Replay" height={40} enabled={rerunEnabled} /> : <QuietButton text="Re-run" onClick={onRerun} icon="Replay" enabled={rerunEnabled} />}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------ loading

/** Whole seconds since `since`, ticking once a second (a discrete React update, not per frame). */
function useElapsedSeconds(since: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    let t: ReturnType<typeof setTimeout>;
    const tick = () => {
      setNow(Date.now());
      // Wake on the next whole second so the count never skips or stutters.
      t = setTimeout(tick, 1000 - ((Date.now() - since) % 1000));
    };
    t = setTimeout(tick, 1000 - ((Date.now() - since) % 1000));
    return () => clearTimeout(t);
  }, [since]);
  return Math.max(0, Math.floor((now - since) / 1000));
}

/** While a model reads: a mug slowly filling, the seconds so far and what is normal. */
export function LoadingBody({ lead, detail, since, onCancel }: { lead: string; detail: string; since: number; onCancel: () => void }) {
  const elapsed = useElapsedSeconds(since);
  // Fills over roughly the 10-40 s a read takes: never empty, never brimming.
  const fill = Math.min(0.9, 0.15 + elapsed / 45);
  return (
    <div className={s.stack} aria-live="polite">
      <StatusLine status="live" lead={lead} rest={detail || undefined} />
      <div className={s.gap12} />
      <div className={s.loadingRow}>
        <div className={s.artLarge} aria-hidden="true">
          <CoffeeMug size={64} fill={fill} showSteam />
        </div>
        <div>
          <SkeletonBar widthFraction={0.9} height={18} />
          <SkeletonBar widthFraction={0.6} height={18} />
          <p className={s.body12}>
            <span style={{ fontWeight: 500 }}>{elapsed}s</span> · usually 10–40 s
          </p>
        </div>
      </div>
      <div style={{ height: 14 }} />
      <SkeletonBar widthFraction={0.55} height={44} radius={16} />
      <div style={{ height: 14 }} />
      <div className={s.twoCol} aria-hidden="true">
        <SkeletonBar widthFraction={0.8} height={14} />
        <SkeletonBar widthFraction={0.8} height={14} />
      </div>
      <div className={s.gap8} />
      <div>
        <QuietButton text="Cancel" onClick={onCancel} />
      </div>
    </div>
  );
}

/** "Waiting for Claude Haiku 5.5 · 4s", ticking once a second. */
export function ElapsedLine({ since, prefix }: { since: number; prefix: string }) {
  const elapsed = useElapsedSeconds(since);
  return (
    <p className={s.body13} style={{ color: "var(--ink-low)", fontWeight: 500 }}>
      {prefix}
      {elapsed}s
    </p>
  );
}

// ------------------------------------------------------------------ errors and raw

export function ErrorBody({ error, onRetry, onOpenSettings, onDismiss }: { error: AiError; onRetry: () => void; onOpenSettings: () => void; onDismiss: () => void }) {
  return (
    <div className={s.stack} role="alert">
      <p className={s.title}>{error.title}</p>
      <div className={s.gap4} />
      {/* The error's own words only: never exception text. */}
      <p className={s.body13} style={{ whiteSpace: "pre-line" }}>
        {error.message}
      </p>
      <div className={s.gap12} />
      <div className={s.buttons}>
        {error.retryable && <SecondaryButton text="Try again" onClick={onRetry} icon="Replay" height={40} />}
        {error.opensSettings && <QuietButton text="AI settings" onClick={onOpenSettings} icon="Tune" />}
        <QuietButton text="Dismiss" onClick={onDismiss} />
      </div>
    </div>
  );
}

export function RawReplyBody({ raw }: { raw: string }) {
  const [expanded, setExpanded] = useState(false);
  const long = raw.length > 480 || raw.split("\n").length > 12;
  return (
    <div className={s.stack}>
      <p className={s.title}>Couldn't read the structured answer</p>
      <p className={s.body13} style={{ marginTop: 2 }}>
        Here's what the model said, as it said it.
      </p>
      <div className={s.gap8} />
      <p className={`${s.raw} ${expanded ? "" : s.clamp12}`}>{raw.trim() ? raw : "(an empty reply)"}</p>
      {long && (
        <div>
          <QuietButton text={expanded ? "Show less" : "Show all"} onClick={() => setExpanded((e) => !e)} />
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------- log check

// Fields Review can edit, so "Fix in log" leads somewhere for them.
const LOG_EDITABLE = new Set<string>([Fields.GRIND, Fields.BEVERAGE, Fields.EY, Fields.DRAWDOWN, Fields.TASTE, Fields.OVERALL, Fields.TIME]);

/** Where to fix a field that doesn't live on the brew log itself. */
function elsewhereHint(field: string): string | null {
  if (field === Fields.ROAST) return "Set the roast date in Gear › Beans; brews after that record it.";
  if (field === Fields.BEAN) return "Pick the coffee on the brew screen next time.";
  return null;
}

/** One line above the analysis, so the answer stays in the first screen. */
export function LogCheckLine({ flags }: { flags: readonly DataFlag[] }) {
  const ignored = [...new Set(flags.filter((f) => f.severity === "Distrust").map((f) => fieldLabel(f.field)))];
  if (ignored.length) return <StatusLine status="attention" lead={ignored.length === 1 ? "1 field ignored" : `${ignored.length} fields ignored`} rest={ignored.join(", ")} />;
  const labels = [...new Set(flags.map((f) => fieldLabel(f.field)))];
  return <StatusLine status="neutral" lead={flags.length === 1 ? "1 note on this log" : `${flags.length} notes on this log`} rest={labels.join(", ")} />;
}

export function LogCheckCard({ flags, onFix }: { flags: readonly DataFlag[]; onFix: (() => void) | null }) {
  const ignored = new Set(flags.filter((f) => f.severity === "Distrust").map((f) => f.field)).size;
  const fixable = flags.some((f) => LOG_EDITABLE.has(f.field));
  return (
    <SoftCard>
      <div className={s.checkHead}>
        <EyebrowLabel text="LOG CHECK" />
        {ignored > 0 ? <StatusChip status="attention" text={`${ignored} IGNORED`} /> : <StatusChip status="neutral" text={flags.length === 1 ? "1 NOTE" : `${flags.length} NOTES`} />}
      </div>
      <ul>
        {flags.map((f, i) => {
          const distrust = f.severity === "Distrust";
          const hint = elsewhereHint(f.field);
          return (
            <li key={i}>
              {i > 0 && <HairLine inset={26} />}
              <div className={s.checkRow}>
                {distrust ? <Glyph name="warning" size={16} className={s.warnIcon} /> : <span className={s.infoIcon} aria-hidden="true">ⓘ</span>}
                <div>
                  <p className="t-title-m">{fieldLabel(f.field)}</p>
                  {distrust && <p className="micro" style={{ color: "var(--attention)" }}>IGNORED BY ANALYSIS</p>}
                  <p className={s.body13} style={{ marginTop: 2 }}>
                    {f.message}
                  </p>
                  {hint && (
                    <p className={s.body12} style={{ fontWeight: 500, marginTop: 4 }}>
                      {hint}
                    </p>
                  )}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
      {onFix && fixable && (
        <div style={{ marginTop: 4 }}>
          <QuietButton text="Fix in log" onClick={onFix} icon="Edit" />
        </div>
      )}
    </SoftCard>
  );
}

// ------------------------------------------------------------------- offline read

export function OfflineRead({ lines, collapsible }: { lines: readonly string[]; collapsible: boolean }) {
  const [open, setOpen] = useState(false);
  if (collapsible && !open) {
    return (
      <div>
        <QuietButton text={`Offline read · ${plural(lines.length, "note")}`} onClick={() => setOpen(true)} />
      </div>
    );
  }
  return (
    <div className={`${s.stack} ${collapsible ? s.fadeIn : ""}`}>
      <EyebrowLabel text="OFFLINE FIRST READ" />
      <div className={s.gap4} />
      {lines.map((line, i) => (
        <div key={i}>
          {i > 0 && <HairLine />}
          <p className={s.readLine}>{line}</p>
        </div>
      ))}
      <p className={s.body12} style={{ marginTop: 4 }}>
        Pattern matching on your notes, not a verdict.
      </p>
      {collapsible && (
        <div>
          <QuietButton text="Hide offline read" onClick={() => setOpen(false)} />
        </div>
      )}
    </div>
  );
}

/** KEEP: this brew's grind, temperature and ratio, the numbers "change nothing" refers to. */
export function KeepWell({ grind, grindUnit, temp, tempUnit, ratio }: { grind: string | null; grindUnit: string | null; temp: string | null; tempUnit: string; ratio: string | null }) {
  return (
    <div className={s.well} style={{ paddingBottom: 4 }}>
      <EyebrowLabel text="KEEP" />
      <InfoGrid
        compact
        columns={3}
        cells={[
          { label: "GRIND", value: grind, unit: grindUnit, textValue: grindUnit == null && grind != null },
          { label: "TEMP", value: temp, unit: tempUnit },
          { label: "RATIO", value: ratio },
        ]}
      />
    </div>
  );
}

/** Keeps a ref to the latest value for effects that must not re-run on every change. */
export function useLatest<T>(value: T) {
  const ref = useRef(value);
  useEffect(() => {
    ref.current = value;
  });
  return ref;
}
