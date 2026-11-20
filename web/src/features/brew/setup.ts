/**
 * The brew setup model, ported from BrewViewModel.kt: what the setup pane starts with, the grind
 * prefill, the click words written to the log, and the brew row finish() writes (A§2.5). Pure.
 *
 * v4 (§4.2): the grind is one of three kinds. "preground" is the coffee as bought (no grinder, no
 * setting), "blade" is a grinder whose setting is seconds, "dial" is every burr grinder.
 */
import { beanDisplay, grinderDisplay, newBrew, type BeanRow, type BrewRow, type GrindKind, type GrinderRow } from "@/data/schema";
import type { Settings } from "@/data/settings";
import type { Brewer } from "@/domain/core/brewer";
import { epochDayOf, grams, roundToInt } from "@/domain/core/format";
import { scaledToDose, type Recipe } from "@/domain/core/recipe";
import { grindLooksTyped, reading, scaleFor } from "@/domain/grind";
import type { BrewResult } from "./engine";

export interface Setup {
  doseG: number;
  /** null: the recipe's ratio sets the water. A number: the water was given (Brew again keeps it). */
  waterG: number | null;
  tempC: number;
  beanId: number | null;
  /** Kept while `preground` is on, so picking the grinder again needs no second choice. */
  grinderId: number | null;
  /** The coffee as bought (§4.2): no grinder and no grind setting. */
  preground: boolean;
  /** Total clicks from zero on click grinders; null = not set (never 0, which is burr contact). */
  grindClicks: number | null;
  /** Free text (or the dial number) on stepless and numbered grinders. */
  grindSetting: string;
  /** Blade grinders: seconds of grinding. */
  bladeSec: number | null;
}

/** A blade grinder starts at 12 s: the §4.2 example, and short enough to stay under a filter grind. */
export const DEFAULT_BLADE_SEC = 12;
export const BLADE_MIN_SEC = 1;
export const BLADE_MAX_SEC = 60;
/** What a pre-ground brew stores as its grind (§4.2). */
export const AS_BOUGHT = "As bought";

export const usesClicks = (g: GrinderRow | null | undefined): boolean => g?.settingStyle === "clicks";
/** A grinder timed in seconds (catalog `blade-grinder`, settingUnit "s"). */
export const isBlade = (g: GrinderRow | null | undefined): boolean => g?.settingUnit === "s";

export function grindKindOf(s: Pick<Setup, "preground">, g: GrinderRow | null | undefined): GrindKind {
  if (s.preground) return "preground";
  if (!g) return "";
  return isBlade(g) ? "blade" : "dial";
}

/**
 * Pre-ground is chosen for you when the coffee was bought ground, or when you buy ground and own
 * no grinder (§4.2).
 */
export function autoPreground(bean: Pick<BeanRow, "form"> | null | undefined, settings: Pick<Settings, "buysGround">, grinderCount: number): boolean {
  return bean?.form === "ground" || (settings.buysGround && grinderCount === 0);
}

/** A setup read back from kv activeBrew, which may predate v4. */
export function withSetupDefaults(raw: Partial<Setup> & Pick<Setup, "doseG" | "tempC">): Setup {
  return {
    waterG: null,
    beanId: null,
    grinderId: null,
    preground: false,
    grindClicks: null,
    grindSetting: "",
    bladeSec: null,
    ...raw,
  };
}

/**
 * The recipe this setup brews: scaled to the dose, then, when the water was given (Brew again),
 * every water target scaled to it. Timings never change.
 */
export function setupRecipe(base: Recipe, s: Pick<Setup, "doseG" | "waterG">): Recipe {
  const r = scaledToDose(base, s.doseG);
  if (s.waterG == null || r.waterG <= 0 || Math.abs(s.waterG - r.waterG) < 1) return r;
  const k = s.waterG / r.waterG;
  return {
    ...r,
    waterG: roundToInt(s.waterG),
    steps: r.steps.map((st) => ({ ...st, targetWaterG: st.targetWaterG == null ? null : roundToInt(st.targetWaterG * k) })),
  };
}

/** A new dose keeps a given water's ratio (Brew again), else the recipe's. */
export function withDose(s: Setup, doseG: number): Setup {
  const dose = Math.max(1, Math.round(doseG * 10) / 10);
  const water = s.waterG != null && s.doseG > 0 ? roundToInt((s.waterG * dose) / s.doseG) : null;
  return { ...s, doseG: dose, waterG: water };
}

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

/** The newest blade time on `g` for this recipe, then for this brewer. `brews` newest first. */
export function lastBladeSec(g: GrinderRow, r: Recipe, brews: readonly BrewRow[]): number | null {
  const timed = brews.filter((l) => l.grinderId === g.id && l.grindKind === "blade" && (l.grindValue ?? 0) > 0);
  const hit = timed.find((l) => l.recipeId === r.id) ?? timed.find((l) => sameBrewer(l, r.brewer));
  return hit?.grindValue ?? null;
}

/** BrewViewModel.prefillGrindSetting: only fills a value that is still empty. */
export function prefillGrind(s: Setup, r: Recipe, grinders: readonly GrinderRow[], brews: readonly BrewRow[]): Setup {
  const g = grinders.find((x) => x.id === s.grinderId);
  if (!g) return s;
  if (isBlade(g)) return s.bladeSec != null ? s : { ...s, bladeSec: lastBladeSec(g, r, brews) ?? DEFAULT_BLADE_SEC };
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
  const bean = beans.find((b) => b.id === beanId) ?? null;
  const base: Setup = {
    doseG: r.doseG,
    waterG: null,
    tempC: r.tempC,
    beanId,
    grinderId,
    preground: autoPreground(bean, settings, grinders.length),
    grindClicks: null,
    grindSetting: "",
    bladeSec: null,
  };
  return prefillGrind(base, r, grinders, brews);
}

/**
 * A different coffee was picked: a ground bag switches to pre-ground; a whole-bean bag switches
 * back to the grinder when pre-ground was only on because of the coffee.
 */
export function withBean(s: Setup, bean: BeanRow | null, settings: Pick<Settings, "buysGround">, grinders: readonly GrinderRow[]): Setup {
  const auto = autoPreground(bean, settings, grinders.length);
  if (auto) return { ...s, beanId: bean?.id ?? null, preground: true };
  const hasGrinder = grinders.some((g) => g.id === s.grinderId);
  return { ...s, beanId: bean?.id ?? null, preground: s.preground && !hasGrinder };
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

/** "12 s": how a blade time is written to the log and shown. */
export const bladeLabel = (sec: number): string => `${sec} s`;

/** Click grinders describe themselves; dials keep the typed setting. */
export function grindLabel(s: Setup, g: GrinderRow | null | undefined): string {
  if (s.preground) return AS_BOUGHT;
  if (g && isBlade(g)) return s.bladeSec != null ? bladeLabel(s.bladeSec) : "";
  if (g && usesClicks(g)) return s.grindClicks != null ? describeClicks(g, s.grindClicks) : "";
  return s.grindSetting;
}

/** The row finish() writes (A§2.5): brewerId, waterId and grindValue included. `recipe` is already scaled to the setup. */
export function buildBrewRow(p: {
  recipe: Recipe;
  setup: Setup;
  bean: BeanRow | null;
  grinder: GrinderRow | null;
  settings: Settings;
  result: BrewResult;
  /** Espresso: what came out (§4.9). */
  beverageG?: number | null;
  now?: number;
}): BrewRow {
  const now = p.now ?? Date.now();
  const { recipe: r, setup: s, bean } = p;
  // Pre-ground brews record no grinder at all: the grind is the bag's, not the grinder's.
  const g = s.preground ? null : p.grinder;
  const kind = grindKindOf(s, g);
  const clicks = kind === "dial" && usesClicks(g);
  const typed = s.grindSetting.trim().replace(",", ".");
  const value = kind === "blade" ? s.bladeSec : kind === "dial" && !clicks && /^-?\d+(\.\d+)?$/.test(typed) ? Number(typed) : null;
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
      grindKind: kind,
      waterId: p.settings.defaultWaterId.trim() || null,
      doseG: s.doseG,
      waterG: r.waterG,
      tempC: s.tempC,
      totalTimeSec: p.result.totalTimeSec,
      drawdownSec: p.result.drawdownSec,
      beverageG: p.beverageG ?? null,
    },
    now,
  );
}
