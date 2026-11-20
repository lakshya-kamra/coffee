// The inline editor's dirty/discard rules and the Settings section routing (v4 §3.10).
import { beforeEach, describe, expect, it, vi } from "vitest";
import { newBean, newGrinder } from "@/data/schema";
import { answerDiscard, Drafts, isAsking, requestSelect, rowsDiffer, type InlineSelection } from "./inlineEdit";
import { currentSection, resetSectionForTests, sectionAnchor, sectionFor, sectionLabel, selectSection, SETTINGS_SECTIONS, subscribeSection, takeJump } from "./settingsSections";

describe("rowsDiffer", () => {
  it("ignores updatedAt and treats equal arrays as equal", () => {
    const a = newBean({ name: "Kiamabara", varietalIds: ["sl28", "sl34"] });
    expect(rowsDiffer(a, { ...a, updatedAt: a.updatedAt + 5000 })).toBe(false);
    expect(rowsDiffer(a, { ...a, varietalIds: ["sl28", "sl34"] })).toBe(false);
    expect(rowsDiffer(a, { ...a, varietalIds: ["sl28"] })).toBe(true);
    expect(rowsDiffer(a, { ...a, name: "Kiamabara AA" })).toBe(true);
    expect(rowsDiffer(a, { ...a, form: "ground", groundFor: "press" })).toBe(true);
  });

  it("works for grinders too", () => {
    const g = newGrinder({ brand: "Any", model: "Blade grinder", settingUnit: "s" });
    expect(rowsDiffer(g, { ...g })).toBe(false);
    expect(rowsDiffer(g, { ...g, defaultFilterSetting: 12 })).toBe(true);
  });
});

describe("moving the selection with unsaved changes", () => {
  const start: InlineSelection = { selected: "bean-a" };

  it("moves at once when nothing is unsaved", () => {
    expect(requestSelect(start, "bean-b", false)).toEqual({ selected: "bean-b" });
    expect(requestSelect(start, null, false)).toEqual({ selected: null });
  });

  it("asks first when the current row is dirty, and Keep editing stays put", () => {
    const asked = requestSelect(start, "bean-b", true);
    expect(isAsking(asked)).toBe(true);
    expect(asked.selected).toBe("bean-a");
    const kept = answerDiscard(asked, false);
    expect(kept).toEqual({ selected: "bean-a" });
    expect(isAsking(kept)).toBe(false);
  });

  it("Discard moves on, including to the default (null)", () => {
    expect(answerDiscard(requestSelect(start, "bean-b", true), true)).toEqual({ selected: "bean-b" });
    expect(answerDiscard(requestSelect(start, null, true), true)).toEqual({ selected: null });
  });

  it("selecting the row you are on never asks", () => {
    expect(requestSelect(start, "bean-a", true)).toBe(start);
    expect(answerDiscard(start, true)).toBe(start);
  });
});

describe("Drafts", () => {
  it("keeps a draft until it matches the saved row again", () => {
    const saved = newBean({ name: "Kiamabara" });
    const d = new Drafts<typeof saved>();
    d.put(saved.uid, { ...saved, name: "Kiamabara AA" }, saved);
    expect(d.dirty(saved.uid)).toBe(true);
    expect(d.get(saved.uid)?.name).toBe("Kiamabara AA");
    d.put(saved.uid, { ...saved }, saved);
    expect(d.dirty(saved.uid)).toBe(false);
    expect(d.dirty(null)).toBe(false);
  });

  it("a new row is dirty only once it differs from the blank template", () => {
    const blank = newBean({ name: "" });
    const d = new Drafts<typeof blank>();
    d.put(blank.uid, { ...blank }, blank);
    expect(d.dirty(blank.uid)).toBe(false);
    d.put(blank.uid, { ...blank, name: "House blend" }, blank);
    expect(d.dirty(blank.uid)).toBe(true);
    d.drop(blank.uid);
    expect(d.get(blank.uid)).toBeUndefined();
  });
});

describe("Settings sections", () => {
  beforeEach(() => resetSectionForTests());

  it("lists the seven sections in order", () => {
    expect(SETTINGS_SECTIONS.map((x) => x.label)).toEqual(["You", "Units and measuring", "Brewing", "Coffee and water", "AI analysis", "Your data", "About"]);
  });

  it("routes ids, labels and aliases, and sends anything else to You", () => {
    expect(sectionFor("brewing")).toBe("brewing");
    expect(sectionFor("Units and measuring")).toBe("units");
    expect(sectionFor("Your data")).toBe("data");
    expect(sectionFor("backup")).toBe("data");
    expect(sectionFor("AI analysis")).toBe("ai");
    expect(sectionFor("water")).toBe("coffee");
    expect(sectionFor("nonsense")).toBe("you");
    expect(sectionFor(null)).toBe("you");
    expect(sectionLabel("coffee")).toBe("Coffee and water");
    expect(sectionAnchor("ai")).toBe("settings-ai");
  });

  it("remembers the chosen section and hands a scroll jump over once", () => {
    const seen = vi.fn();
    const off = subscribeSection(seen);
    expect(currentSection()).toBe("you");
    selectSection("data", { scroll: true });
    expect(currentSection()).toBe("data");
    expect(seen).toHaveBeenCalledTimes(1);
    expect(takeJump()).toBe("data");
    expect(takeJump()).toBeNull();
    selectSection("data");
    expect(seen).toHaveBeenCalledTimes(1); // no change, no render
    selectSection("units");
    expect(seen).toHaveBeenCalledTimes(2);
    expect(takeJump()).toBeNull();
    off();
  });
});
