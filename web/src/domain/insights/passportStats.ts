/**
 * Port of ui/screens/insights/PassportStats.kt: pure functions over the log, so the Passport and
 * both charts are unit tested without React or IndexedDB. Logs may arrive in any order; every
 * function sorts what it needs. Days are LOCAL calendar days (epochDayOf), as on Android.
 */
import { Brewers } from "@/catalog/catalog";
import type { Brewer } from "@/domain/core/brewer";
import { epochDayOf } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { encodeDialFormat, scaleFor } from "@/domain/grind";
import type { BeanRow, BrewRow, GrinderRow } from "@/data/schema";

/** A brewer in the passport. `brewer` is null for a label the app no longer knows. */
export interface Stamp {
  readonly brewer: Brewer | null;
  readonly label: string;
  readonly count: number;
}

/** Lifetime totals for the Brew Passport. Every average and best ignores unrated brews. */
export interface Passport {
  readonly brews: number;
  readonly waterG: number;
  readonly coffeeG: number;
  readonly streak: number;
  readonly bestStreak: number;
  readonly avg: number | null;
  readonly best: number | null;
  readonly greatCups: number;
  readonly firstAt: number | null;
  readonly spanDays: number;
  /** One stamp per brewer used, most used first. */
  readonly stamps: readonly Stamp[];
  readonly recipesUsed: number;
  readonly coffees: number;
}

export interface ScorePoint {
  readonly id: number;
  readonly uid: string;
  readonly at: number;
  readonly score: number;
}

export interface Ranked {
  readonly name: string;
  readonly sub: string | null;
  readonly count: number;
  readonly avg: number | null;
}

export interface GrindPoint {
  readonly id: number;
  readonly at: number;
  readonly clicks: number;
  readonly score: number;
}

/**
 * One grinder's journey on ONE recipe. Clicks only mean the same thing within a recipe, so other
 * recipes are counted in `otherBrews` instead of being plotted on the same axis.
 */
export interface GrindSeries {
  readonly grinderLabel: string;
  readonly points: readonly GrindPoint[];
  readonly mainRecipeId: string;
  readonly bestZone: { readonly first: number; readonly last: number } | null;
  readonly bestZoneAvg: number | null;
  readonly first: number | null;
  readonly latest: number | null;
  readonly lowerIsFiner: boolean;
  readonly recipeName: string;
  readonly otherBrews: number;
  readonly clicksPerTurn: number;
  /** The grinder's encoded DialFormat, so the screen reads clicks the way the dial does. */
  readonly dialFormat: string;
}

/** The best-scoring cup. Ties go to the most recent, because that is the one worth repeating. */
export interface BestCup {
  readonly id: number;
  readonly uid: string;
  readonly at: number;
  readonly score: number;
  readonly recipeName: string;
  readonly clicks: number | null;
  readonly grindSetting: string;
  /** 0 when the brew didn't record one. */
  readonly tempC: number;
}

/** `end` is a local epoch day. */
export interface StreakRun {
  readonly days: number;
  readonly end: number;
  readonly current: boolean;
}

export interface PersonalBests {
  readonly bestCup: BestCup | null;
  readonly longestStreak: StreakRun | null;
  readonly bestCoffee: Ranked | null;
}

function averageScore(logs: readonly BrewRow[]): number | null {
  return logs.length === 0 ? null : logs.reduce((s, l) => s + l.overall, 0) / logs.length;
}

function newestScored(logs: readonly BrewRow[]): BrewRow[] {
  return logs.filter((l) => l.overall > 0).sort((a, b) => b.startedAt - a.startedAt);
}

/** Every run of consecutive days as [last day, length], in chronological order. */
function runs(days: Iterable<number>): [number, number][] {
  const out: [number, number][] = [];
  let prev: number | null = null;
  let length = 0;
  for (const day of [...new Set(days)].sort((a, b) => a - b)) {
    if (prev != null && prev + 1 === day) length++;
    else {
      if (prev != null) out.push([prev, length]);
      length = 1;
    }
    prev = day;
  }
  if (prev != null) out.push([prev, length]);
  return out;
}

/** (current, best) runs of brewing days. The current run ends today, or yesterday when today has none. */
export function streaks(logs: readonly BrewRow[], today: number = epochDayOf(Date.now())): [number, number] {
  if (logs.length === 0) return [0, 0];
  const days = new Set(logs.map((l) => epochDayOf(l.startedAt)));
  let cursor = today;
  if (!days.has(cursor)) cursor--;
  let current = 0;
  while (days.has(cursor)) {
    current++;
    cursor--;
  }
  const best = Math.max(0, ...runs(days).map(([, n]) => n));
  return [current, Math.max(best, current)];
}

/** The longest run of consecutive days, latest first on a tie, or null with no brews. */
export function longestStreak(logs: readonly BrewRow[], today: number = epochDayOf(Date.now())): StreakRun | null {
  if (logs.length === 0) return null;
  const all = runs(logs.map((l) => epochDayOf(l.startedAt)));
  const longest = Math.max(...all.map(([, n]) => n));
  // runs() is chronological, so the last run of that length is the most recent one.
  const [end, days] = [...all].reverse().find(([, n]) => n === longest)!;
  return { days, end, current: end >= today - 1 };
}

/**
 * One stamp per brewer, most used first. As Kotlin PassportStats.stamps: grouped by the brewer
 * itself, not by the words stored with the brew (a 1.x brew says "Hario V60", a later one "V60 02").
 */
export function stamps(logs: readonly BrewRow[]): Stamp[] {
  const groups = new Map<string, Stamp>();
  for (const l of logs) {
    if (!l.brewer.trim() && l.brewerId == null) continue;
    const brewer = Brewers.byId(l.brewerId) ?? Brewers.resolveOrNull(l.brewer);
    const key = brewer?.id ?? l.brewer;
    const seen = groups.get(key);
    groups.set(key, seen ? { ...seen, count: seen.count + 1 } : { brewer, label: l.brewer, count: 1 });
  }
  return [...groups.values()].sort((a, b) => b.count - a.count);
}

export function passport(logs: readonly BrewRow[], today: number = epochDayOf(Date.now())): Passport {
  const scored = logs.filter((l) => l.overall > 0);
  const [streak, bestStreak] = streaks(logs, today);
  const firstAt = logs.length ? Math.min(...logs.map((l) => l.startedAt)) : null;
  const spanDays = firstAt != null ? Math.max(1, today - epochDayOf(firstAt) + 1) : 0;
  return {
    brews: logs.length,
    waterG: logs.reduce((s, l) => s + l.waterG, 0),
    coffeeG: logs.reduce((s, l) => s + l.doseG, 0),
    streak,
    bestStreak,
    avg: averageScore(scored),
    best: scored.length ? Math.max(...scored.map((l) => l.overall)) : null,
    greatCups: logs.filter((l) => l.overall >= 8).length,
    firstAt,
    spanDays,
    stamps: stamps(logs),
    recipesUsed: new Set(logs.map((l) => l.recipeId).filter((id) => id.trim() !== "")).size,
    coffees: new Set(logs.map((l) => (l.beanId != null ? String(l.beanId) : l.beanLabel)).filter((k) => k.trim() !== "")).size,
  };
}

/** The last `n` scored brews, oldest first, for the score chart. */
export function scoreSeries(logs: readonly BrewRow[], n = 10): ScorePoint[] {
  return logs
    .filter((l) => l.overall > 0)
    .sort((a, b) => a.startedAt - b.startedAt)
    .slice(-n)
    .map((l) => ({ id: l.id ?? 0, uid: l.uid, at: l.startedAt, score: l.overall }));
}

export const lastFiveAvg = (logs: readonly BrewRow[]) => averageScore(newestScored(logs).slice(0, 5));
export const prevFiveAvg = (logs: readonly BrewRow[]) => averageScore(newestScored(logs).slice(5, 10));

function rankOrder(a: Ranked, b: Ranked): number {
  return b.count - a.count || (b.avg ?? -1) - (a.avg ?? -1);
}

export function rankedRecipes(logs: readonly BrewRow[], recipes: readonly Recipe[], max = 3): Ranked[] {
  const byId = new Map(recipes.map((r) => [r.id, r]));
  const groups = new Map<string, BrewRow[]>();
  for (const l of logs) {
    const g = groups.get(l.recipeId);
    if (g) g.push(l);
    else groups.set(l.recipeId, [l]);
  }
  return [...groups.entries()]
    .map(([id, group]): Ranked => {
      const recipe = byId.get(id);
      const sample = group[0]!;
      const author = recipe?.author ?? sample.recipeAuthor;
      return { name: recipe?.name ?? (sample.recipeName.trim() || "Brew"), sub: author.trim() ? author : null, count: group.length, avg: averageScore(group.filter((l) => l.overall > 0)) };
    })
    .sort(rankOrder)
    .slice(0, max);
}

interface BeanGroup {
  ranked: Ranked;
  rated: number;
}

function beanGroups(logs: readonly BrewRow[], beans: readonly BeanRow[]): BeanGroup[] {
  const byId = new Map(beans.map((b) => [b.id, b]));
  const groups = new Map<string, BrewRow[]>();
  for (const l of logs) {
    const key = l.beanId != null ? `id:${l.beanId}` : l.beanLabel.trim() ? `label:${l.beanLabel}` : null;
    if (!key) continue;
    const g = groups.get(key);
    if (g) g.push(l);
    else groups.set(key, [l]);
  }
  return [...groups.values()].map((group) => {
    const first = group[0]!;
    const bean = first.beanId != null ? byId.get(first.beanId) : undefined;
    // Labels are stored as "Roaster — Name"; split them when the bean row is gone.
    const label = first.beanLabel;
    const i = label.indexOf(" — ");
    const name = bean?.name ?? ((i >= 0 ? label.slice(i + 3) : label).trim() || "Coffee");
    const sub = bean?.roaster ?? (i >= 0 ? label.slice(0, i).trim() : "");
    const rated = group.filter((l) => l.overall > 0);
    return { ranked: { name, sub: sub.trim() ? sub : null, count: group.length, avg: averageScore(rated) }, rated: rated.length };
  });
}

export function rankedBeans(logs: readonly BrewRow[], beans: readonly BeanRow[], max = 3): Ranked[] {
  return beanGroups(logs, beans)
    .map((g) => g.ranked)
    .sort(rankOrder)
    .slice(0, max);
}

function mostCommon<K>(items: readonly K[]): K | undefined {
  const counts = new Map<K, number>();
  for (const k of items) counts.set(k, (counts.get(k) ?? 0) + 1);
  // Insertion order, strictly greater: the first key with the top count wins, as Kotlin's maxByOrNull.
  let best: K | undefined;
  let bestN = 0;
  for (const [k, n] of counts) {
    if (n > bestN) {
      best = k;
      bestN = n;
    }
  }
  return best;
}

/**
 * The most-used click grinder, on its most-brewed recipe only. Null until that recipe has two
 * brews with a real click count, because one point is not a journey.
 */
export function grindSeries(logs: readonly BrewRow[], grinders: readonly GrinderRow[]): GrindSeries | null {
  // Kotlin groups in log order (newest first); keep that so ties resolve the same way.
  const clickLogs = logs.filter((l) => (l.grindClicks ?? 0) > 0 && l.grinderId != null);
  const grinderId = mostCommon(clickLogs.map((l) => l.grinderId!));
  if (grinderId == null) return null;
  const onGrinder = clickLogs.filter((l) => l.grinderId === grinderId);
  const mainRecipeId = mostCommon(onGrinder.map((l) => l.recipeId));
  if (mainRecipeId == null) return null;
  const main = onGrinder.filter((l) => l.recipeId === mainRecipeId).sort((a, b) => a.startedAt - b.startedAt);
  if (main.length < 2) return null;
  const points = main.map((l) => ({ id: l.id ?? 0, at: l.startedAt, clicks: l.grindClicks ?? 0, score: l.overall }));
  const good = points.filter((p) => p.score >= 7);
  const grinder = grinders.find((g) => g.id === grinderId) ?? null;
  const latestLog = main[main.length - 1]!;
  return {
    grinderLabel: grinder?.model.trim() ? grinder.model : latestLog.grinderLabel.trim() || "Grinder",
    points,
    mainRecipeId,
    bestZone: good.length ? { first: Math.min(...good.map((p) => p.clicks)), last: Math.max(...good.map((p) => p.clicks)) } : null,
    bestZoneAvg: good.length ? good.reduce((s, p) => s + p.score, 0) / good.length : null,
    first: points[0]!.clicks,
    latest: points[points.length - 1]!.clicks,
    lowerIsFiner: grinder?.lowerIsFiner ?? true,
    recipeName: latestLog.recipeName.trim() || "This recipe",
    otherBrews: onGrinder.length - main.length,
    clicksPerTurn: grinder?.clicksPerRotation ?? 0,
    dialFormat: grinder ? encodeDialFormat(scaleFor(grinder).dial) : "",
  };
}

/**
 * Records with context, not averages: the best cup and what made it, the longest run of brewing
 * days, and the coffee with the best average over at least two rated brews.
 */
export function personalBests(logs: readonly BrewRow[], beans: readonly BeanRow[], today: number = epochDayOf(Date.now())): PersonalBests {
  let best: BrewRow | null = null;
  for (const l of logs) {
    if (l.overall <= 0) continue;
    if (!best || l.overall > best.overall || (l.overall === best.overall && l.startedAt > best.startedAt)) best = l;
  }
  let bestCoffee: Ranked | null = null;
  for (const g of beanGroups(logs, beans)) {
    if (g.rated < 2) continue;
    const r = g.ranked;
    if (!bestCoffee || (r.avg ?? 0) > (bestCoffee.avg ?? 0) || ((r.avg ?? 0) === (bestCoffee.avg ?? 0) && r.count > bestCoffee.count)) bestCoffee = r;
  }
  return {
    bestCup: best
      ? {
          id: best.id ?? 0,
          uid: best.uid,
          at: best.startedAt,
          score: best.overall,
          recipeName: best.recipeName.trim() || "Brew",
          clicks: best.grindClicks != null && best.grindClicks > 0 ? best.grindClicks : null,
          // A logged 0 on a click grinder is a slip, not a setting worth repeating.
          grindSetting: best.grindClicks === 0 ? "" : best.grindSetting,
          tempC: best.tempC,
        }
      : null,
    longestStreak: longestStreak(logs, today),
    bestCoffee,
  };
}

export function hasRecords(b: PersonalBests): boolean {
  return b.bestCup != null || (b.longestStreak?.days ?? 0) >= 2 || b.bestCoffee != null;
}
