// shared/fixtures/domain-v4-cases.json: the v4 domain functions give exactly these results. The
// Android app runs the same file (DomainV4ParityTest), so both apps agree case for case.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, it } from "vitest";
import { Brewers, catalog } from "@/catalog/catalog";
import { newBrew, type BrewRow } from "@/data/schema";
import { checkBrew } from "@/domain/ai/dataQuality";
import { brewerArt, brewerFamily, makeBrewer, methodLabels, reviewSections, type BrewMethod } from "./brewer";
import { DEFAULT_TASTE } from "./evaluation";
import { firstBrew, type FirstBrewSettings } from "./firstBrew";
import { formatDuration, ratioPhrase } from "./format";
import { grindReference } from "./glossary";
import { matchesLogQuery, type LogSearchable } from "./logQuery";
import { stepLead } from "@/features/brew/announce";
import { noScaleAmounts, noScalePrep, noScaleText, servingsPreset, type BeanForm } from "./noScale";
import { quickRead, type QuickReadInput, type QuickReadOptions } from "./quickRead";
import type { Recipe, RecipeLevel, RecipeNeed, RecipeStep } from "./recipe";
import { defaultTempUnit, PROSE_TEMP, renderTemps, stepsInUnit } from "./units";

type StubInput = { brewer?: string; totalSec?: number; doseG?: number; waterG?: number; id?: string; level?: RecipeLevel; needs?: RecipeNeed[]; prep?: string[]; prepNoScale?: string[]; method?: BrewMethod };
type Pair<I, O> = { in: I; out: O };

/** The shape of shared/fixtures/domain-v4-cases.json. */
interface Cases {
  proseTempPattern: string;
  noScaleAmounts: Pair<{ doseG: number; waterG: number; method: BrewMethod; form: BeanForm }, unknown>[];
  noScalePrep: { recipe: StubInput & { prep: string[] }; fahrenheit: boolean; form: BeanForm; out: string[] }[];
  servingsPreset: { recipe: string; mugs: number; out: { doseG: number; waterG: number } }[];
  renderTemps: Pair<{ text: string; unit: "c" | "f" }, string>[];
  formatDuration: Pair<number, string>[];
  ratioPhrase: Pair<{ doseG: number; waterG: number; method: string }, string>[];
  grindReference: Pair<string, string | null>[];
  brewerFamily: Pair<string, string>[];
  brewerArt: Pair<string, string>[];
  reviewSections: Pair<string | null, unknown>[];
  methodLabels: Pair<BrewMethod, unknown>[];
  defaultTempUnit: Pair<string, string>[];
  matchesLogQuery: { brew: LogSearchable; cases: { query: string; out: boolean }[] };
  firstBrew: {
    recipes: StubInput[];
    cases: { settings: FirstBrewSettings; grinders: number; out: { recipe: string | null; noRecipeFor: string[] } }[];
  };
  quickRead: { name: string; brewer: string | null; recipe: { totalSec: number; doseG: number } | null; log: Partial<QuickReadInput>; opts?: QuickReadOptions; out: string[] }[];
  grindMissing: { name: string; log: Partial<BrewRow>; missing: boolean }[];
  noScaleText: Pair<string, string>[];
  stepLeadNoScale: Pair<Pick<RecipeStep, "kind" | "title" | "targetWaterG">, string>[];
}

const cases = JSON.parse(readFileSync(fileURLToPath(new URL("../../../../shared/fixtures/domain-v4-cases.json", import.meta.url)), "utf8")) as Cases;

beforeAll(async () => {
  await catalog.load(["brewers", "recipes", "grinders"]);
});

const brewer = (id: string) => {
  const b = Brewers.byId(id);
  if (!b) throw new Error(`${id} is not in brewers.json`);
  return b;
};

/** A recipe with only what a case gives: brewer, numbers, one step for the total time. */
function stub(p: StubInput): Recipe {
  const b = p.brewer ? brewer(p.brewer) : makeBrewer({ id: "x", label: "X", method: p.method ?? "pourover" });
  return {
    id: p.id ?? "case", name: p.id ?? "Case", author: "", brewer: b, doseG: p.doseG ?? 15, waterG: p.waterG ?? 250, grind: "", tempC: 93, blurb: "",
    sourceNote: "", prep: p.prep ?? [], tips: [], steps: [{ kind: "Pour", title: "", detail: "", startSec: 0, endSec: p.totalSec ?? 180, targetWaterG: null }],
    accent: 0, isBuiltIn: true, fidelity: "", sources: [], basedOn: null, level: p.level ?? "", needs: p.needs ?? [], prepNoScale: p.prepNoScale ?? [],
  };
}

describe("shared/fixtures/domain-v4-cases.json", () => {
  it("prose temperatures use the shared pattern", () => {
    expect(PROSE_TEMP).toBe(cases.proseTempPattern);
  });

  it("noScaleAmounts", () => {
    for (const c of cases.noScaleAmounts) {
      expect(noScaleAmounts(c.in.doseG, c.in.waterG, c.in.method, c.in.form), JSON.stringify(c.in)).toEqual(c.out);
    }
  });

  it("noScalePrep", () => {
    for (const c of cases.noScalePrep) {
      const r = stub(c.recipe);
      expect(noScalePrep(r, { fahrenheit: c.fahrenheit, ounces: false }, c.form), c.recipe.prep[0]).toEqual(c.out);
    }
  });

  it("noScaleText (step prose without a scale, A1)", () => {
    for (const c of cases.noScaleText) expect(noScaleText(c.in), c.in).toBe(c.out);
  });

  it("stepLeadNoScale (banner, status line and announcements without a scale, A1)", () => {
    for (const c of cases.stepLeadNoScale) expect(stepLead(c.in, false), JSON.stringify(c.in)).toBe(c.out);
  });

  it("servingsPreset", () => {
    for (const c of cases.servingsPreset) {
      const r = servingsPreset(catalog.recipe(c.recipe)!, c.mugs);
      expect({ doseG: r.doseG, waterG: r.waterG }, `${c.recipe} x${c.mugs}`).toEqual(c.out);
    }
  });

  it("renderTemps", () => {
    for (const c of cases.renderTemps) expect(renderTemps(c.in.text, c.in.unit), c.in.text).toBe(c.out);
  });

  it("stepsInUnit converts step titles as well as details", () => {
    const steps = [{ title: "CLOSE, 160 g at 70 °C", detail: "Pour to 160 g at 70 °C." }];
    expect(stepsInUnit(steps, "f")).toEqual([{ title: "CLOSE, 160 g at 158 °F", detail: "Pour to 160 g at 158 °F." }]);
    expect(stepsInUnit(steps, "c")).toBe(steps);
  });

  it("formatDuration, ratioPhrase, grindReference", () => {
    for (const c of cases.formatDuration) expect(formatDuration(c.in), String(c.in)).toBe(c.out);
    for (const c of cases.ratioPhrase) expect(ratioPhrase(c.in.doseG, c.in.waterG, c.in.method), JSON.stringify(c.in)).toBe(c.out);
    for (const c of cases.grindReference) expect(grindReference(c.in), c.in).toBe(c.out);
  });

  it("brewerFamily, brewerArt, reviewSections, methodLabels", () => {
    for (const c of cases.brewerFamily) expect(brewerFamily(brewer(c.in)), c.in).toBe(c.out);
    for (const c of cases.brewerArt) expect(brewerArt(brewer(c.in)), c.in).toBe(c.out);
    for (const c of cases.reviewSections) {
      const s = reviewSections(c.in == null ? null : brewer(c.in));
      expect({ drawdown: s.drawdown, bed: s.bed, retained: s.retained, style: s.style }, String(c.in)).toEqual(c.out);
    }
    for (const c of cases.methodLabels) expect(methodLabels(c.in), c.in).toEqual(c.out);
  });

  it("defaultTempUnit", () => {
    for (const c of cases.defaultTempUnit) expect(defaultTempUnit(c.in), c.in).toBe(c.out);
  });

  it("matchesLogQuery", () => {
    for (const c of cases.matchesLogQuery.cases) expect(matchesLogQuery(cases.matchesLogQuery.brew, c.query), c.query).toBe(c.out);
  });

  it("firstBrew", () => {
    const recipes = cases.firstBrew.recipes.map((r) => stub(r));
    for (const c of cases.firstBrew.cases) {
      const grinders = Array.from({ length: c.grinders }, () => ({ isArchived: false }));
      const pick = firstBrew(recipes, c.settings, grinders, (id) => Brewers.byId(id));
      expect({ recipe: pick.recipe?.id ?? null, noRecipeFor: pick.noRecipeFor }, JSON.stringify(c.settings)).toEqual(c.out);
    }
  });

  it("quickRead v2: one case per table cell", () => {
    for (const c of cases.quickRead) {
      const log = { taste: DEFAULT_TASTE, faults: [], bedShape: null, channeling: null, totalTimeSec: 0, daysOffRoast: null, extractionYield: null, ...c.log };
      const recipe = c.recipe ? stub({ brewer: c.brewer ?? undefined, totalSec: c.recipe.totalSec, doseG: c.recipe.doseG }) : null;
      expect(quickRead(log, recipe, { brewer: c.brewer ? brewer(c.brewer) : null, ...c.opts }), c.name).toEqual(c.out);
    }
  });

  it("GRIND_MISSING is skipped for pre-ground and recorded for blade seconds", () => {
    for (const c of cases.grindMissing) {
      const flags = checkBrew({ ...newBrew({ ...c.log, doseG: 15, waterG: 250, tempC: 93, totalTimeSec: 180 }), id: 1 }, null, null, null);
      expect(flags.some((f) => f.code === "GRIND_MISSING"), c.name).toBe(c.missing);
    }
  });

  it("a drip machine owner's first brew is never a V60 recipe, against the real catalog", () => {
    const pick = firstBrew(catalog.recipes, { ownedBrewerIds: ["drip-machine"], hasScale: false, buysGround: true, experience: "new" }, [], (id) => Brewers.byId(id));
    expect(pick.recipe?.brewer.id).toBe("drip-machine");
    for (const r of catalog.recipes) if (r.brewer.id.startsWith("hario-v60")) expect(pick.recipe?.id).not.toBe(r.id);
  });
});
