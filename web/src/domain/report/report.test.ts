// A snapshot of one share report (A§8.7 additions) and the trend table.
import { beforeAll, describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { DEFAULT_TASTE } from "@/domain/core/evaluation";
import { newBean, newBrew, newGrinder } from "@/data/schema";
import { brewMarkdown, trendMarkdown, whenLabel } from "./brewReport";

beforeAll(async () => {
  await catalog.load(["brewers", "grinders", "beans", "water", "roasters", "origins", "recipes"]);
});

describe("brew report", () => {
  it("matches the snapshot (date line aside)", () => {
    const recipe = catalog.recipes.find((r) => r.brewer.method === "pourover")!;
    const bean = newBean({ id: 1, name: "Kayon Mountain", roaster: "Tim Wendelboe", origin: "Ethiopia", region: "Guji", processId: "natural", roastLevelId: "light", tastingNotes: "Peach" });
    const grinder = newGrinder({ id: 2, brand: "1Zpresso", model: "K-Ultra", settingStyle: "clicks", clicksPerRotation: 100, micronsPerClick: 20, catalogId: "1zpresso-k-ultra", maxSetting: 200 });
    const log = newBrew({
      startedAt: Date.UTC(2026, 10, 17, 8, 14), recipeId: recipe.id, recipeName: recipe.name, recipeAuthor: recipe.author, brewer: recipe.brewer.label, brewerId: recipe.brewer.id,
      beanId: 1, beanLabel: "Tim Wendelboe — Kayon Mountain", daysOffRoast: 12, grinderId: 2, grinderLabel: "1Zpresso K-Ultra", grindClicks: 75, doseG: 15, waterG: 250, tempC: 94,
      totalTimeSec: 205, drawdownSec: 40, bedShape: "flat", taste: { ...DEFAULT_TASTE, acidity: 7 }, overall: 7, descriptors: ["Citrus"], faults: ["Thin"], notes: "Bright", nextTime: "Finer",
    });
    const md = brewMarkdown(log, recipe, bean, grinder, [], { photoCount: 2 });
    expect(md.split("\n")[0]).toBe(`# Brew log — ${whenLabel(log.startedAt)}`);
    expect(md).toContain("- Dial: 100 clicks per full turn; 75 reads 7.5 on the dial");
    expect(md).toContain("- Direction: FEWER clicks is finer");
    expect(md).toContain("- Usual pour-over starting range on this grinder: 75–90");
    expect(md).toContain("- 2 photos of this brew are in the app (not attached)");
    expect(md).toContain("in this grinder's units, the way the dial reads");
    expect(md.split("\n").slice(1).join("\n")).toMatchSnapshot();
  });

  it("trend table has one row per brew", () => {
    const logs = [newBrew({ recipeName: "A", overall: 6, grindClicks: 70 }), newBrew({ recipeName: "B", overall: 8, grindSetting: "medium" })];
    const md = trendMarkdown(logs, []);
    expect(md).toContain("# Brew history — last 2 brews");
    expect(md.split("\n").filter((l) => l.startsWith("| ") && !l.startsWith("| When") && !l.startsWith("| ---")).length).toBe(2);
  });
});
