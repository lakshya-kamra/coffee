/**
 * What each onboarding step writes (A§7.3, v4 §4.1): every Next persists its own step, so leaving
 * half way keeps what you chose. Pure data calls (settings + repo), tested against fake-indexeddb.
 */
import { catalog } from "@/catalog/catalog";
import { repo } from "@/data/repo";
import { newGrinder, type GrinderRow } from "@/data/schema";
import { settings, type Experience, type Settings } from "@/data/settings";
import { fieldsFromCatalog, findExistingGrinder } from "@/features/gear/mapping";

/** The steps, in order (v4 §4.1: "you" is new, "done" became "Your first brew"). */
export const STEPS = ["you", "measure", "grinder", "brewers", "coffee", "done"] as const;
export type StepId = (typeof STEPS)[number];

// ---------------------------------------------------------------- 1. How do you make coffee now?

export type ExperienceChoice = Exclude<Experience, "">;

export const EXPERIENCE_CHOICES: readonly { id: ExperienceChoice; title: string; body: string }[] = [
  { id: "new", title: "I'm new to this", body: "Keep it simple. Explain the words. No scale needed." },
  { id: "recipes", title: "I follow recipes", body: "Show me good recipes and time each step." },
  { id: "tinker", title: "I tinker", body: "Give me every number: grind, flow, TDS." },
];

/**
 * What an answer pre-sets (v4 §4.1, D8). Each stays editable on its own in Gear › Settings.
 * `hasScale` is only the measure step's preselected answer for someone new; the step saves it.
 */
export function presetsFor(e: ExperienceChoice): { reviewMode: Settings["reviewMode"]; soundCues?: boolean; hasScale?: boolean } {
  switch (e) {
    case "new":
      return { reviewMode: "quick", soundCues: true, hasScale: false };
    case "recipes":
      return { reviewMode: "quick" };
    case "tinker":
      return { reviewMode: "detailed" };
  }
}

export function saveYou(e: ExperienceChoice): Promise<Settings> {
  const { hasScale: _scaleIsAskedNext, ...p } = presetsFor(e);
  return settings.update({ experience: e, ...p });
}

// ---------------------------------------------------------------- 2. How you measure

export function saveMeasure(p: Pick<Settings, "tempUnit" | "weightUnit" | "homeArea"> & { hasScale?: boolean }): Promise<Settings> {
  return settings.update(p);
}

// ---------------------------------------------------------------- 3. Your grinder

export const BLADE_GRINDER_ID = "blade-grinder";

export type GrinderChoice =
  | { kind: "ground" }
  | { kind: "catalog"; id: string }
  | { kind: "custom"; brand: string; model: string; settingStyle: GrinderRow["settingStyle"]; clicksPerRotation: number };

/**
 * "I buy ground coffee" sets buysGround and saves no grinder (v4 §4.2). Any grinder (the blade
 * grinder is the catalog's "blade-grinder", timed in seconds) is saved as the default and turns
 * buysGround off. On "Run setup again", a grinder you already have (same catalog entry, or same
 * brand + model) becomes the default instead of a duplicate row. Returns the row id, or null.
 */
export async function saveGrinder(choice: GrinderChoice, existing: readonly GrinderRow[]): Promise<number | null> {
  if (choice.kind === "ground") {
    await settings.set("buysGround", true);
    return null;
  }
  let row: GrinderRow;
  if (choice.kind === "catalog") {
    const c = catalog.grinder(choice.id);
    if (!c) return null;
    const f = fieldsFromCatalog(c);
    const have = findExistingGrinder(existing, { catalogId: c.id, brand: f.brand, model: f.model });
    row = have ?? newGrinder({ ...f, isDefault: true });
  } else {
    const have = findExistingGrinder(existing, { catalogId: null, brand: choice.brand, model: choice.model });
    row =
      have ??
      newGrinder({
        brand: choice.brand.trim(),
        model: choice.model.trim(),
        settingStyle: choice.settingStyle,
        clicksPerRotation: choice.settingStyle === "clicks" ? choice.clicksPerRotation : 0,
        isDefault: true,
      });
  }
  const id = row.id ?? (await repo.upsertGrinder({ ...row, isDefault: true }));
  await repo.setDefaultGrinder(id);
  await settings.update({ defaultGrinderId: id, buysGround: false });
  return id;
}

// ---------------------------------------------------------------- 4. Brewers, 5. Coffee

export function saveBrewers(ids: readonly string[]): Promise<Settings> {
  return settings.set("ownedBrewerIds", [...ids]);
}

export function saveCoffee(p: { favouriteRoasterIds: readonly string[]; defaultWaterId: string }): Promise<Settings> {
  return settings.update({ favouriteRoasterIds: [...p.favouriteRoasterIds], defaultWaterId: p.defaultWaterId });
}

/** "Start my first brew", "Go to Today" and "Skip for now": onboarding never shows again by itself. */
export function finish(): Promise<Settings> {
  return settings.update({ onboardingDone: true, onboardingAuto: false });
}

/** Brewers step "Most common", in this order (v4 §4.1); the rest follow A-Z by category. */
export const COMMON_BREWER_IDS: readonly string[] = [
  "drip-machine",
  "french-press",
  "hario-v60-02",
  "aeropress",
  "moka-pot",
  "espresso-machine",
  "drip-bag",
  "south-indian-filter",
  "phin",
  "cold-brew-jar",
];

/** Water: four plain answers first (v4 §4.1); "Not sure" stores no water. */
export const PLAIN_WATERS: readonly { key: string; label: string; waterId: string }[] = [
  { key: "tap", label: "Tap water", waterId: "tap-unfiltered" },
  { key: "filtered", label: "Filtered tap water", waterId: "tap-filtered" },
  { key: "bottled", label: "Bottled spring water", waterId: "bottled-spring" },
  { key: "unsure", label: "Not sure", waterId: "" },
];
