import { describe, expect, it } from "vitest";
import { grinder, parseList } from "./normalise";

describe("parseList", () => {
  it("skips a bad entry, records why, and keeps the rest", () => {
    const raw = {
      version: 1,
      grinders: [
        { id: "good-one", brand: "A", model: "B", setting: { style: "clicks", min: 0, max: 40, step: 1 } },
        { id: "Bad Id", brand: "A", model: "B", setting: { style: "clicks", min: 0, max: 40, step: 1 } },
        { id: "no-setting", brand: "A", model: "B" },
        { id: "good-one", brand: "A", model: "C", setting: { style: "numbers", min: 1, max: 40, step: 1 } },
      ],
    };
    const p = parseList("catalog/grinders.json", raw, "grinders", grinder);
    expect(p.items.map((g) => g.id)).toEqual(["good-one"]);
    expect(p.problems).toEqual([
      'catalog/grinders.json[1] (id=Bad Id): id "Bad Id" is not lowercase-kebab',
      "catalog/grinders.json[2] (id=no-setting): missing setting",
      "catalog/grinders.json[3] (id=good-one): duplicate id",
    ]);
  });

  it("turns a missing file into an empty list and one problem line", () => {
    const p = parseList("catalog/grinders.json", undefined, "grinders", grinder);
    expect(p.items).toEqual([]);
    expect(p.problems).toEqual(["catalog/grinders.json: missing file"]);
  });

  it("fills schema defaults", () => {
    const p = parseList("x", { version: 1, grinders: [{ id: "a", brand: "A", model: "B", setting: { style: "stepless", min: 0, max: 10, step: 0.1 } }] }, "grinders", grinder);
    const g = p.items[0]!;
    expect(g.popularity).toBe(3);
    expect(g.confidence).toBe("low");
    expect(g.setting.lowerIsFiner).toBe(true);
    expect(g.setting.dialFormat).toBeNull();
    expect(g.display).toBe("A B");
  });
});
