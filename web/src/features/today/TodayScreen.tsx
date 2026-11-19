/**
 * Port of HomeScreen.kt (web-spec §11 Today): the playful title with the steaming mug that
 * fills with today's brews, the headline, a "Brew in progress" banner (§12.5), the Personalise
 * card (A§7.8), the NEXT BREW pass, the stats strip, recent brews, the START FROM carousel and
 * the backup nudge (§8.5). Everything reads the hot store synchronously, so the tab's first frame
 * has its data (rule 5).
 */
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { prefetchers } from "@/app/registry";
import { nav } from "@/app/router";
import shell from "@/app/shell.module.css";
import { CoffeeMug, Dripper } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo, type ActiveBrew } from "@/data/repo";
import type { BrewRow } from "@/data/schema";
import { settings as settingsStore } from "@/data/settings";
import { shouldNudgeBackup, storageStatus } from "@/data/storage";
import { useActiveBrew, useBrews, useRecipes, useSettings } from "@/data/store";
import { EyebrowLabel, LogRow, PrimaryButton, QuietButton, ScreenHeader, SectionLabel, SoftCard, StatusDot, StatusLine, groupPosition } from "@/design/components";
import { brewElapsedMs, isAbandoned } from "@/domain/core/activeBrew";
import { clock, greeting, oneDp } from "@/domain/core/format";
import { totalSec, type Recipe } from "@/domain/core/recipe";
import { scoreStatus, timeStatus } from "@/domain/core/status";
import { temp, tempUnit, unitsOf, type Units } from "@/domain/core/units";
import { ConfirmSheet, InfoGrid, RecipeCard, RecipeHeader, RecipeRoute } from "@/viz";
import {
  RECENT_WINDOW,
  brewsThisWeek,
  brewsToday,
  eyebrowDate,
  headline,
  lastFiveAvg,
  lastGrindFor,
  lastLogFor,
  mugFill,
  nextRecipe,
  playfulTitle,
  prevFiveAvg,
  streakDays,
} from "./homeModel";
import t from "./today.module.css";

const NUDGE_KEY = "backupNudgeAt";
// Written by features/backup/meta.ts as { lastBackupAt: epoch ms }.
const LAST_BACKUP_KEY = "backup";

/** Re-reads the clock when the tab comes back and every ten minutes: enough for "today" and the greeting. */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const id = setInterval(tick, 10 * 60_000);
    const onVis = () => !document.hidden && tick();
    document.addEventListener("visibilitychange", onVis);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, []);
  return now;
}

const openRecipe = (id: string, from: "home" | "next") => nav.push(`/recipes/${encodeURIComponent(id)}`, { from });
const warmRecipe = () => void prefetchers.recipe().catch(() => undefined);

export function TodayScreen() {
  const allBrews = useBrews();
  const recipes = useRecipes();
  const settings = useSettings();
  const active = useActiveBrew();
  const now = useNow();
  const units = unitsOf(settings);
  const logs = useMemo(() => allBrews.slice(0, RECENT_WINDOW), [allBrews]);
  const today = brewsToday(logs, now);
  const h = headline(logs);
  const loaded = recipes.length > 0;
  const next = useMemo(() => nextRecipe(recipes, logs, settings, (id) => Brewers.byId(id)), [recipes, logs, settings]);
  const recent = logs.slice(0, 3);
  const targetById = useMemo(() => new Map(recipes.map((r) => [r.id, totalSec(r)])), [recipes]);

  return (
    <div className={`column ${shell.screen}`}>
      <ScreenHeader
        eyebrow={eyebrowDate(greeting(new Date(now).getHours()), now)}
        title={playfulTitle(today)}
        trailing={<HomeMug brewsToday={today} />}
        status={loaded ? <StatusLine status={h.status} lead={h.lead} rest={h.detail ?? undefined} /> : undefined}
      />
      {active && <InProgress active={active} recipe={recipes.find((r) => r.id === active.recipeId) ?? null} />}
      {/* Setup never ran (restored a backup, or backed out of /welcome): offer it once, quietly. */}
      {(settings.onboardingAuto || !settings.onboardingDone) && !settings.personaliseDismissed && <PersonaliseCard />}
      {loaded && next && (
        <NextBrewPass recipe={next} firstBrew={logs.length === 0} lastGrind={lastGrindFor(logs, next.id)} lastLog={lastLogFor(logs, next.id)} units={units} />
      )}
      {loaded && <StatsStrip logs={logs} now={now} />}
      {loaded && (
        <>
          <SectionLabel text="RECENT" trailing={logs.length ? "See log" : undefined} onTrailing={() => nav.switchTab("log")} />
          {recent.length === 0 ? (
            <EmptyRecent />
          ) : (
            <div className={t.recent}>
              {recent.map((log, i) => (
                <LogRow key={log.uid} log={log} position={groupPosition(i, recent.length)} targetSec={targetById.get(log.recipeId) ?? null} onOpen={() => nav.push(`/log/${encodeURIComponent(log.uid)}`)} />
              ))}
            </div>
          )}
          <SectionLabel text="START FROM" trailing={`All ${recipes.length}`} onTrailing={() => nav.switchTab("recipes")} />
          <div className={t.carousel} role="list" aria-label="Recipes">
            {recipes.map((r) => (
              <div key={r.id} role="listitem" className={t.carouselItem}>
                <RecipeCard recipe={r} sharedPrefix="home" compact onOpen={() => openRecipe(r.id, "home")} onPrefetch={warmRecipe} />
              </div>
            ))}
          </div>
          <BackupNudge brewCount={allBrews.length} />
        </>
      )}
    </div>
  );
}

/**
 * The app's signature: a small steaming mug that fills with today's brews (a sliver at zero, full
 * at three). Light mode draws the shell one step deeper, or light ceramic vanishes on paper.
 */
function HomeMug({ brewsToday: n }: { brewsToday: number }) {
  const fill = mugFill(n);
  return (
    <span className={t.mug}>
      <CoffeeMug fill={fill} size={72} body="var(--mug-body)" steamStrength={fill} label={n === 1 ? "One coffee today" : `${n} coffees today`} />
    </span>
  );
}

function InProgress({ active, recipe }: { active: ActiveBrew; recipe: Recipe | null }) {
  const leaf = useRef<HTMLSpanElement>(null);
  const [asking, setAsking] = useState(false);
  const planSec = recipe ? totalSec(recipe) : null;
  // A brew started by mistake or walked away from (or whose recipe is gone): say so, and offer the way out right here.
  const [left, setLeft] = useState(() => planSec == null || isAbandoned(active, planSec, Date.now()));
  useLayoutEffect(() => {
    // Once a second is all a resting banner needs; it writes text, and state only when the brew is given up on.
    const write = () => {
      const text = clock(Math.floor(brewElapsedMs(active, Date.now()) / 1000));
      const el = leaf.current;
      if (el && el.textContent !== text) el.textContent = text;
    };
    write();
    const id = setInterval(() => {
      write();
      setLeft(planSec == null || isAbandoned(active, planSec, Date.now()));
    }, 1000);
    return () => clearInterval(id);
  }, [active, planSec]);
  const go = () => nav.push(`/brew/${encodeURIComponent(active.recipeId)}`);
  return (
    <div className={t.banner}>
      <button type="button" className={t.bannerOpen} onClick={go} onPointerDown={() => void prefetchers.brew().catch(() => undefined)}>
        <StatusDot status={active.running && !left ? "live" : "attention"} />
        <span className={t.bannerText}>
          <span className="t-title-m">
            {left ? "Brew left unfinished" : active.running ? "Brew in progress" : "Brew paused"} · <span ref={leaf} />
          </span>
          {recipe && <span className="t-body-s ink-mid">{recipe.name}</span>}
        </span>
        <span className="t-label-l ink-mid">Open</span>
      </button>
      {left && (
        <button type="button" className={`${t.bannerDiscard} t-label-l`} onClick={() => setAsking(true)}>
          Discard
        </button>
      )}
      {asking && (
        <ConfirmSheet
          title="Discard this brew?"
          body="Nothing from it is kept or added to your log."
          confirmText="Discard"
          danger
          onClose={() => setAsking(false)}
          onConfirm={() => void repo.setActiveBrew(null)}
        />
      )}
    </div>
  );
}

function PersonaliseCard() {
  return (
    <SoftCard className={t.block}>
      <EyebrowLabel text="MAKE IT YOURS" />
      <p className="t-body-m">Pick your grinder from the list and the brewers you own.</p>
      <div className={t.actions}>
        <QuietButton text="Not now" onClick={() => void settingsStore.set("personaliseDismissed", true)} />
        <QuietButton text="Personalise" color="var(--ink-high)" onClick={() => nav.push("/welcome")} />
      </div>
    </SoftCard>
  );
}

function NextBrewPass({ recipe, firstBrew, lastGrind, lastLog, units }: { recipe: Recipe; firstBrew: boolean; lastGrind: number | null; lastLog: BrewRow | null; units: Units }) {
  const target = totalSec(recipe);
  const eyebrow = (
    <span className={t.passEyebrow}>
      <EyebrowLabel text={firstBrew ? "FIRST BREW" : "NEXT BREW"} />
    </span>
  );
  return (
    <div className={t.pass}>
      <button type="button" className={t.passOpen} onClick={() => openRecipe(recipe.id, "next")} onPointerDown={warmRecipe}>
        <RecipeHeader recipe={recipe} size="card" sharedPrefix="next" eyebrow={eyebrow} />
        <span className={t.passBody}>
          <RecipeRoute recipe={recipe} numeral="num-large" />
          <span className={t.passGrid}>
            <InfoGrid
              cells={[
                // Says where the number came from: your last brew of this recipe, not the recipe.
                lastGrind != null ? { label: "GRIND", value: String(lastGrind), unit: "clicks", sub: "last used" } : { label: "GRIND", value: recipe.grind || null, textValue: true },
                { label: "TEMP", value: temp(units, recipe.tempC), unit: tempUnit(units) },
                { label: "TARGET", value: clock(target) },
              ]}
            />
          </span>
          {lastLog && (
            <StatusLine
              status={scoreStatus(lastLog.overall)}
              lead={lastLog.overall > 0 ? `Last time ${lastLog.overall}/10` : "Last time unrated"}
              rest={timeStatus(lastLog.totalTimeSec, target)[1].toLowerCase()}
            />
          )}
        </span>
      </button>
      <div className={t.passCta}>
        {/* Inverse, not crema: crema is kept for things that are live, and nothing is yet. */}
        <PrimaryButton text="Start brew" icon="Play" height={52} className={t.inverse} onClick={() => nav.push(`/brew/${encodeURIComponent(recipe.id)}`)} />
      </div>
    </div>
  );
}

function StatsStrip({ logs, now }: { logs: readonly BrewRow[]; now: number }) {
  const has = logs.length > 0;
  const last5 = lastFiveAvg(logs);
  const prev5 = prevFiveAvg(logs);
  const delta = last5 != null && prev5 != null ? last5 - prev5 : null;
  // "1 day", not "1 days".
  const streak = streakDays(logs, now);
  const week = brewsThisWeek(logs, now);
  return (
    <div className={t.stats}>
      <InfoGrid
        cells={[
          // Every cell carries a quiet sub line, and the delta says what it is compared with.
          { label: "STREAK", value: has ? String(streak) : null, unit: streak === 1 ? "day" : "days", sub: has ? "in a row" : null },
          { label: "THIS WEEK", value: has ? String(week) : null, unit: week === 1 ? "brew" : "brews", sub: has ? "last 7 days" : null },
          {
            label: "LAST 5",
            value: last5 != null ? oneDp(last5) : null,
            status: last5 != null ? scoreStatus(Math.round(last5)) : null,
            sub: delta != null ? `${delta >= 0 ? "▲" : "▼"} ${oneDp(Math.abs(delta))} vs prev 5` : null,
            subStatus: delta != null ? (delta >= 0 ? "good" : "attention") : null,
            subChip: false,
          },
        ]}
      />
    </div>
  );
}

/** No brews yet: the dripper, small, instead of a bare sentence. */
function EmptyRecent() {
  return (
    <SoftCard>
      <span className={t.empty}>
        <span className={t.emptyArt}>
          <Dripper waterLevel={0.5} size={56} shell="var(--mug-body)" />
        </span>
        <span className="t-body-m ink-mid">Brews you finish show up here.</span>
      </span>
    </SoftCard>
  );
}

/** Once per 30 days, with 10+ brews, no backup in 30 days and storage not persisted (§8.5). */
function BackupNudge({ brewCount }: { brewCount: number }) {
  const [show, setShow] = useState(false);
  useEffect(() => {
    if (brewCount < 10) return;
    let live = true;
    void (async () => {
      const [status, nudge, backup] = await Promise.all([storageStatus(), db.kv.get(NUDGE_KEY), db.kv.get(LAST_BACKUP_KEY)]);
      const num = (v: unknown) => (typeof v === "number" ? v : null);
      const saved = num((backup?.value as { lastBackupAt?: unknown } | undefined)?.lastBackupAt);
      if (live) setShow(shouldNudgeBackup({ brewCount, persisted: status.persisted, lastNudgeAt: num(nudge?.value), lastBackupAt: saved }));
    })();
    return () => {
      live = false;
    };
  }, [brewCount]);
  if (!show) return null;
  return (
    <SoftCard className={t.block}>
      <EyebrowLabel text="YOUR DATA" />
      <p className="t-body-m">Your brews live only in this browser. Save a backup now and then.</p>
      <div className={t.actions}>
        <QuietButton
          text="Not now"
          onClick={() => {
            setShow(false);
            // A web-only flag in kv (not a setting): when the nudge was last waved away.
            void db.kv.put({ key: NUDGE_KEY, value: Date.now() });
          }}
        />
        <QuietButton text="Save a backup" color="var(--ink-high)" onClick={() => nav.switchTab("gear", "app")} />
      </div>
    </SoftCard>
  );
}
