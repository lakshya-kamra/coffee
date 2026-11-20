// Recipe card grind cell: a short word, never a sentence cut off with an ellipsis.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { grindWord } from "./grindWord";

describe("grindWord", () => {
  it.each([
    ["Medium-fine", "Medium-fine"],
    ["Coarse, a step coarser than your usual paper-filter setting", "Coarse"],
    ["The finer end of medium (for light roasts). Darker roasts: coarser.", "Medium-fine"],
    ["Fine. Near espresso, definitely finer than V60", "Fine"],
    ["Espresso. Dial by taste, not by number", "Espresso"],
    ["Finer than your pour-over setting, but nowhere near espresso", "Medium-fine"],
    ["25 clicks on a Comandante C40. She gives no other grind measure.", "25 clicks"],
    ["About 800 microns, coarser than his V60 grind", "~800 µm"],
    ["Mahlkönig EK43S. The archive rates the grind 7 on a 1-10 scale (1 very fine, 10 very coarse).", "7 of 10"],
    ["Macap Labo 70D at 7.0 (medium)", "Medium"],
    ["Very coarse on a Timemore, then reground on a Kinu at 4.", "Very coarse"],
    ["Filter grind, or slightly finer.", "Filter grind"],
    ["Not specified by Rao. Start at your usual V60 setting and adjust to the time.", "Not given"],
    ["", ""],
  ])("%s -> %s", (grind, word) => {
    expect(grindWord(grind)).toBe(word);
  });

  it("gives every built-in recipe a short word (at most 14 characters)", () => {
    const raw = JSON.parse(readFileSync(new URL("../../../../shared/recipes.json", import.meta.url), "utf8")) as unknown;
    const list = (Array.isArray(raw) ? raw : (raw as { recipes: unknown[] }).recipes) as { id: string; grind?: string }[];
    for (const r of list) expect(grindWord(r.grind ?? "").length, r.id).toBeLessThanOrEqual(14);
  });
});
