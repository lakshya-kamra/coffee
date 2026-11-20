/**
 * Port of HomeViewModel.kt's HomeUiState: everything Today derives from the store. Pure; `now`
 * is passed in so tests pin the clock. Logs are newest first. "Scored" means overall > 0;
 * unrated brews never move an average.
 */
import type { BeanRow, BrewRow } from "@/data/schema";
import type { Settings } from "@/data/settings";
import { brewerFamily, type Brewer } from "@/domain/core/brewer";
import { epochDayOf, oneDp } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import type { Status } from "@/domain/core/status";

/** Android reads the newest 40 logs for Today. */
export const RECENT_WINDOW = 40;

export interface Headline {
  status: Status;
  lead: string;
  detail: string | null;
}

const scored = (logs: readonly BrewRow[]) => logs.filter((l) => l.overall > 0);
const avg = (logs: readonly BrewRow[]): number | null => (logs.length ? logs.reduce((n, l) => n + l.overall, 0) / logs.length : null);

export function brewsToday(logs: readonly BrewRow[], now: number): number {
  const today = epochDayOf(now);
  return logs.filter((l) => epochDayOf(l.startedAt) === today).length;
}

/** Brews in the rolling seven days ending today, so Monday never resets it to zero. */
export function brewsThisWeek(logs: readonly BrewRow[], now: number): number {
  const today = epochDayOf(now);
  return logs.filter((l) => {
    const d = epochDayOf(l.startedAt);
    return d >= today - 6 && d <= today;
  }).length;
}

/** Consecutive days ending today (or yesterday) with at least one brew. */
export function streakDays(logs: readonly BrewRow[], now: number): number {
  if (logs.length === 0) return 0;
  const days = new Set(logs.map((l) => epochDayOf(l.startedAt)));
  let cursor = epochDayOf(now);
  if (!days.has(cursor)) {
    cursor -= 1;
    if (!days.has(cursor)) return 0;
  }
  let count = 0;
  while (days.has(cursor)) {
    count++;
    cursor--;
  }
  return count;
}

/** Rolling average of the last ten scored brews. */
export const averageScore = (logs: readonly BrewRow[]) => avg(scored(logs).slice(0, 10));
/** The five newest scored brews. */
export const lastFiveAvg = (logs: readonly BrewRow[]) => avg(scored(logs).slice(0, 5));
/** Scored brews six to ten, the window LAST 5 is compared against. */
export const prevFiveAvg = (logs: readonly BrewRow[]) => avg(scored(logs).slice(5, 10));

/** First match wins, so a dialled-in run outranks one weak cup. */
export function headline(logs: readonly BrewRow[]): Headline {
  if (logs.length === 0) return { status: "neutral", lead: "No brews yet", detail: "start with a recipe below" };
  const s = scored(logs);
  const lastThree = s.slice(0, 3);
  if (lastThree.length === 3) {
    const a = avg(lastThree) ?? 0;
    if (a >= 7.5) {
      const first = lastThree[0]!.overall;
      const detail = lastThree.every((l) => l.overall === first) ? `last 3 brews scored ${first}` : `last 3 avg ${oneDp(a)}`;
      return { status: "good", lead: "Dialled in", detail };
    }
  }
  const newest = s[0];
  if (newest && newest.overall >= 1 && newest.overall <= 5) return { status: "attention", lead: "Worth a tweak", detail: `last cup ${newest.overall}/10` };
  const av = averageScore(logs);
  return { status: "neutral", lead: "Brewing steady", detail: av != null ? `avg ${oneDp(av)}` : null };
}

export function playfulTitle(brewsTodayCount: number): string {
  switch (brewsTodayCount) {
    case 0: return "No coffee yet.";
    case 1: return "One down.";
    case 2: return "Two down.";
    default: return `${brewsTodayCount} today. Steady on.`;
  }
}

/** A sliver at zero, full at three. */
export function mugFill(brewsTodayCount: number): number {
  return Math.max(0.12, Math.min(brewsTodayCount, 3) / 3);
}

/**
 * The recipe the NEXT BREW pass offers: the last one brewed (settings, then the newest log),
 * else the first recipe for a brewer the person owns (A§7.8), else the first expert recipe.
 */
export function nextRecipe(recipes: readonly Recipe[], logs: readonly BrewRow[], settings: Pick<Settings, "lastRecipeId" | "ownedBrewerIds">, brewerById: (id: string) => Brewer | null): Recipe | null {
  const byId = (id: string) => recipes.find((r) => r.id === id) ?? null;
  const last = (settings.lastRecipeId ? byId(settings.lastRecipeId) : null) ?? (logs[0] ? byId(logs[0].recipeId) : null);
  if (last) return last;
  if (settings.ownedBrewerIds.length) {
    const families = new Set(settings.ownedBrewerIds.flatMap((id) => {
      const b = brewerById(id);
      return b ? [brewerFamily(b)] : [];
    }));
    const owned = recipes.find((r) => families.has(brewerFamily(r.brewer)));
    if (owned) return owned;
  }
  return recipes[0] ?? null;
}

export function lastLogFor(logs: readonly BrewRow[], recipeId: string): BrewRow | null {
  return logs.find((l) => l.recipeId === recipeId) ?? null;
}

/** Skips 0 and null so a corrupted "0 clicks" log is never offered as the grind to use. */
export function lastGrindFor(logs: readonly BrewRow[], recipeId: string): number | null {
  return logs.find((l) => l.recipeId === recipeId && (l.grindClicks ?? 0) > 0)?.grindClicks ?? null;
}

/**
 * Start from (v4 §3.1): recipes for the brewers you own first, then the rest, in library order,
 * without the one already offered as the next brew.
 */
export function startFromRecipes(recipes: readonly Recipe[], families: ReadonlySet<string>, nextId: string | null, count: number): Recipe[] {
  const rest = recipes.filter((r) => r.id !== nextId);
  const owned = rest.filter((r) => families.has(brewerFamily(r.brewer)));
  const other = rest.filter((r) => !families.has(brewerFamily(r.brewer)));
  return [...owned, ...other].slice(0, Math.max(0, count));
}

/**
 * The coffee on deck for "Your coffee": the default coffee, else the one in the newest brew that
 * named one, else the newest coffee you have. Archived coffees are never offered.
 */
export function beanOnDeck(beans: readonly BeanRow[], defaultBeanId: number, logs: readonly BrewRow[]): BeanRow | null {
  const live = beans.filter((b) => !b.isArchived);
  const byId = (id: number | null | undefined) => (id ? live.find((b) => b.id === id) ?? null : null);
  return byId(defaultBeanId) ?? byId(logs.find((l) => l.beanId != null)?.beanId) ?? [...live].sort((a, b) => b.updatedAt - a.updatedAt)[0] ?? null;
}

/** Whole days since roasting, from today; null when the roast date is not recorded. */
export function daysOffRoast(bean: Pick<BeanRow, "roastedOnEpochDay">, now: number): number | null {
  return bean.roastedOnEpochDay == null ? null : Math.max(0, epochDayOf(now) - bean.roastedOnEpochDay);
}
