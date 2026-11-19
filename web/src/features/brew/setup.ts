/**
 * The brew setup model, ported from BrewViewModel.kt: what the setup pane starts with, the grind
 * prefill, the click words written to the log, and the brew row finish() writes (A§2.5). Pure.
 */
import { beanDisplay, grinderDisplay, newBrew, type BeanRow, type BrewRow, type GrinderRow } from "@/data/schema";
import type { Settings } from "@/data/settings";
import type { Brewer } from "@/domain/core/brewer";
import { epochDayOf, grams } from "@/domain/core/format";
import type { Recipe } from "@/domain/core/recipe";
import { grindLooksTyped, reading, scaleFor } from "@/domain/grind";
import type { BrewResult } from "./engine";

export interface Setup {
  doseG: number;
  tempC: number;
  beanId: number | null;
  grinderId: number | null;
  /** Total clicks from zero on click grinders; null = not set (never 0, which is burr contact). */
  grindClicks: number | null;
  /** Free text (or the dial number) on stepless and numbered grinders. */
  grindSetting: string;
}

export const usesClicks = (g: GrinderRow | null | undefined): boolean => g?.settingStyle === "clicks";

/** The grinder's own go-to setting for this kind of brew, if the user set one. */
export function grindPreference(r: Recipe, g: GrinderRow): number | null {
  return r.brewer.method === "espresso" ? g.defaultEspressoSetting : g.defaultFilterSetting;
}

/** The lowest click count a stepper may reach. 0 is burr contact, which never brews. */
export function minClicks(g: GrinderRow | null | undefined): number {
  return Math.max(1, Math.round(g?.minSetting ?? 1));
}

const sameBrewer = (log: BrewRow, b: Brewer) => log.brewerId === b.id || log.brewer === b.label;

/**
 * The newest believable click count on `g`: this recipe first, then another recipe on the same
 * brewer. Never a different brewer's grind (an AeroPress 55 is wrong for a V60), never 0, and
 * never a filter reading the data checks distrust as a dial number typed in place of clicks.
 * `brews` newest first.
 */
export function lastTrustedClicks(g: GrinderRow, r: Recipe, brews: readonly BrewRow[]): number | null {
  const scale = scaleFor(g);
  const method = r.brewer.method;
  const trusted = brews.filter((log) => {
    const c = log.grindClicks ?? 0;
    return log.grinderId === g.id && c > 0 && !grindLooksTyped(c, scale, method);
  });
  const hit = trusted.find((l) => l.recipeId === r.id) ?? trusted.find((l) => sameBrewer(l, r.brewer));
  return hit?.grindClicks ?? null;
}

/** BrewViewModel.prefillGrindSetting: only fills a value that is still empty. */
export function prefillGrind(s: Setup, r: Recipe, grinders: readonly GrinderRow[], brews: readonly BrewRow[]): Setup {
  const g = grinders.find((x) => x.id === s.grinderId);
  if (!g) return s;
  const pref = grindPreference(r, g);
  if (usesClicks(g) && s.grindClicks == null) {
    const prefClicks = pref != null && Math.round(pref) > 0 ? Math.round(pref) : null;
    return { ...s, grindClicks: lastTrustedClicks(g, r, brews) ?? prefClicks };
  }
  if (!usesClicks(g) && !s.grindSetting.trim() && pref != null) return { ...s, grindSetting: grams(pref) };
  return s;
}

export function initialSetup(r: Recipe, beans: readonly BeanRow[], grinders: readonly GrinderRow[], settings: Settings, brews: readonly BrewRow[]): Setup {
  const beanId = beans.some((b) => b.id === settings.defaultBeanId) ? settings.defaultBeanId : (beans[0]?.id ?? null);
  const grinderId = grinders.some((g) => g.id === settings.defaultGrinderId)
    ? settings.defaultGrinderId
    : (grinders.find((g) => g.isDefault)?.id ?? grinders[0]?.id ?? null);
  const base: Setup = { doseG: r.doseG, tempC: r.tempC, beanId, grinderId, grindClicks: null, grindSetting: "" };
  return prefillGrind(base, r, grinders, brews);
}

/**
 * The text snapshot stored with a brew (GrinderEntity.describeClicks): "75 clicks (7 turns + 5)"
 * on a 1.x row, "75 clicks (dial 7.5)" on a numbered dial, "32 clicks" when the dial adds nothing.
 * Kotlin writes "turns" for every count; kept so both apps store the same words.
 */
export function describeClicks(g: GrinderRow, clicks: number): string {
  const base = `${clicks} clicks`;
  const s = scaleFor(g);
  let words: string | null = null;
  switch (s.dial.kind) {
    case "turns":
    case "turns-dot":
      words = `${Math.floor(clicks / s.dial.clicksPerTurn)} turns + ${clicks % s.dial.clicksPerTurn}`;
      break;
    case "number-tick": {
      const r = reading(s, clicks);
      words = r != null ? `dial ${r}` : null;
      break;
    }
    case "plain":
      break;
  }
  return words == null ? base : `${base} (${words})`;
}

/** Click grinders describe themselves; dials keep the typed setting. */
export function grindLabel(s: Setup, g: GrinderRow | null | undefined): string {
  if (g && usesClicks(g)) return s.grindClicks != null ? describeClicks(g, s.grindClicks) : "";
  return s.grindSetting;
}

/** The row finish() writes (A§2.5): brewerId, waterId and grindValue included. `recipe` is already scaled to the dose. */
export function buildBrewRow(p: {
  recipe: Recipe;
  setup: Setup;
  bean: BeanRow | null;
  grinder: GrinderRow | null;
  settings: Settings;
  result: BrewResult;
  now?: number;
}): BrewRow {
  const now = p.now ?? Date.now();
  const { recipe: r, setup: s, bean, grinder: g } = p;
  const clicks = usesClicks(g);
  const typed = s.grindSetting.trim().replace(",", ".");
  const value = g && !clicks && /^-?\d+(\.\d+)?$/.test(typed) ? Number(typed) : null;
  return newBrew(
    {
      startedAt: now,
      recipeId: r.id,
      recipeName: r.name,
      recipeAuthor: r.author,
      brewer: r.brewer.label,
      brewerId: r.brewer.id,
      beanId: bean?.id ?? null,
      beanLabel: bean ? beanDisplay(bean) : "",
      daysOffRoast: bean?.roastedOnEpochDay != null ? epochDayOf(now) - bean.roastedOnEpochDay : null,
      grinderId: g?.id ?? null,
      grinderLabel: g ? grinderDisplay(g) : "",
      grindSetting: grindLabel(s, g),
      grindClicks: clicks ? s.grindClicks : null,
      grindValue: value,
      waterId: p.settings.defaultWaterId.trim() || null,
      doseG: s.doseG,
      waterG: r.waterG,
      tempC: s.tempC,
      totalTimeSec: p.result.totalTimeSec,
      drawdownSec: p.result.drawdownSec,
      beverageG: null,
    },
    now,
  );
}
