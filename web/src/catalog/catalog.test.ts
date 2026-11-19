// Mirrors A§2.8 CatalogParseTest + LegacyIdsTest, reading the same shared/ files the app ships.
import { beforeAll, describe, expect, it } from "vitest";
import { BREW_METHOD_IDS } from "@/domain/core/brewer";
import { LegacyIds } from "@/domain/core/legacyIds";
import { Brewers, catalog } from "./catalog";
import { KEBAB } from "./normalise";

beforeAll(async () => {
  await catalog.load();
});

describe("catalog", () => {
  it("parses every file with no problems", () => {
    expect(catalog.problems).toEqual([]);
    expect(catalog.grinders.length).toBeGreaterThanOrEqual(50);
    expect(catalog.roasters.length).toBeGreaterThanOrEqual(400);
    expect(catalog.origins.length).toBeGreaterThanOrEqual(50);
    expect(catalog.water.length).toBeGreaterThan(0);
    expect(catalog.providers.length).toBeGreaterThanOrEqual(15);
    expect(catalog.beans.processes.length).toBeGreaterThan(0);
  });

  it("uses unique lowercase-kebab ids in every list", () => {
    const lists: Record<string, readonly { id: string }[]> = {
      grinders: catalog.grinders,
      roasters: catalog.roasters,
      origins: catalog.origins,
      water: catalog.water,
      providers: catalog.providers,
      species: catalog.beans.species,
      processes: catalog.beans.processes,
      varietals: catalog.beans.varietals,
      lotTypes: catalog.beans.lotTypes,
      decafMethods: catalog.beans.decafMethods,
      roastLevels: catalog.beans.roastLevels,
      roastIntents: catalog.beans.roastIntents,
      brewers: catalog.brewers,
    };
    for (const [name, list] of Object.entries(lists)) {
      const ids = list.map((x) => x.id);
      expect(new Set(ids).size, `${name} ids unique`).toBe(ids.length);
      for (const id of ids) expect(id, `${name}: ${id}`).toMatch(KEBAB);
    }
  });

  it("holds its cross references", () => {
    const processIds = new Set(catalog.beans.processes.map((p) => p.id));
    const varietalIds = new Set(catalog.beans.varietals.map((v) => v.id));
    const speciesIds = new Set(catalog.beans.species.map((s) => s.id));
    for (const o of catalog.origins) {
      for (const p of o.commonProcesses) expect(processIds.has(p), `${o.id} process ${p}`).toBe(true);
      for (const v of o.commonVarietals) expect(varietalIds.has(v), `${o.id} varietal ${v}`).toBe(true);
    }
    for (const v of catalog.beans.varietals) if (v.species) expect(speciesIds.has(v.species), `${v.id} species`).toBe(true);
    for (const b of catalog.brewers) expect(BREW_METHOD_IDS).toContain(b.method);
  });

  it("describes grinders the way the grind scale reads them", () => {
    for (const g of catalog.grinders) {
      expect(["conical", "flat", "ghost", "blade", ""], `${g.id} burr`).toContain(g.burr);
      expect(["clicks", "numbers", "stepless"], `${g.id} style`).toContain(g.setting.style);
      for (const k of Object.keys(g.ranges)) expect(BREW_METHOD_IDS, `${g.id} range ${k}`).toContain(k);
      if (g.setting.style === "clicks") {
        // Stored unit is total clicks from zero (A§3.1): whole numbers, step 1.
        expect(g.setting.step, `${g.id} step`).toBe(1);
        expect(Number.isInteger(g.setting.min) && Number.isInteger(g.setting.max), `${g.id} min/max`).toBe(true);
        for (const [k, r] of Object.entries(g.ranges)) expect(Number.isInteger(r.from) && Number.isInteger(r.to), `${g.id} ${k}`).toBe(true);
      }
    }
  });

  it("knows the K-Ultra as 100 clicks per turn at 20 µm (lead decision 2)", () => {
    const k = catalog.grinder("1zpresso-k-ultra");
    expect(k?.setting.style).toBe("clicks");
    expect(k?.setting.clicksPerRotation).toBe(100);
    expect(k?.setting.micronsPerStep).toBe(20);
  });

  it("gives every provider with models exactly one default", () => {
    for (const p of catalog.providers) {
      if (p.models.length === 0) continue;
      expect(p.models.filter((m) => m.default).length, p.id).toBe(1);
    }
    expect(catalog.provider("anthropic")?.browserHeaders["anthropic-dangerous-direct-browser-access"]).toBe("true");
  });

  it("maps every legacy id to a shipped catalog id (LegacyIdsTest)", () => {
    const fileBrewerIds = new Set(catalog.brewers.map((b) => b.id));
    for (const id of Object.values(LegacyIds.BREWER)) expect(fileBrewerIds.has(id), id).toBe(true);
    for (const f of LegacyIds.FALLBACK_BREWERS) expect(fileBrewerIds.has(f.id), f.id).toBe(true);
    const processIds = new Set(catalog.beans.processes.map((p) => p.id));
    for (const id of [...Object.values(LegacyIds.PROCESS), ...Object.values(LegacyIds.PROCESS_LABEL)]) if (id) expect(processIds.has(id), id).toBe(true);
    const roastIds = new Set(catalog.beans.roastLevels.map((r) => r.id));
    for (const id of [...Object.values(LegacyIds.ROAST_LEVEL), ...Object.values(LegacyIds.ROAST_LEVEL_LABEL)]) if (id) expect(roastIds.has(id), id).toBe(true);
  });

  it("resolves legacy brewer strings (lead decision 8)", () => {
    expect(Brewers.resolve("V60").id).toBe("hario-v60-02");
    expect(Brewers.resolve("Hario V60").id).toBe("hario-v60-02");
    expect(Brewers.resolve("Kalita").id).toBe("kalita-wave-185");
    expect(Brewers.resolve("chemex").id).toBe("chemex");
    expect(Brewers.resolve("Something else").id).toBe("other");
    expect(Brewers.resolveOrNull("")).toBeNull();
  });

  it("labels vocabulary ids", () => {
    expect(catalog.label("process", "washed")).toBe("Washed");
    expect(catalog.label("process", "no-such-process")).toBeNull();
    expect(catalog.process("natural")?.id).toBe("natural");
  });
});
