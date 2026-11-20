import { describe, expect, it } from "vitest";
import { windowClassFor } from "@/app/windowClass";
import { todayLayout } from "./todayLayout";

const at = (w: number, h: number) => todayLayout(windowClassFor(w, h));

describe("Today layout (v4 §3.1)", () => {
  it("phone: one column, a carousel, no tiles", () => {
    const l = at(390, 844);
    expect(l).toMatchObject({ mode: "column", startCols: 1, recentCount: 3, yourCoffee: false, lastGrind: false, startInline: false, maxWidth: null });
  });

  it("medium: split with Your coffee and a 2-up Start from (no carousel)", () => {
    expect(at(700, 1000)).toMatchObject({ mode: "split", startCols: 2, startCount: 4, yourCoffee: true, lastGrind: false });
  });

  it("expanded: the 12-column dashboard at 1200, 3-up, more recent rows", () => {
    expect(at(1024, 768)).toMatchObject({ mode: "dashboard", startCols: 3, startCount: 6, recentCount: 5, yourCoffee: true, lastGrind: false, maxWidth: 1200 });
  });

  it("large and XL add Last grind, 4-up; XL is 1360 wide", () => {
    expect(at(1440, 900)).toMatchObject({ mode: "dashboard", startCols: 4, recentCount: 5, lastGrind: true, maxWidth: 1200 });
    expect(at(1920, 1080)).toMatchObject({ mode: "dashboard", startCols: 4, lastGrind: true, maxWidth: 1360 });
  });

  it("short landscape: no dashboard, Start brew inline", () => {
    expect(at(844, 390)).toMatchObject({ mode: "split", startInline: true });
    expect(at(568, 320)).toMatchObject({ mode: "column", startInline: true });
    expect(at(1280, 450)).toMatchObject({ mode: "split", startInline: true, maxWidth: null });
    // A tall narrow window is not "inline".
    expect(at(390, 844).startInline).toBe(false);
  });
});
