/**
 * Port of ui/screens/insights/InsightsScreen.kt: BREW PASSPORT, PERSONAL BESTS, SCORE · LAST N,
 * GRIND journey, MOST BREWED, the pattern finder, SHARE & EXPORT and WHAT LEAVES THIS BROWSER.
 * Everything above the pattern finder works offline; derivations are memoised per data change.
 */
import { useMemo, useState } from "react";
import { nav } from "@/app/router";
import { pathOf } from "@/app/routes";
import shell from "@/app/shell.module.css";
import { BrewerIcon } from "@/art";
import { useCatalog } from "@/catalog/useCatalog";
import { db } from "@/data/db";
import { useStore } from "@/data/store";
import { EyebrowLabel, GroupRow, HairLine, Icon, NumberWithUnit, ScreenHeader, SectionLabel, SoftCard, StatusChip, StatusLine } from "@/design/components";
import { brewerShortName } from "@/domain/core/brewer";
import { grams, oneDp, plural } from "@/domain/core/format";
import { scoreStatus, statusColorVar } from "@/domain/core/status";
import { bigWeight, tempLabel, unitsOf } from "@/domain/core/units";
import { decodeDialFormat, DialFormats, reading, showsDialNumber, type DialFormat } from "@/domain/grind";
import { dayMonthCaps, grindSeries, hasRecords, lastFiveAvg, passport, personalBests, prevFiveAvg, rankedBeans, rankedRecipes, scoreSeries, type BestCup, type GrindSeries, type Passport, type PersonalBests } from "@/domain/insights";
import { brewMarkdown, trendMarkdown } from "@/domain/report";
import { TrendAnalysisSection, useAiSettings } from "@/features/ai";
import { SaveBackupRow } from "@/features/backup";
import { InfoGrid, type GridCell } from "@/viz";
import type { IconName } from "@/design/icons";
import type { GroupPosition } from "@/design/components";
import { Glyph } from "@/features/ai/glyphs";
import { GrindJourneyChart, RankedList, ScoreBars } from "./Charts";
import i from "./insights.module.css";

/** Web Share API when there is one, else the clipboard. */
async function share(text: string, title: string): Promise<string | null> {
  try {
    if (typeof navigator.share === "function") {
      await navigator.share({ title, text });
      return null;
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") return null;
  }
  return copy(text);
}

async function copy(text: string): Promise<string | null> {
  try {
    await navigator.clipboard.writeText(text);
    return "Copied.";
  } catch {
    return "Couldn't copy in this browser.";
  }
}

/**
 * Must stay literally true (web-spec §14.8). No analytics, telemetry or error reporting exists to
 * contradict it; adding any later requires rewriting this copy first.
 */
export function privacyCopy(service: { name: string; host: string } | null): string {
  const lead =
    "Coffee on the web is a set of files served by Vercel. Once loaded it runs in your browser and keeps everything here, in this browser's storage; Vercel sees requests for those files like any website.";
  if (!service) {
    return `${lead} It goes online only to load or update itself. No AI service is set up, so nothing you log leaves this browser. Photos are re-saved without location or camera details and leave this browser only in a backup you save. Backups never include your key.`;
  }
  return (
    `${lead} It goes online only to load or update itself, and when you tap a button that runs an analysis, tests a connection or lists models — and then only to ${service.name} at ${service.host}, the service you chose in Gear › App › AI analysis. ` +
    "An analysis sends the brews it reads — recipe, coffee details, grinder and its scale, brewer, water, times, bed, taste and your notes — and, if the model can see images and you left them ticked, up to 3 photos of that brew. " +
    "Photos are re-saved without location or camera details and leave this browser only in an analysis or a backup you save. Backups never include your key."
  );
}

export function InsightsScreen() {
  useCatalog(["brewers", "grinders", "beans", "roasters", "origins", "water"]);
  const logs = useStore((s) => s.brews);
  const beans = useStore((s) => s.beans);
  const grinders = useStore((s) => s.grinders);
  const recipes = useStore((s) => s.recipes);
  const units = unitsOf(useStore((s) => s.settings));
  const ai = useAiSettings();
  const [notice, setNotice] = useState<string | null>(null);

  // Derived once per data change, never per render (audit rank 10).
  const p = useMemo(() => passport(logs), [logs]);
  const bests = useMemo(() => personalBests(logs, beans), [logs, beans]);
  const scores = useMemo(() => scoreSeries(logs), [logs]);
  const last5 = useMemo(() => lastFiveAvg(logs), [logs]);
  const prev5 = useMemo(() => prevFiveAvg(logs), [logs]);
  const topRecipes = useMemo(() => rankedRecipes(logs, recipes), [logs, recipes]);
  const topBeans = useMemo(() => rankedBeans(logs, beans), [logs, beans]);
  const grind = useMemo(() => grindSeries(logs, grinders), [logs, grinders]);
  const hasData = logs.length > 0;

  const lastBrewReport = async (): Promise<string> => {
    const log = logs[0];
    if (!log) return "";
    const photoCount = log.id != null ? await db.photos.where("brewLogId").equals(log.id).count() : 0;
    return brewMarkdown(
      log,
      recipes.find((r) => r.id === log.recipeId) ?? null,
      log.beanId != null ? (beans.find((b) => b.id === log.beanId) ?? null) : null,
      log.grinderId != null ? (grinders.find((g) => g.id === log.grinderId) ?? null) : null,
      logs.slice(1, 9),
      { photoCount },
    );
  };

  return (
    <div className={`column ${shell.screen}`}>
      <ScreenHeader eyebrow={p.firstAt != null ? `SINCE ${dayMonthCaps(p.firstAt)}` : "YOUR LOG"} title="Insights" />
      <div className={i.cards}>
        <PassportCard p={p} totalRecipes={recipes.length} units={units} />
        {hasRecords(bests) && <PersonalBestsCard bests={bests} units={units} />}
        {scores.length > 0 && (
          <SoftCard>
            <div className={i.cardHead}>
              <EyebrowLabel text={`SCORE · LAST ${scores.length}`} />
              {last5 != null && prev5 != null && <TrendChip d={last5 - prev5} />}
            </div>
            {/* The big number is the last five only, and says so. */}
            {last5 != null && (
              <div style={{ marginTop: 6 }}>
                <NumberWithUnit value={oneDp(last5)} unit="/10" numeral="num-large" />
                <p className="micro ink-low">AVG · LAST 5</p>
              </div>
            )}
            <ScoreBars points={scores} />
          </SoftCard>
        )}
        {grind && <GrindCard g={grind} />}
        {topRecipes.length > 0 && (
          <SoftCard>
            <EyebrowLabel text="MOST BREWED" />
            <div style={{ height: 4 }} />
            <RankedList items={topRecipes} />
            {topBeans.length > 0 && (
              <>
                <div style={{ height: 8 }} />
                <HairLine />
                <div style={{ height: 12 }} />
                <EyebrowLabel text="COFFEES" />
                <div style={{ height: 4 }} />
                <RankedList items={topBeans} />
              </>
            )}
          </SoftCard>
        )}
        {/* No section label: the card's own eyebrow says PATTERNS. */}
        <TrendAnalysisSection />
      </div>

      <SectionLabel text="SHARE & EXPORT" />
      <div>
        <ShareRow
          position="first"
          icon="Share"
          title="Last brew report"
          subtitle="Markdown with the 8 brews before it"
          enabled={hasData}
          onShare={() => void lastBrewReport().then((t) => share(t, "Brew log")).then(setNotice)}
          copyLabel="Copy last brew report"
          onCopy={() => void lastBrewReport().then(copy).then(setNotice)}
        />
        <ShareRow
          position="middle"
          icon="History"
          title="History table"
          subtitle="Last 25 brews, for pattern questions"
          enabled={hasData}
          onShare={() => void share(trendMarkdown(logs.slice(0, 25), beans), "Brew history").then(setNotice)}
          copyLabel="Copy history table"
          onCopy={() => void copy(trendMarkdown(logs.slice(0, 25), beans)).then(setNotice)}
        />
        {/* Lead decision 9: this export leaves photos out; "Back up everything" in Gear keeps them. */}
        <SaveBackupRow includePhotos={false} label="Save backup (JSON)" subtitle="Every brew, bean and grinder, without photos" />
      </div>
      {notice && (
        <p className="t-body-s ink-mid" role="status" style={{ marginTop: 8 }}>
          {notice}
        </p>
      )}

      <SectionLabel text="WHAT LEAVES THIS BROWSER" />
      <SoftCard>
        <p className={i.privacy}>{privacyCopy(ai.ready ? { name: ai.providerName, host: ai.host } : null)}</p>
      </SoftCard>
    </div>
  );
}

/**
 * A share row with its copy action beside it. Two sibling buttons inside one grouped row, so no
 * button sits inside another (nested interactive content is invalid and confuses screen readers).
 */
function ShareRow({ position, icon, title, subtitle, onShare, onCopy, copyLabel, enabled }: { position: GroupPosition; icon: IconName; title: string; subtitle: string; onShare: () => void; onCopy: () => void; copyLabel: string; enabled: boolean }) {
  return (
    <GroupRow position={position} hairlineInset={56}>
      <div className={i.shareRow}>
        <button type="button" className={i.shareMain} onClick={onShare} disabled={!enabled}>
          <Icon name={icon} size={22} />
          <span className={i.shareText}>
            <span className="t-body-l">{title}</span>
            <span className="t-body-s ink-mid">{subtitle}</span>
          </span>
        </button>
        <button type="button" className={i.copyBtn} aria-label={copyLabel} title={copyLabel} onClick={onCopy} disabled={!enabled}>
          <Glyph name="copy" size={20} />
        </button>
      </div>
    </GroupRow>
  );
}

/** The chip says what it compares, not just which way it moved. */
function TrendChip({ d }: { d: number }) {
  if (Math.abs(d) < 0.05) return <StatusChip status="neutral" text="Level vs prev 5" />;
  return d > 0 ? <StatusChip status="good" text={`▲ ${oneDp(Math.abs(d))} vs prev 5`} /> : <StatusChip status="attention" text={`▼ ${oneDp(Math.abs(d))} vs prev 5`} />;
}

function PassportCard({ p, totalRecipes, units }: { p: Passport; totalRecipes: number; units: ReturnType<typeof unitsOf> }) {
  const empty = p.brews === 0;
  // Lifetime totals only; records live in Personal bests with their story.
  const cells: GridCell[] = empty
    ? ["WATER", "COFFEE", "GREAT CUPS", "AVG", "RECIPES", "COFFEES"].map((label) => ({ label, value: null }))
    : (() => {
        // Lead decision 3: totals convert in ounce mode.
        const kilos = !units.ounces && p.coffeeG >= 1000;
        const [cv, cu] = kilos ? [oneDp(p.coffeeG / 1000), "kg"] : units.ounces ? bigWeight(units, p.coffeeG) : [grams(p.coffeeG), "g"];
        return [
          { label: "WATER", value: oneDp(p.waterG / 1000), unit: "L" },
          { label: "COFFEE", value: cv, unit: cu },
          { label: "GREAT CUPS", value: String(p.greatCups), status: p.greatCups > 0 ? "good" : null },
          { label: "AVG", value: p.avg != null ? oneDp(p.avg) : null, status: p.avg != null ? scoreStatus(Math.round(p.avg)) : null },
          { label: "RECIPES", value: String(p.recipesUsed), unit: totalRecipes > 0 ? `of ${totalRecipes}` : null },
          { label: "COFFEES", value: String(p.coffees) },
        ];
      })();
  return (
    <SoftCard>
      <EyebrowLabel text="BREW PASSPORT" />
      <div className={i.passCount}>
        <span className="num-display" style={{ color: empty ? "var(--ink-low)" : undefined }}>
          {p.brews}
        </span>
        <span className="t-title-l ink-mid">{p.brews === 1 ? "brew" : "brews"}</span>
        {!empty && <span className="micro ink-low">{p.spanDays === 1 ? "IN 1 DAY" : `IN ${p.spanDays} DAYS`}</span>}
      </div>
      {empty && <p className="t-body-s ink-mid">Your passport fills in as you brew.</p>}
      <div className={i.gap16} />
      <HairLine />
      <InfoGrid cells={cells} columns={3} />
      {!empty && p.stamps.length > 0 && (
        <>
          <HairLine />
          <div style={{ height: 12 }} />
          <EyebrowLabel text="STAMPS" />
          <ul className={i.stamps}>
            {p.stamps.map((s) => (
              <li key={s.brewer?.id ?? s.label} className={`t-title-s ${i.stamp}`}>
                {s.brewer && <BrewerIcon brewer={s.brewer} size={18} tint="var(--ink-mid)" decorative />}
                <span>
                  {s.brewer ? brewerShortName(s.brewer) : s.label} ×{s.count}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </SoftCard>
  );
}

function openBrew(uid: string) {
  nav.push(pathOf({ kind: "brewDetail", brewUid: uid }));
}

/** Filled tiles led by their number; the best cup opens its brew, because the next move is to repeat it. */
function PersonalBestsCard({ bests, units }: { bests: PersonalBests; units: ReturnType<typeof unitsOf> }) {
  const streak = bests.longestStreak && bests.longestStreak.days >= 2 ? bests.longestStreak : null;
  const coffee = bests.bestCoffee;
  const dm = (epochDay: number) => dayMonthCaps(epochDay * 86_400_000 + new Date().getTimezoneOffset() * 60_000);
  return (
    <SoftCard>
      <EyebrowLabel text="PERSONAL BESTS" />
      {bests.bestCup && <BestCupTile cup={bests.bestCup} units={units} />}
      {(streak || coffee) && (
        <div className={i.records}>
          {streak && (
            <RecordTile
              label="LONGEST STREAK"
              value={String(streak.days)}
              unit={streak.days === 1 ? "day" : "days"}
              color="var(--ink-high)"
              title={streak.current ? "Still going" : "Every day"}
              detail={streak.current ? `since ${dm(streak.end - streak.days + 1)}` : `${dm(streak.end - streak.days + 1)} – ${dm(streak.end)}`}
            />
          )}
          {coffee && (
            <RecordTile
              label="BEST COFFEE"
              value={coffee.avg != null ? oneDp(coffee.avg) : "—"}
              unit="avg"
              color={coffee.avg != null ? statusColorVar(scoreStatus(Math.round(coffee.avg))) : "var(--ink-low)"}
              title={coffee.name}
              detail={[coffee.sub, plural(coffee.count, "brew")].filter(Boolean).join(" · ")}
            />
          )}
        </div>
      )}
    </SoftCard>
  );
}

function BestCupTile({ cup, units }: { cup: BestCup; units: ReturnType<typeof unitsOf> }) {
  const settings = [cup.clicks != null ? `${cup.clicks} clicks` : cup.grindSetting.trim() || null, cup.tempC > 0 ? tempLabel(units, cup.tempC) : null].filter(Boolean).join(" · ");
  return (
    <button type="button" className={i.bestCup} onClick={() => openBrew(cup.uid)}>
      <span>
        <EyebrowLabel text={`BEST CUP · ${dayMonthCaps(cup.at)}`} />
        <span className={`t-title-m ${i.clamp2}`}>{cup.recipeName}</span>
        {settings && <span className={`t-body-s ink-mid ${i.ellipsis}`}>{settings}</span>}
      </span>
      <NumberWithUnit value={String(cup.score)} unit="/10" numeral="num-large" color={statusColorVar(scoreStatus(cup.score))} />
    </button>
  );
}

function RecordTile({ label, value, unit, color, title, detail }: { label: string; value: string; unit: string; color: string; title: string; detail: string }) {
  return (
    <div className={i.record}>
      <EyebrowLabel text={label} />
      <NumberWithUnit value={value} unit={unit} numeral="num-medium" color={color} />
      <span className={`t-title-s ${i.ellipsis}`}>{title}</span>
      <span className={`t-body-s ink-mid ${i.ellipsis}`} style={{ fontSize: "0.75rem" }}>
        {detail}
      </span>
    </div>
  );
}

function GrindCard({ g }: { g: GrindSeries }) {
  // Say it the way the dial reads: a numbered ring shows 75 clicks as 7.5.
  const format: DialFormat = decodeDialFormat(g.dialFormat, g.clicksPerTurn) ?? (g.clicksPerTurn === 10 ? DialFormats.turnsDotClicks(10) : DialFormats.plain());
  const read = (v: number) =>
    reading({ style: "clicks", unit: "clicks", min: 0, max: 0, step: 1, lowerIsFiner: true, clicksPerRotation: null, micronsPerStep: null, dial: format, ranges: {}, rangeSources: [], confidence: null, dialReadingHelp: "", entryHint: "" }, v);
  const dial = showsDialNumber(format) && g.first != null && g.latest != null ? ` · DIAL ${read(g.first)} → ${read(g.latest)}` : "";
  const others = g.otherBrews === 1 ? "1 brew on another recipe is" : `${g.otherBrews} brews on other recipes are`;
  return (
    <SoftCard>
      <EyebrowLabel text={`GRIND · ${g.grinderLabel}`} />
      <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginTop: 8 }}>
        <span className="num-large ink-low">{g.first ?? "—"}</span>
        <span className="num-medium ink-low">→</span>
        <NumberWithUnit value={String(g.latest ?? "—")} unit="clicks" numeral="num-large" />
      </div>
      <p className="micro ink-low">FIRST → NOW{dial}</p>
      <GrindJourneyChart series={g} />
      {g.otherBrews > 0 && (
        <p className="t-body-s ink-low" style={{ marginTop: 6, fontSize: "0.75rem" }}>
          {g.recipeName} only. {others} left out, because clicks only compare within one recipe.
        </p>
      )}
      {g.bestZone && (
        <div style={{ marginTop: 12 }}>
          <StatusLine status="good" lead={g.bestZone.first === g.bestZone.last ? `Best at ${g.bestZone.first} clicks` : `Best zone ${g.bestZone.first}–${g.bestZone.last} clicks`} rest={g.bestZoneAvg != null ? `avg ${oneDp(g.bestZoneAvg)}` : undefined} />
        </div>
      )}
    </SoftCard>
  );
}
