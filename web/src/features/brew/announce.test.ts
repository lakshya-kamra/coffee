import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RecipeStep } from "@/domain/core/recipe";
import { Announcer, doneAnnouncement, stepAnnouncement, stepLead, TEN_LEFT, tenLeftDue, TIME_FOR_NEXT } from "./announce";

const steps: RecipeStep[] = [
  { kind: "Bloom", title: "Bloom", detail: "Wet all the coffee", startSec: 0, endSec: 45, targetWaterG: 60 },
  { kind: "Pour", title: "Main pour", detail: "Steady spiral from the centre outward.", startSec: 45, endSec: 75, targetWaterG: 300 },
  { kind: "Pour", title: "Second pour", detail: "", startSec: 75, endSec: 105, targetWaterG: 500 },
  { kind: "Swirl", title: "Swirl", detail: "Gently.", startSec: 105, endSec: 115, targetWaterG: null },
  { kind: "Steep", title: "Wait", detail: "Let it settle", startSec: 115, endSec: 150, targetWaterG: null },
  { kind: "Drawdown", title: "Drawdown", detail: "", startSec: 150, endSec: 210, targetWaterG: null },
];

describe("A4 announcement strings", () => {
  it("step change: 'Step 2 of 6, Pour to 300 g. <detail> Next at 1:15.'", () => {
    expect(stepAnnouncement(steps, 1)).toBe("Step 2 of 6, Pour to 300 g. Steady spiral from the centre outward. Next at 1:15.");
    expect(stepAnnouncement(steps, 0)).toBe("Step 1 of 6, Bloom to 60 g. Wet all the coffee. Next at 0:45.");
    expect(stepAnnouncement(steps, 2)).toBe("Step 3 of 6, Pour to 500 g. Next at 1:45.");
    expect(stepAnnouncement(steps, 4)).toBe("Step 5 of 6, Wait. Let it settle. Next at 2:30.");
    // The last step has no "Next at".
    expect(stepAnnouncement(steps, 5)).toBe("Step 6 of 6, Drawdown.");
    expect(stepAnnouncement(steps, 9)).toBe("");
  });

  it("without a scale: the targets in ml, unrounded, the detail's grams as ml (A1)", () => {
    expect(stepLead(steps[1]!, false)).toBe("Pour to 300 ml");
    expect(stepLead(steps[0]!, false)).toBe("Bloom to 60 ml");
    // The same number the step card's title says through noScaleText, never "about 350 ml".
    expect(stepLead({ kind: "Pour", title: "Up to 352 g", targetWaterG: 352 }, false)).toBe("Pour to 352 ml");
    expect(stepLead({ kind: "Pour", title: "Pour all 500 g", targetWaterG: null }, false)).toBe("Pour all 500 ml");
    const press: RecipeStep[] = [{ kind: "Pour", title: "Pour", detail: "Pour all 500 g straight onto the grounds in one go.", startSec: 0, endSec: 10, targetWaterG: 500 }];
    expect(stepAnnouncement(press, 0, false)).toBe("Step 1 of 1, Pour to 500 ml. Pour all 500 ml straight onto the grounds in one go.");
    expect(stepAnnouncement(press, 0)).toBe("Step 1 of 1, Pour to 500 g. Pour all 500 g straight onto the grounds in one go.");
  });

  it("done, the boundary with auto-advance off, and ten seconds left", () => {
    expect(doneAnnouncement(212.7)).toBe("Brew done, 3:32. Rate this brew.");
    expect(TIME_FOR_NEXT).toBe("Time for the next step.");
    expect(TEN_LEFT).toBe("10 seconds left");
  });

  it("stepLead names pours by their target", () => {
    expect(stepLead(steps[0]!)).toBe("Bloom to 60 g");
    expect(stepLead(steps[3]!)).toBe("Swirl");
    expect(stepLead({ kind: "Pour", title: "Top up", targetWaterG: null })).toBe("Top up");
  });

  it("'10 seconds left' only in steps longer than 30 s, in their last 10 seconds", () => {
    const long = steps[4]; // 35 s
    expect(tenLeftDue(long, 139)).toBe(false);
    expect(tenLeftDue(long, 140)).toBe(true);
    expect(tenLeftDue(long, 149.5)).toBe(true);
    expect(tenLeftDue(long, 150)).toBe(false);
    expect(tenLeftDue(steps[1], 70)).toBe(false); // exactly 30 s: not longer than 30
    expect(tenLeftDue(undefined, 10)).toBe(false);
  });
});

describe("Announcer", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("clears the region before each write, so a repeat is read again", () => {
    // The writer only touches textContent; a plain object stands in for the <p>.
    const el = { textContent: "" };
    const a = new Announcer(() => el as unknown as HTMLElement);
    a.say("Time for the next step.");
    expect(el.textContent).toBe("");
    vi.advanceTimersByTime(100);
    expect(el.textContent).toBe("Time for the next step.");
    a.say("Time for the next step.");
    expect(el.textContent).toBe("");
    vi.advanceTimersByTime(100);
    expect(el.textContent).toBe("Time for the next step.");
    // A second write before the first lands replaces it.
    a.say("one");
    a.say("two");
    vi.advanceTimersByTime(100);
    expect(el.textContent).toBe("two");
    a.dispose();
  });
});
