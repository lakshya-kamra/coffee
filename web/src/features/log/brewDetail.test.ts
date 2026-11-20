// The brew detail's pure parts (v4 §3.8, A3, A4): an espresso shot is measured against its band in
// every line of the screen, and the cells follow the review's sections.
import { beforeAll, describe, expect, it } from "vitest";
import { Brewers, catalog } from "@/catalog/catalog";
import { newBrew, type BrewRow } from "@/data/schema";
import { reviewSections } from "@/domain/core/brewer";
import { withSettingsDefaults } from "@/data/settings";
import { totalSec } from "@/domain/core/recipe";
import { BAND_WORDS, bandStatus, judgeTime, shotBand, timeTarget } from "@/domain/core/shotTarget";
import { timeStatus } from "@/domain/core/status";
import { unitsOf } from "@/domain/core/units";
import { brewTimeline, passCells, passOutG } from "./BrewDetailScreen";

beforeAll(async () => {
  await catalog.load(["brewers", "recipes", "grinders"]);
});

const recipe = (id: string) => {
  const r = catalog.recipe(id);
  if (!r) throw new Error(`${id} is not in recipes.json`);
  return r;
};
const units = unitsOf(withSettingsDefaults(null));

function brew(p: Partial<BrewRow>): BrewRow {
  return { ...newBrew({ doseG: 18, waterG: 45, ...p }, 0), id: 1 };
}

describe("espresso against its band (A4)", () => {
  it("a 38 s shot on hedrick-espresso is slow by 3 s everywhere, never fast against 0:45", () => {
    const r = recipe("hedrick-espresso");
    const band = shotBand(r);
    expect(band.label).toBe("about 35 s");
    const log = brew({ recipeId: r.id, brewerId: "espresso-machine", totalTimeSec: 38 });
    const words = BAND_WORDS[bandStatus(38, band)];
    expect(words.lead).toBe("Slow");
    const rows = brewTimeline(log, r, words.status, true, null, band);
    const finished = rows[rows.length - 1]!;
    expect(finished.title).toBe("Finished");
    expect(finished.detail).toBe("aim about 35 s");
    // 35 is the band's edge: 38 is 3 s past it (the 2 s slack decides in or out, not the size).
    expect(finished.delta).toBe("+0:03");
    const cells = passCells(log, r, null, [words.status, words.lead], new Set(), units, null, "SHOT TIME", reviewSections(Brewers.byId("espresso-machine")), band);
    expect(cells.find((c) => c.label === "SHOT TIME")?.sub).toBe("aim about 35 s");
  });

  it("inside the band the delta is 0:00, and filter coffee still measures from the total", () => {
    const r = recipe("hedrick-espresso");
    const band = shotBand(r);
    const rows = brewTimeline(brew({ recipeId: r.id, totalTimeSec: 36 }), r, "good", true, null, band);
    expect(rows[rows.length - 1]!.delta).toBe("0:00");
    const v60 = recipe("hoffmann-v60");
    const t = totalSec(v60);
    const plain = brewTimeline(brew({ recipeId: v60.id, totalTimeSec: t + 20 }), v60, timeStatus(t + 20, t)[0], true, null);
    expect(plain[plain.length - 1]!.detail).toBe(`planned ${Math.floor(t / 60)}:${String(t % 60).padStart(2, "0")}`);
    expect(plain[plain.length - 1]!.delta).toBe("+0:20");
  });

  it("the lists judge a shot by its band: a 14 s gusher is fast, never 'on time'", () => {
    const r = recipe("3fe-classic-double");
    const target = timeTarget(r, totalSec(r));
    expect(judgeTime(14, target)).toEqual({ status: "attention", delta: -11, hit: "in band" });
    expect(judgeTime(43, target)).toEqual({ status: "attention", delta: 13, hit: "in band" });
    expect(judgeTime(27, target)?.status).toBe("good");
    // Filter coffee keeps the ±15 s window around its total.
    const v60 = recipe("hoffmann-v60");
    expect(judgeTime(totalSec(v60) + 10, timeTarget(v60, totalSec(v60)))?.status).toBe("good");
  });
});

describe("cells and timeline follow the method and the scale", () => {
  it("a Clever keeps IN CUP beside DRAWDOWN, as Review asks for both", () => {
    const sections = reviewSections(Brewers.byId("clever-dripper"));
    expect(sections.drawdown).toBe(true);
    expect(sections.retained).toBe(false);
    const log = brew({ brewerId: "clever-dripper", waterG: 250, doseG: 15 });
    const labels = passCells(log, null, null, ["neutral", ""], new Set(), units, null, "TIME", sections).map((c) => c.label);
    expect(labels).toEqual(["GRIND", "TIME", "TEMP", "DRAWDOWN", "IN CUP", "OFF ROAST"]);
  });

  it("a shot's YIELD is what came out, and espresso has no IN CUP cell (Android 6564550)", () => {
    const log = brew({ brewerId: "espresso-machine", doseG: 18, waterG: 36, beverageG: 42 });
    expect(passOutG(log, "espresso")).toBe(42);
    expect(passOutG({ ...log, beverageG: null }, "espresso")).toBe(36);
    expect(passOutG(log, "pourover")).toBe(36);
    const labels = passCells(log, null, null, ["neutral", ""], new Set(), units, null, "SHOT TIME", reviewSections(Brewers.byId("espresso-machine"))).map((c) => c.label);
    expect(labels).not.toContain("IN CUP");
  });

  it("without a scale IN CUP reads in ml", () => {
    const log = brew({ brewerId: "clever-dripper", waterG: 250, doseG: 15, beverageG: 212.4 });
    const sections = reviewSections(Brewers.byId("clever-dripper"));
    const cup = (hasScale: boolean) => passCells(log, null, null, ["neutral", ""], new Set(), units, null, "TIME", sections, null, hasScale).find((c) => c.label === "IN CUP");
    expect(cup(true)).toMatchObject({ value: "212.4", unit: "g" });
    expect(cup(false)).toMatchObject({ value: "212", unit: "ml" });
  });

  it("without a scale the planned steps read in ml", () => {
    const r = recipe("hoffmann-v60");
    const rows = brewTimeline(brew({ recipeId: r.id, totalTimeSec: 200, waterG: 500, doseG: 30 }), r, "good", true, null, null, false);
    const text = rows.map((x) => `${x.title} ${x.detail ?? ""}`).join(" ");
    expect(text).not.toMatch(/\d g\b/);
    expect(text).toMatch(/\d ml\b/);
  });
});
