/**
 * What each onboarding step writes (A§7.3): every Next persists its own step, so leaving half
 * way keeps what you chose. Pure data calls (settings + repo), tested against fake-indexeddb.
 */
import { catalog } from "@/catalog/catalog";
import { repo } from "@/data/repo";
import { newGrinder, type GrinderRow } from "@/data/schema";
import { settings, type Settings } from "@/data/settings";
import { fieldsFromCatalog, findExistingGrinder } from "@/features/gear";

export function saveMeasure(p: Pick<Settings, "tempUnit" | "weightUnit" | "homeArea">): Promise<Settings> {
  return settings.update(p);
}

export type GrinderChoice =
  | { kind: "catalog"; id: string }
  | { kind: "custom"; brand: string; model: string; settingStyle: GrinderRow["settingStyle"]; clicksPerRotation: number };

/**
 * Saves the chosen grinder as the default. On "Run setup again", a grinder you already have
 * (same catalog entry, or same brand + model) becomes the default instead of a duplicate row.
 * Returns the row id, or null when the catalog entry is unknown.
 */
export async function saveGrinder(choice: GrinderChoice, existing: readonly GrinderRow[]): Promise<number | null> {
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
  await settings.set("defaultGrinderId", id);
  return id;
}

export function saveBrewers(ids: readonly string[]): Promise<Settings> {
  return settings.set("ownedBrewerIds", [...ids]);
}

export function saveCoffee(p: { favouriteRoasterIds: readonly string[]; defaultWaterId: string }): Promise<Settings> {
  return settings.update({ favouriteRoasterIds: [...p.favouriteRoasterIds], defaultWaterId: p.defaultWaterId });
}

/** "Start brewing" and "Skip": onboarding never shows again by itself, and neither does Today's card. */
export function finish(): Promise<Settings> {
  return settings.update({ onboardingDone: true, onboardingAuto: false });
}

/** Brewer chips: the nine 1.x brewers first (most people own one), then the rest A–Z (A§7.3). */
export const COMMON_BREWER_IDS: readonly string[] = [
  "hario-v60-02",
  "chemex",
  "kalita-wave-185",
  "hario-switch",
  "aeropress",
  "french-press",
  "moka-pot",
  "espresso-machine",
  "cold-brew-jar",
];
