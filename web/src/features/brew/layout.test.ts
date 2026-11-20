import { describe, expect, it } from "vitest";
import { runLayoutForSize, setupLayoutForSize } from "./layout";

describe("brew running layout by window size (§3.5)", () => {
  it.each([
    [390, 844, "portrait"],
    [360, 800, "portrait"],
    [820, 1180, "portrait"],
    [768, 1024, "portrait"],
    [1024, 768, "stage"],
    [1024, 600, "stage"],
    [1180, 820, "stage"],
    [1440, 900, "stage"],
    [1920, 1080, "stage"],
    [2560, 1440, "stage"],
    [640, 360, "short"],
    [844, 390, "short"],
    [915, 412, "short"],
    // 1440x900 at 200% browser zoom.
    [720, 450, "short"],
    // Narrow and short (split screen on a phone in landscape).
    [560, 400, "short"],
    // Square-ish windows are not landscape: portrait.
    [900, 900, "portrait"],
    // Landscape but under 600 wide stays portrait.
    [590, 500, "portrait"],
  ] as const)("%i x %i → %s", (w, h, want) => {
    expect(runLayoutForSize(w, h)).toBe(want);
  });

  it("the boundaries are exactly 480 tall and 600 wide", () => {
    expect(runLayoutForSize(800, 479)).toBe("short");
    expect(runLayoutForSize(800, 480)).toBe("stage");
    expect(runLayoutForSize(599, 480)).toBe("portrait");
    expect(runLayoutForSize(600, 480)).toBe("stage");
  });
});

describe("brew setup layout (§3.4)", () => {
  it.each([
    [390, 844, "single"],
    [820, 1180, "single"],
    [840, 1180, "two"],
    [1024, 768, "two"],
    [1440, 900, "two"],
    [700, 500, "two"],
    [844, 390, "short"],
    [640, 360, "short"],
    [560, 400, "single"],
  ] as const)("%i x %i → %s", (w, h, want) => {
    expect(setupLayoutForSize(w, h)).toBe(want);
  });
});
