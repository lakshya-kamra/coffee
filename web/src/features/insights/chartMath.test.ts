// Insights chart interaction (v4 §3.9): the point under the pointer, ticks, tooltip copy, keys.
import { describe, expect, it } from "vitest";
import { chartKeyStep, nearestIndex, slotCentres, spacedTicks, tickIndices, tooltipAlign, tooltipText } from "./chartMath";
import { EXPORT_LABELS, insightsEmptyText } from "./copy";

describe("chart point lookup by x", () => {
  it("finds the nearest point, ties to the later one, -1 when empty", () => {
    const xs = [0, 0.25, 0.5, 1];
    expect(nearestIndex(xs, 0.1)).toBe(0);
    expect(nearestIndex(xs, 0.3)).toBe(1);
    expect(nearestIndex(xs, 0.375)).toBe(2);
    expect(nearestIndex(xs, 0.9)).toBe(3);
    expect(nearestIndex(xs, -5)).toBe(0);
    expect(nearestIndex(xs, 5)).toBe(3);
    expect(nearestIndex([], 0.5)).toBe(-1);
  });

  it("bar slots are looked up by their centres", () => {
    const c = slotCentres(4);
    expect(c).toEqual([0.125, 0.375, 0.625, 0.875]);
    expect(nearestIndex(c, 0.49)).toBe(1);
    expect(nearestIndex(c, 0.51)).toBe(2);
  });
});

describe("ticks", () => {
  it("spreads at most max ticks, always first and last", () => {
    expect(tickIndices(0)).toEqual([]);
    expect(tickIndices(1)).toEqual([0]);
    expect(tickIndices(2)).toEqual([0, 1]);
    expect(tickIndices(10, 4)).toEqual([0, 3, 6, 9]);
    expect(tickIndices(3, 5)).toEqual([0, 1, 2]);
  });

  it("drops crowded ticks but keeps the last date", () => {
    expect(spacedTicks([0, 0.05, 0.5, 0.97, 1], [0, 1, 2, 3, 4])).toEqual([0, 2, 4]);
    expect(spacedTicks([0.05, 0.35, 0.65, 0.95], [0, 1, 2, 3])).toEqual([0, 1, 2, 3]);
  });
});

describe("tooltip", () => {
  const at = new Date(2026, 10, 12, 8, 14).getTime();
  it("reads date · recipe · score in the locale", () => {
    expect(tooltipText(at, "Hoffmann V60", 8, null, "en-GB")).toBe("Thu 12 Nov · Hoffmann V60 · 8/10");
    expect(tooltipText(at, "", 0, "72 clicks", "en-GB")).toBe("Thu 12 Nov · Brew · 72 clicks · not rated");
  });
  it("leans away from the edges", () => {
    expect(tooltipAlign(0.05)).toBe("start");
    expect(tooltipAlign(0.5)).toBe("center");
    expect(tooltipAlign(0.95)).toBe("end");
  });
});

describe("keyboard point navigation", () => {
  it("Left/Right move, Home/End jump; the first key starts at the newest point", () => {
    expect(chartKeyStep("ArrowLeft", null, 5)).toBe(4);
    expect(chartKeyStep("ArrowRight", null, 5)).toBe(4);
    expect(chartKeyStep("ArrowRight", 4, 5)).toBe(4);
    expect(chartKeyStep("ArrowLeft", 0, 5)).toBe(0);
    expect(chartKeyStep("Home", 3, 5)).toBe(0);
    expect(chartKeyStep("End", 0, 5)).toBe(4);
    expect(chartKeyStep("Enter", 0, 5)).toBeNull();
    expect(chartKeyStep("ArrowLeft", null, 0)).toBeNull();
  });
});

describe("copy", () => {
  it("the empty state and the plain export labels (§4.12, §4.16)", () => {
    expect(insightsEmptyText(1)).toBe("Insights start after 3 brews. 2 to go.");
    expect(insightsEmptyText(0)).toBe("Insights start after 3 brews. 3 to go.");
    expect(EXPORT_LABELS.lastBrew).toBe("Copy as text for an AI chat (this brew and the 8 before it)");
    expect(EXPORT_LABELS.backup).toBe("Save a backup file");
  });
});
