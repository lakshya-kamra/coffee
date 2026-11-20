/**
 * Port of HomeScreen.kt (web-spec §11 Today), adaptive in v4 (§3.1): the playful title with the
 * steaming mug that fills with today's brews, the headline, a "Brew in progress" banner (§12.5),
 * the Personalise card (A§7.8), YOUR FIRST BREW (§4.6) or the NEXT BREW pass, the stats, recent
 * brews, Your coffee and Last grind tiles, START FROM and the backup nudge (§8.5).
 *
 * One tree for every window: todayLayout() picks column (phone), split (medium) or dashboard
 * (expanded and up) and the CSS places the same blocks. Everything reads the hot store
 * synchronously, so the tab's first frame has its data (rule 5).
 */
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type MouseEvent } from "react";
import { openInTab } from "@/app/panes";
import { prefetchers } from "@/app/registry";
import { nav, useRouter } from "@/app/router";
import shell from "@/app/shell.module.css";
import { openPalette } from "@/app/shellUi";
import { useWindowClass } from "@/app/windowClass";
import { CoffeeMug, EmptyArt } from "@/art";
import { Brewers } from "@/catalog/catalog";
import { db } from "@/data/db";
import { repo, type ActiveBrew } from "@/data/repo";
import type { BeanRow, BrewRow } from "@/data/schema";
import { settings as settingsStore } from "@/data/settings";
import { shouldNudgeBackup, storageStatus } from "@/data/storage";
import { useActiveBrew, useBeans, useBrews, useGrinders, useRecipes, useSettings } from "@/data/store";
import { EyebrowLabel, Icon, IconWell, PrimaryButton, QuietButton, ScreenHeader, SectionLabel, SoftCard, StatusDot, StatusLine, toCaps, tooltipProps, useContextMenu } from "@/design/components";
import { brewElapsedMs, isAbandoned } from "@/domain/core/activeBrew";
import { brewerRowTag, ownedFamilies, type Brewer } from "@/domain/core/brewer";
import { clipWords, formatDuration, grams, oneDp, screenDayLabel, screenTime, todayEyebrow } from "@/domain/core/format";
import { noScaleAmounts } from "@/domain/core/noScale";
import { totalSec, type Recipe } from "@/domain/core/recipe";
import { roastStatus, roastWord, scoreStatus, statusColorVar, timeStatus } from "@/domain/core/status";
import { temp, tempUnit, unitsOf, type Units } from "@/domain/core/units";
import { StatGrid } from "@/features/recipes/StatGrid";
import { ConfirmSheet, RecipeCard, RecipeHeader, RecipeRoute } from "@/viz";
import { brewRecipe, useBrewNext } from "./brewNext";
import { firstBrewCard, noRecipeLine, type FirstBrewCard } from "./firstBrewCard";
import {
  RECENT_WINDOW,
  beanOnDeck,
  brewsThisWeek,
  brewsToday,
  daysOffRoast,
  headline,
  lastFiveAvg,
  lastGrindFor,
  lastLogFor,
  mugFill,
  nextRecipe,
  playfulTitle,
  prevFiveAvg,
  startFromRecipes,
  streakDays,
} from "./homeModel";
import { todayLayout } from "./todayLayout";
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
const brewAgain = (log: BrewRow) => {
  void prefetchers.brew().catch(() => undefined);
  nav.push(`/brew/${encodeURIComponent(log.recipeId)}/again/${encodeURIComponent(log.uid)}`);
};
// Lands in Log's pane at expanded and up, with the brew selected (v4 §3.1).
const openBrew = (log: BrewRow) => openInTab("log", `/log/${encodeURIComponent(log.uid)}`);

export function TodayScreen() {
  const allBrews = useBrews();
  const recipes = useRecipes();
  const settings = useSettings();
  const active = useActiveBrew();
  const grinders = useGrinders();
  const beans = useBeans();
  const now = useNow();
  const wc = useWindowClass();
  const layout = useMemo(() => todayLayout(wc), [wc]);
  const { startCount, yourCoffee } = layout;
  const units = unitsOf(settings);
  const logs = useMemo(() => allBrews.slice(0, RECENT_WINDOW), [allBrews]);
  const today = brewsToday(logs, now);
  const h = headline(logs);
  const loaded = recipes.length > 0;
  const families = useMemo(() => ownedFamilies(settings.ownedBrewerIds, (id) => Brewers.byId(id)), [settings.ownedBrewerIds]);
  const next = useMemo(() => nextRecipe(recipes, logs, settings, (id) => Brewers.byId(id)), [recipes, logs, settings]);
  const first = useMemo(
    () => (logs.length === 0 ? firstBrewCard(recipes, settings, grinders, (id) => Brewers.byId(id)) : null),
    [logs.length, recipes, settings, grinders],
  );
  // With no brews, the first-brew pick is what B and Start brew mean.
  const brewNext = first?.kind === "recipe" ? first.recipe : first?.kind === "none" ? null : next;
  const front = useRouter((s) => s.tab === "today" && s.depth === 0);
  useBrewNext(brewNext, "today", front);

  const recent = logs.slice(0, layout.recentCount);
  const targetById = useMemo(() => new Map(recipes.map((r) => [r.id, totalSec(r)])), [recipes]);
  const startFrom = useMemo(() => startFromRecipes(recipes, families, next?.id ?? null, startCount), [recipes, families, next, startCount]);
  const firstOwned = useMemo(() => settings.ownedBrewerIds.map((id) => Brewers.byId(id)).find((b) => b != null) ?? null, [settings.ownedBrewerIds]);
  const bean = useMemo(() => (yourCoffee ? beanOnDeck(beans, settings.defaultBeanId, logs) : null), [yourCoffee, beans, settings.defaultBeanId, logs]);
  const showTiles = loaded && (layout.yourCoffee || layout.lastGrind);

  return (
    <div className={`${t.today} ${shell.screen}`} data-mode={layout.mode} data-size={wc.width} data-inline={layout.startInline || undefined} style={layout.maxWidth ? { maxWidth: layout.maxWidth } : undefined}>
      <div className={t.head}>
        <ScreenHeader
          eyebrow={todayEyebrow(now)}
          title={playfulTitle(today)}
          trailing={
            <span className={t.headTrailing}>
              {layout.mode === "column" && <IconWell icon="Search" label="Search or jump" keys="mod+k" onClick={() => openPalette()} />}
              <HomeMug brewsToday={today} />
            </span>
          }
          status={loaded ? <StatusLine status={h.status} lead={h.lead} rest={h.detail ?? undefined} /> : undefined}
        />
      </div>

      <div className={t.left}>
        <div className={t.lead}>
          {active && <InProgress active={active} recipe={recipes.find((r) => r.id === active.recipeId) ?? null} />}
          {/* Setup never ran (restored a backup, or backed out of /welcome): offer it once, quietly. */}
          {(settings.onboardingAuto || !settings.onboardingDone) && !settings.personaliseDismissed && <PersonaliseCard />}
          {loaded && first ? (
            <FirstBrew card={first} hasScale={settings.hasScale} />
          ) : (
            loaded && next && <NextBrewPass recipe={next} lastGrind={lastGrindFor(logs, next.id)} lastLog={lastLogFor(logs, next.id)} units={units} hasScale={settings.hasScale} />
          )}
        </div>
        {showTiles && (
          <div className={t.tiles}>
            <StatGrid
              tiles
              label="Your coffee and grind"
              cells={[
                ...(layout.yourCoffee ? [yourCoffeeCell(bean, now)] : []),
                // Only once there is a brew to have a grind from.
                ...(layout.lastGrind && next && logs.length > 0 ? [lastGrindCell(lastLogFor(logs, next.id), next)] : []),
              ]}
            />
            {layout.yourCoffee && !bean && <QuietButton text="Add the coffee you're drinking" className={t.tileAction} onClick={() => nav.switchTab("gear", "beans")} />}
          </div>
        )}
      </div>

      <div className={t.right}>
        {loaded && (
          <div className={t.stats}>
            <Stats logs={logs} now={now} tiles={layout.mode === "dashboard"} />
          </div>
        )}
        {loaded && (
          <section className={t.recentBlock} aria-label="Recent brews">
            <SectionLabel text="RECENT" trailing={logs.length ? "See log" : undefined} onTrailing={() => nav.switchTab("log")} />
            {recent.length === 0 ? <EmptyRecent art={firstOwned} /> : <RecentList logs={recent} targetById={targetById} />}
          </section>
        )}
      </div>

      {loaded && startFrom.length > 0 && (
        <section className={t.start} aria-label="Start from">
          <SectionLabel text="START FROM" trailing={`All ${recipes.length}`} onTrailing={() => nav.switchTab("recipes")} />
          {layout.startCols === 1 ? (
            <div className={t.carousel} role="list" aria-label="Recipes">
              {startFrom.map((r) => (
                <div key={r.id} role="listitem" className={t.carouselItem}>
                  <RecipeCard recipe={r} sharedPrefix="home" compact onOpen={() => openRecipe(r.id, "home")} onPrefetch={warmRecipe} />
                </div>
              ))}
            </div>
          ) : (
            <ul className={t.startGrid} style={{ ["--cols" as string]: layout.startCols }} aria-label="Recipes">
              {startFrom.map((r) => (
                <li key={r.id} className={t.cardCell}>
                  <RecipeCard recipe={r} sharedPrefix="home" onOpen={() => openRecipe(r.id, "home")} onPrefetch={warmRecipe} />
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      {loaded && (
        <div className={t.nudge}>
          <BackupNudge brewCount={allBrews.length} />
        </div>
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
      const text = formatDuration(Math.floor(brewElapsedMs(active, Date.now()) / 1000));
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
            {left ? "Brew left unfinished" : active.running ? "Brew in progress" : "Brew paused"} · <span ref={leaf} className="num-inline" />
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
          safe="cancel"
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

/** "6 level tablespoons → 500 ml (2 mugs)" for someone without a scale (v4 §4.3). */
function NoScaleLine({ recipe }: { recipe: Recipe }) {
  const a = noScaleAmounts(recipe.doseG, recipe.waterG, recipe.brewer.method);
  return (
    <span className="t-body-m">
      {a.coffeeTbsp != null ? a.coffeeLine : a.basket} · {recipe.brewer.method === "espresso" ? "" : `${a.water} water `}
      <span className="ink-low">
        ({grams(recipe.doseG)} g → {grams(recipe.waterG)} g)
      </span>
    </span>
  );
}

function NextBrewPass({ recipe, lastGrind, lastLog, units, hasScale }: { recipe: Recipe; lastGrind: number | null; lastLog: BrewRow | null; units: Units; hasScale: boolean }) {
  const target = totalSec(recipe);
  const eyebrow = (
    <span className={t.passEyebrow}>
      <EyebrowLabel text="NEXT BREW" />
    </span>
  );
  return (
    <div className={t.pass}>
      <button type="button" className={t.passOpen} onClick={() => openRecipe(recipe.id, "next")} onPointerDown={warmRecipe}>
        <RecipeHeader recipe={recipe} size="card" sharedPrefix="next" eyebrow={eyebrow} />
        <span className={t.passBody}>
          <RecipeRoute recipe={recipe} numeral="num-large" />
          {!hasScale && <NoScaleLine recipe={recipe} />}
          <StatGrid
            cells={[
              // Says where the number came from: your last brew of this recipe, not the recipe.
              lastGrind != null
                ? { key: "grind", label: "GRIND", value: String(lastGrind), unit: "clicks", sub: "last used" }
                : { key: "grind", label: "GRIND", value: recipe.grind || null, text: true },
              { key: "temp", label: "TEMP", value: temp(units, recipe.tempC), unit: tempUnit(units) },
              { key: "time", label: recipe.brewer.method === "espresso" ? "SHOT TIME" : "TARGET", value: formatDuration(target) },
            ]}
          />
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
        <PrimaryButton text="Start brew" icon="Play" height={52} className={t.inverse} onClick={() => brewRecipe(recipe.id)} />
      </div>
    </div>
  );
}

/** YOUR FIRST BREW (v4 §4.6): the recipe, what to have ready, three things to do, one button. */
function FirstBrew({ card, hasScale }: { card: FirstBrewCard; hasScale: boolean }) {
  if (card.kind === "none") {
    return (
      <SoftCard className={t.block}>
        <EyebrowLabel text="YOUR FIRST BREW" />
        <p className="t-body-m">{noRecipeLine(card.missing)}</p>
        <div className={t.actions}>
          <QuietButton text="See all recipes" color="var(--ink-high)" onClick={() => nav.switchTab("recipes")} />
        </div>
      </SoftCard>
    );
  }
  const r = card.recipe;
  return (
    <section className={t.first} aria-labelledby="first-brew-title">
      <EyebrowLabel text="YOUR FIRST BREW" />
      <button type="button" className={t.firstOpen} onClick={() => openRecipe(r.id, "next")} onPointerDown={warmRecipe}>
        <span id="first-brew-title" className={`t-headline-s ${t.firstName}`} data-title="">
          {r.name}
        </span>
        <span className="micro ink-low">
          {toCaps(r.author)} · {toCaps(r.brewer.label)}
        </span>
      </button>
      <EyebrowLabel text="YOU'LL NEED" />
      <ul className={t.needs} aria-label="You'll need">
        {card.needs.map((n) => (
          <li key={n} className={`t-label-l ${t.need}`}>
            {n}
          </li>
        ))}
      </ul>
      <ol className={t.checklist}>
        {card.steps.map((s, i) => (
          <li key={i}>
            <span className={`num-small ${t.checkNum}`} aria-hidden="true">
              {i + 1}
            </span>
            <span className="t-body-m">{s}</span>
          </li>
        ))}
      </ol>
      {!hasScale && <p className="t-body-s ink-mid">No scale needed: we'll show spoons and ml as you go.</p>}
      <div className={t.passCta}>
        <PrimaryButton text="Start my first brew" icon="Play" height={52} className={t.inverse} onClick={() => brewRecipe(r.id)} />
      </div>
      {card.missing.length > 0 && <p className="t-body-s ink-low">{noRecipeLine(card.missing)}</p>}
    </section>
  );
}

function Stats({ logs, now, tiles }: { logs: readonly BrewRow[]; now: number; tiles: boolean }) {
  const has = logs.length > 0;
  const last5 = lastFiveAvg(logs);
  const prev5 = prevFiveAvg(logs);
  const delta = last5 != null && prev5 != null ? last5 - prev5 : null;
  // "1 day", not "1 days".
  const streak = streakDays(logs, now);
  const week = brewsThisWeek(logs, now);
  return (
    <StatGrid
      tiles={tiles}
      label="Your brewing"
      cells={[
        // Every cell carries a quiet sub line, and the delta says what it is compared with.
        { key: "streak", label: "STREAK", value: has ? String(streak) : null, unit: streak === 1 ? "day" : "days", sub: has ? "in a row" : null },
        { key: "week", label: "THIS WEEK", value: has ? String(week) : null, unit: week === 1 ? "brew" : "brews", sub: has ? "last 7 days" : null },
        {
          key: "last5",
          label: "LAST 5",
          value: last5 != null ? oneDp(last5) : null,
          status: last5 != null ? scoreStatus(Math.round(last5)) : null,
          sub: delta != null ? `${delta >= 0 ? "▲" : "▼"} ${oneDp(Math.abs(delta))} vs prev 5` : null,
          subStatus: delta != null ? (delta >= 0 ? "good" : "attention") : null,
          subChip: false,
        },
      ]}
    />
  );
}

function yourCoffeeCell(bean: BeanRow | null, now: number) {
  if (!bean) return { key: "coffee", label: "YOUR COFFEE", value: null, sub: "No coffee added yet" };
  const days = daysOffRoast(bean, now);
  const word = roastWord(days);
  const name = clipWords([bean.roaster, bean.name].filter(Boolean).join(" · "), 40);
  return {
    key: "coffee",
    label: "YOUR COFFEE",
    value: days != null ? String(days) : name,
    text: days == null,
    unit: days != null ? (days === 1 ? "day off roast" : "days off roast") : null,
    status: days != null ? roastStatus(days) : null,
    // The colour comes with its word ("resting", "fading"); an in-window coffee needs neither.
    sub: days != null ? (word ? `${name} · ${word}` : name) : "Roast date not set",
    subStatus: word ? roastStatus(days) : null,
    subChip: false,
  };
}

function lastGrindCell(log: BrewRow | null, recipe: Recipe) {
  if (!log) return { key: "grind", label: "LAST GRIND", value: null, sub: `Not brewed ${clipWords(recipe.name, 24)} yet` };
  const pre = log.grindKind === "preground";
  const clicks = log.grindClicks != null && log.grindClicks > 0 ? log.grindClicks : null;
  return {
    key: "grind",
    label: "LAST GRIND",
    value: pre ? "As bought" : clicks != null ? String(clicks) : log.grindSetting.trim() || null,
    unit: clicks != null ? "clicks" : null,
    text: pre || clicks == null,
    sub: log.grinderLabel || (pre ? "Pre-ground" : null),
  };
}

/** Recent brews: open in Log (its pane at expanded and up), or brew the same again. */
function RecentList({ logs, targetById }: { logs: readonly BrewRow[]; targetById: ReadonlyMap<string, number> }) {
  const target = useRef<BrewRow | null>(null);
  const menu = useContextMenu(() => {
    const log = target.current;
    if (!log) return [];
    return [
      { label: "Open in Log", icon: "History" as const, onSelect: () => openBrew(log) },
      { label: "Brew again", icon: "Replay" as const, keys: "b", onSelect: () => brewAgain(log) },
    ];
  }, "Brew actions");
  return (
    <>
      <ul className={t.recentList}>
        {logs.map((log) => (
          <RecentRow
            key={log.uid}
            log={log}
            targetSec={targetById.get(log.recipeId) ?? null}
            onContextMenu={(e) => {
              target.current = log;
              menu.onContextMenu(e);
            }}
          />
        ))}
      </ul>
      {menu.element}
    </>
  );
}

function RecentRow({ log, targetSec, onContextMenu }: { log: BrewRow; targetSec: number | null; onContextMenu: (e: MouseEvent<HTMLElement>) => void }) {
  const brewer = Brewers.byId(log.brewerId) ?? Brewers.resolveOrNull(log.brewer);
  const tag = brewer ? brewerRowTag(brewer) : log.brewer;
  const name = log.recipeName.trim() || "Brew";
  const grind = log.grindKind === "preground" ? "as bought" : log.grindClicks != null ? `${log.grindClicks} clk` : log.grindSetting.trim() ? clipWords(log.grindSetting, 14) : null;
  const st = targetSec != null && targetSec > 0 && log.totalTimeSec > 0 ? timeStatus(log.totalTimeSec, targetSec)[0] : null;
  return (
    <li className={`interactive-row ${t.recentRow}`} onContextMenu={onContextMenu}>
      {/* Named by its own text (WCAG 2.5.3: the name holds what is visible), plus the score in words. */}
      <button type="button" className={t.recentOpen} onClick={() => openBrew(log)}>
        <span className={t.recentWhen}>
          <span className="micro ink-low">{screenDayLabel(log.startedAt)}</span>
          <span className="num-inline">{screenTime(log.startedAt)}</span>
        </span>
        <span className={t.recentMain}>
          <span className={`t-title-m ${t.recentName}`} data-title="">
            {name}
          </span>
          <span className="t-body-s ink-mid">
            {[tag, grind].filter(Boolean).join(" · ")}
            {log.totalTimeSec > 0 && (
              <>
                {" · "}
                <span className={t.nowrap} style={{ color: st && st !== "good" ? statusColorVar(st) : undefined }}>
                  {formatDuration(log.totalTimeSec)}
                </span>
              </>
            )}
          </span>
        </span>
        <span className={`num-small ${t.recentScore}`} style={{ color: log.overall > 0 ? statusColorVar(scoreStatus(log.overall)) : "var(--ink-low)" }} aria-hidden="true">
          {log.overall > 0 ? log.overall : "—"}
        </span>
        <span className="visually-hidden">{log.overall > 0 ? `, scored ${log.overall} of 10` : ", not rated"}</span>
      </button>
      <button type="button" className={`row-more ${t.again}`} aria-label={`Brew again: ${name}`} {...tooltipProps("Brew again")} onClick={() => brewAgain(log)}>
        <Icon name="Replay" size={20} />
      </button>
    </li>
  );
}

/** No brews yet: your own brewer (or the dripper), small, and the way to a first one. */
function EmptyRecent({ art }: { art: Brewer | null }) {
  return (
    <SoftCard>
      <span className={t.empty}>
        <EmptyArt brewer={art} size={56} />
        <span className={t.emptyText}>
          <span className="t-body-m ink-mid">Brews you finish show up here.</span>
          <QuietButton text="See recipes" className={t.emptyAction} onClick={() => nav.switchTab("recipes")} />
        </span>
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

