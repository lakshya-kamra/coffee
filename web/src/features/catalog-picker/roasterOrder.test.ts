// The roaster picker's empty-query order (v4 §4.7): favourites and region, mainstream, specialty A-Z.
import { describe, expect, it } from "vitest";
import { catalog } from "@/catalog/catalog";
import { FAVOURITES_GROUP, inRegion, MAINSTREAM_GROUP, roasterGroups, SPECIALTY_GROUP, type RoasterLike } from "./roasterOrder";

const r = (id: string, name: string, area: string, mainstream = false, regions: string[] = []): RoasterLike => ({ id, name, area, mainstream, regions });

const LIST: RoasterLike[] = [
  r("onyx", "Onyx Coffee Lab", "North America"),
  r("square-mile", "Square Mile", "Europe"),
  r("folgers", "Folgers", "North America", true, ["North America"]),
  r("lavazza", "Lavazza", "Europe", true, ["Europe", "North America"]),
  r("bru", "Bru", "Asia", true, ["Asia"]),
  r("blue-tokai", "Blue Tokai", "Asia"),
  r("april", "april coffee", "Europe"),
];

describe("roasterGroups", () => {
  it("puts favourites, then your region, then supermarket brands, then specialty A-Z", () => {
    const g = roasterGroups(LIST, "Europe", ["onyx"]);
    expect(g.map((x) => x.name)).toEqual([FAVOURITES_GROUP, "Europe", MAINSTREAM_GROUP, SPECIALTY_GROUP]);
    expect(g[0]!.ids).toEqual(["onyx"]);
    // Lavazza is sold in Europe, so it sits with the region, A-Z ignoring case.
    expect(g[1]!.ids).toEqual(["april", "lavazza", "square-mile"]);
    expect(g[2]!.ids).toEqual(["bru", "folgers"]);
    expect(g[3]!.ids).toEqual(["blue-tokai", "onyx"]);
  });

  it("lists every roaster once outside the favourites", () => {
    const g = roasterGroups(LIST, "North America", ["onyx", "bru"]);
    const rest = g.filter((x) => x.name !== FAVOURITES_GROUP).flatMap((x) => x.ids);
    expect([...rest].sort()).toEqual(LIST.map((x) => x.id).sort());
    expect(new Set(rest).size).toBe(rest.length);
    // A brand sold in the region counts as the region even when it is based elsewhere.
    expect(g.find((x) => x.name === "North America")!.ids).toEqual(["folgers", "lavazza", "onyx"]);
  });

  it("without a region: mainstream first, then specialty, and no empty groups", () => {
    const g = roasterGroups(LIST, "", []);
    expect(g.map((x) => x.name)).toEqual([MAINSTREAM_GROUP, SPECIALTY_GROUP]);
    expect(g[0]!.ids).toEqual(["bru", "folgers", "lavazza"]);
    expect(inRegion(LIST[0]!, "")).toBe(false);
  });

  it("orders the real catalog with mainstream brands ahead of specialty roasters", async () => {
    await catalog.load(["roasters"]);
    const g = roasterGroups(catalog.roasters, "", []);
    expect(g[0]!.name).toBe(MAINSTREAM_GROUP);
    expect(g[0]!.ids.length).toBeGreaterThanOrEqual(10);
    expect(g.flatMap((x) => x.ids)).toHaveLength(catalog.roasters.length);
  });
});
