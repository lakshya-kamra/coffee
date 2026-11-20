// Where each detail renders (v4 §2.3 rule 1, D4): pane vs layer vs focus, the nav's cover, and
// which keys flip (no animation) when the window crosses 840 or a ListDetail mounts.
import { describe, expect, it } from "vitest";
import { flippedKeys, layersOf, navCovered, paneRouteOf, planDetails } from "./layerPlan";
import { parsePath, type Route, type TabId } from "./routes";
import type { WidthClass } from "./windowClass";

function stack(...paths: string[]): Route[] {
  return paths.map((p) => parsePath(p)!);
}

const RECIPES: ReadonlySet<TabId> = new Set(["recipes"]);
const BOTH: ReadonlySet<TabId> = new Set(["recipes", "log"]);
const NONE: ReadonlySet<TabId> = new Set();

describe("planDetails", () => {
  it("puts a pane-able detail above its own tab in the pane at expanded and up", () => {
    for (const w of ["expanded", "large", "xl"] as WidthClass[]) {
      const plan = planDetails(stack("/recipes", "/recipes/hoffmann-v60"), "recipes", w, RECIPES);
      expect(plan.map((d) => d.placement), w).toEqual(["pane"]);
      expect(layersOf(plan)).toEqual([]);
      expect(paneRouteOf(plan)?.route).toEqual({ kind: "recipe", id: "hoffmann-v60" });
    }
  });

  it("keeps today's layer below expanded", () => {
    for (const w of ["compact", "medium"] as WidthClass[]) {
      const plan = planDetails(stack("/recipes", "/recipes/hoffmann-v60"), "recipes", w, RECIPES);
      expect(plan.map((d) => d.placement), w).toEqual(["layer"]);
    }
  });

  it("keeps today's layer for a tab that has no ListDetail mounted", () => {
    const plan = planDetails(stack("/log", "/log/u1"), "log", "large", RECIPES);
    expect(plan.map((d) => d.placement)).toEqual(["layer"]);
    expect(planDetails(stack("/recipes", "/recipes/x"), "recipes", "xl", NONE)[0]!.placement).toBe("layer");
  });

  it("uses a layer when the detail belongs to another tab (a recipe opened from Today)", () => {
    const plan = planDetails(stack("/", "/recipes/x"), "today", "large", BOTH);
    expect(plan[0]!.placement).toBe("layer");
  });

  it("only the detail at stack index 1 can be a pane; focus modes always cover", () => {
    const plan = planDetails(stack("/recipes", "/recipes/x", "/brew/x", "/review/u1"), "recipes", "large", RECIPES);
    expect(plan.map((d) => d.placement)).toEqual(["pane", "focus", "focus"]);
    const deeper = planDetails(stack("/log", "/log/u1", "/log/u1/compare/u2"), "log", "large", BOTH);
    expect(deeper.map((d) => d.placement)).toEqual(["pane", "layer"]);
    expect(planDetails(stack("/log", "/log/u1/compare/u2"), "log", "expanded", BOTH)[0]!.placement).toBe("pane");
  });

  it("onboarding and the recipe editor are focus modes at every size", () => {
    for (const w of ["compact", "medium", "large"] as WidthClass[]) {
      expect(planDetails(stack("/", "/welcome"), "today", w, BOTH)[0]!.placement).toBe("focus");
      expect(planDetails(stack("/recipes", "/recipes/new"), "recipes", w, BOTH)[0]!.placement).toBe("focus");
    }
  });

  it("keys are `index:path`, the same whichever way a detail renders", () => {
    const a = planDetails(stack("/recipes", "/recipes/x", "/brew/x"), "recipes", "compact", RECIPES);
    const b = planDetails(stack("/recipes", "/recipes/x", "/brew/x"), "recipes", "large", RECIPES);
    expect(a.map((d) => d.key)).toEqual(["0:/recipes/x", "1:/brew/x"]);
    expect(b.map((d) => d.key)).toEqual(a.map((d) => d.key));
  });
});

describe("navCovered", () => {
  it("covers the bar under any layer on compact, the rail only under a focus mode", () => {
    const recipe = stack("/recipes", "/recipes/x");
    expect(navCovered(planDetails(recipe, "recipes", "compact", RECIPES), "compact")).toBe(true);
    expect(navCovered(planDetails(recipe, "recipes", "medium", RECIPES), "medium")).toBe(false);
    expect(navCovered(planDetails(recipe, "recipes", "large", RECIPES), "large")).toBe(false);
    const brew = stack("/", "/brew/hoffmann-v60");
    for (const w of ["compact", "medium", "expanded", "large", "xl"] as WidthClass[]) expect(navCovered(planDetails(brew, "today", w, RECIPES), w), w).toBe(true);
    expect(navCovered(planDetails(stack("/"), "today", "compact", RECIPES), "compact")).toBe(false);
  });
});

describe("resize across 840 (flippedKeys)", () => {
  it("names the detail that moves between pane and layer, and nothing above it", () => {
    const s = stack("/recipes", "/recipes/x", "/brew/x");
    const wide = planDetails(s, "recipes", "large", RECIPES);
    const narrow = planDetails(s, "recipes", "medium", RECIPES);
    expect([...flippedKeys(wide, narrow)]).toEqual(["0:/recipes/x"]);
    expect([...flippedKeys(narrow, wide)]).toEqual(["0:/recipes/x"]);
    // Within one side of 840 nothing flips.
    expect(flippedKeys(wide, planDetails(s, "recipes", "xl", RECIPES)).size).toBe(0);
    // A pop is not a flip (it animates as usual).
    expect(flippedKeys(narrow, planDetails(stack("/recipes"), "recipes", "medium", RECIPES)).size).toBe(0);
  });

  it("a ListDetail mounting late flips the deep-linked detail into the pane", () => {
    const s = stack("/recipes", "/recipes/x");
    const before = planDetails(s, "recipes", "large", NONE);
    const after = planDetails(s, "recipes", "large", RECIPES);
    expect([...flippedKeys(before, after)]).toEqual(["0:/recipes/x"]);
  });
});
