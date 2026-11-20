// Window classes (v4 §1.1): the exact breakpoints, the short and landscape modifiers.
import { describe, expect, it } from "vitest";
import { atLeast, isWideLandscape, QUERIES, widthClassFor, windowClassFor, WIDTH_MIN } from "./windowClass";

describe("windowClass", () => {
  it("maps widths to classes at exactly the §1.1 boundaries", () => {
    const cases: [number, string][] = [
      [320, "compact"],
      [599, "compact"],
      [599.98, "compact"],
      [600, "medium"],
      [839, "medium"],
      [840, "expanded"],
      [1199, "expanded"],
      [1200, "large"],
      [1599, "large"],
      [1600, "xl"],
      [2560, "xl"],
    ];
    for (const [w, c] of cases) expect(widthClassFor(w), String(w)).toBe(c);
  });

  it("flags short (< 480 high) and landscape (wider than tall)", () => {
    expect(windowClassFor(390, 844)).toEqual({ width: "compact", short: false, landscape: false });
    expect(windowClassFor(844, 390)).toEqual({ width: "expanded", short: true, landscape: true });
    expect(windowClassFor(640, 360)).toEqual({ width: "medium", short: true, landscape: true });
    expect(windowClassFor(1024, 768)).toEqual({ width: "expanded", short: false, landscape: true });
    expect(windowClassFor(1440, 900)).toEqual({ width: "large", short: false, landscape: true });
    expect(windowClassFor(1920, 1080)).toEqual({ width: "xl", short: false, landscape: true });
    expect(windowClassFor(700, 1000)).toEqual({ width: "medium", short: false, landscape: false });
    expect(windowClassFor(800, 480).short).toBe(false);
    expect(windowClassFor(800, 479).short).toBe(true);
  });

  it("orders classes", () => {
    expect(atLeast("expanded", "expanded")).toBe(true);
    expect(atLeast("large", "expanded")).toBe(true);
    expect(atLeast("medium", "expanded")).toBe(false);
    expect(atLeast("compact", "compact")).toBe(true);
    expect(atLeast("xl", "large")).toBe(true);
  });

  it("knows wide-landscape (the brew stage)", () => {
    expect(isWideLandscape(windowClassFor(1024, 768))).toBe(true);
    expect(isWideLandscape(windowClassFor(844, 390))).toBe(false); // short
    expect(isWideLandscape(windowClassFor(768, 1024))).toBe(false); // portrait
    expect(isWideLandscape(windowClassFor(560, 400))).toBe(false); // compact
  });

  it("keeps the CSS media queries and the JS numbers in step", () => {
    expect(QUERIES.medium).toBe(`(min-width: ${WIDTH_MIN.medium}px)`);
    expect(QUERIES.expanded).toBe(`(min-width: ${WIDTH_MIN.expanded}px)`);
    expect(QUERIES.large).toBe(`(min-width: ${WIDTH_MIN.large}px)`);
    expect(QUERIES.xl).toBe(`(min-width: ${WIDTH_MIN.xl}px)`);
    expect(QUERIES.compact).toBe("(max-width: 599.98px)");
    expect(QUERIES.short).toBe("(max-height: 479.98px)");
  });
});
