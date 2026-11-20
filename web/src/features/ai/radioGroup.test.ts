// A2: the AI settings radios move and check with the arrows, wrapping, like any radio group.
import { describe, expect, it } from "vitest";
import { radioStep } from "./radioGroup";

describe("radioStep", () => {
  it("wraps with the arrows and jumps with Home/End", () => {
    expect(radioStep("ArrowDown", 0, 3)).toBe(1);
    expect(radioStep("ArrowRight", 2, 3)).toBe(0);
    expect(radioStep("ArrowUp", 0, 3)).toBe(2);
    expect(radioStep("ArrowLeft", 1, 3)).toBe(0);
    expect(radioStep("Home", 2, 3)).toBe(0);
    expect(radioStep("End", 0, 3)).toBe(2);
    expect(radioStep("Enter", 0, 3)).toBeNull();
    expect(radioStep("ArrowDown", 0, 0)).toBeNull();
  });
});
