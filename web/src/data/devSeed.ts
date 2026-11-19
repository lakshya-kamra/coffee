/**
 * Dev-only data for performance work (web-spec §8.6), imported only under import.meta.env.DEV.
 * Mirrors Android's DebugTools: a 10-click K-Ultra row exactly as 1.x stored it (the fixture
 * that proves 1.x behaviour survives) and a catalog-linked K-Ultra at 100 clicks per turn.
 */
import { catalog } from "@/catalog/catalog";
import { LegacyIds } from "@/domain/core/legacyIds";
import { epochDayOf } from "@/domain/core/format";
import { db } from "./db";
import { repo } from "./repo";
import { newBean, newBrew, newGrinder, type BrewRow } from "./schema";

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Seeds `count` brews over the last ~18 months. Deterministic for a given count. */
export async function seedBrews(count = 1000): Promise<void> {
  await catalog.load(["brewers", "recipes", "grinders"]);
  const rnd = mulberry32(count);
  const now = Date.now();
  const beanIds = await db.transaction("rw", db.beans, async () => {
    const beans = [
      newBean({ name: "Red Brick", roaster: "Square Mile", origin: "Blend", processId: "washed", roastLevelId: "medium-light", roastedOnEpochDay: epochDayOf(now) - 12 }),
      newBean({ name: "Kayon Mountain", roaster: "Tim Wendelboe", origin: "Ethiopia", originId: "ethiopia", processId: "natural", roastLevelId: "light", roastedOnEpochDay: epochDayOf(now) - 20 }),
    ];
    return Promise.all(beans.map((b) => db.beans.add(b)));
  });
  const legacyId = await db.grinders.add(
    newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 10, micronsPerClick: 20, minSetting: 0, maxSetting: 200, isDefault: true, notes: "1.x fixture: 10 clicks per turn, as 1.x stored it." }),
  );
  await db.grinders.add(
    newGrinder({ brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, minSetting: 0, maxSetting: 200, catalogId: "1zpresso-k-ultra" }),
  );
  const recipes = catalog.recipes.length ? catalog.recipes : null;
  const rows: BrewRow[] = [];
  for (let i = 0; i < count; i++) {
    const startedAt = now - Math.floor(rnd() * 540) * 86_400_000 - Math.floor(rnd() * 12 * 3_600_000);
    const recipe = recipes ? recipes[Math.floor(rnd() * recipes.length)]! : null;
    const brewerId = recipe?.brewer.id ?? LegacyIds.BREWER["Hario V60"]!;
    rows.push(
      newBrew(
        {
          startedAt,
          recipeId: recipe?.id ?? "",
          recipeName: recipe?.name ?? "Seeded brew",
          recipeAuthor: recipe?.author ?? "",
          brewer: recipe?.brewer.label ?? "Hario V60",
          brewerId,
          beanId: beanIds[i % beanIds.length] ?? null,
          beanLabel: i % 2 ? "Tim Wendelboe — Kayon Mountain" : "Square Mile — Red Brick",
          grinderId: legacyId,
          grinderLabel: "1Zpresso K-Ultra",
          grindClicks: 60 + Math.floor(rnd() * 30),
          doseG: recipe?.doseG ?? 15,
          waterG: recipe?.waterG ?? 250,
          tempC: recipe?.tempC ?? 94,
          totalTimeSec: 150 + Math.floor(rnd() * 120),
          overall: 1 + Math.floor(rnd() * 10),
        },
        startedAt,
      ),
    );
  }
  await db.brew_logs.bulkAdd(rows);
}

export async function wipeDev(): Promise<void> {
  await repo.wipeAll();
}
