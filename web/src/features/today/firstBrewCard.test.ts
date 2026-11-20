import { beforeAll, describe, expect, it } from "vitest";
import { Brewers, catalog } from "@/catalog/catalog";
import { DEFAULT_SETTINGS } from "@/data/settings";
import { firstBrewCard, noRecipeLine } from "./firstBrewCard";

beforeAll(async () => {
  await catalog.load(["brewers", "recipes"]);
});

const byId = (id: string) => Brewers.byId(id);
const settings = (p: Partial<typeof DEFAULT_SETTINGS>) => ({ ...DEFAULT_SETTINGS, ...p });

describe("YOUR FIRST BREW card (v4 §4.6)", () => {
  it("a drip machine and French press owner never gets a V60, and sees the machine's own first line", () => {
    const card = firstBrewCard(catalog.recipes, settings({ ownedBrewerIds: ["drip-machine", "french-press"], hasScale: false, experience: "new" }), [], byId);
    expect(card?.kind).toBe("recipe");
    if (card?.kind !== "recipe") return;
    expect(["drip-machine", "french-press"]).toContain(card.recipe.brewer.id);
    expect(card.recipe.brewer.id).not.toMatch(/v60/);
    expect(card.needs.join(" | ")).not.toMatch(/V60/);
    expect(card.steps[1]).toBe("Measure the coffee and the water.");
    if (card.recipe.brewer.id === "drip-machine") expect(card.steps[0]).toBe("Fill the machine's tank with fresh cold water.");
  });

  it("a French press owner without a scale: spoons and ml, a kettle, a mug, and the exact checklist", () => {
    const card = firstBrewCard(catalog.recipes, settings({ ownedBrewerIds: ["french-press"], hasScale: false, buysGround: true }), [], byId);
    expect(card?.kind).toBe("recipe");
    if (card?.kind !== "recipe") return;
    expect(card.recipe.brewer.id).toBe("french-press");
    expect(card.needs[0]).toBe("French press");
    expect(card.needs).toContain("Kettle");
    expect(card.needs).toContain("A mug");
    expect(card.needs).toContain("Ground coffee");
    expect(card.needs.some((n) => /level tablespoons? of coffee$/.test(n))).toBe(true);
    expect(card.needs.some((n) => /^\d+ ml water$/.test(n))).toBe(true);
    expect(card.needs).not.toContain("Kitchen scale");
    expect(card.steps).toEqual(["Boil the kettle, then let it sit for 30 seconds.", "Measure the coffee and the water.", "Press Start as you pour. We'll tell you each step."]);
  });

  it("with a scale the amounts are in grams", () => {
    const card = firstBrewCard(catalog.recipes, settings({ ownedBrewerIds: ["french-press"], hasScale: true }), [{ isArchived: false }], byId);
    if (card?.kind !== "recipe") throw new Error("no card");
    expect(card.needs.some((n) => /^\d+ g coffee$/.test(n))).toBe(true);
    expect(card.needs).toContain("Grinder");
  });

  it("no recipes yet, or none for what you own", () => {
    expect(firstBrewCard([], settings({}), [], byId)).toBeNull();
    const onlyV60 = catalog.recipes.filter((r) => r.brewer.id === "hario-v60-02");
    const card = firstBrewCard(onlyV60, settings({ ownedBrewerIds: ["cezve"] }), [], byId);
    expect(card).toEqual({ kind: "none", missing: ["Cezve / ibrik"] });
    expect(noRecipeLine(["Cezve"])).toBe("We don't have a tested recipe for your Cezve yet.");
    expect(noRecipeLine(["Cezve", "Phin", "Drip bag"])).toBe("We don't have a tested recipe for your Cezve, Phin or Drip bag yet.");
  });
});
