// Parity with the Android theme: tokens.gen.css must equal what gen-tokens makes from Color.kt,
// and the spring table must match Motion.kt. Both skip when app/ is absent (e.g. a web-only upload).
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { argbToCss, generateTokensCss, kebab } from "../../scripts/gen-tokens.mjs";
import { ICONS } from "./icons";
import { dampingFor, SPRINGS, spatialDefault } from "./motion";
import { liquidTransform, solveSpring, springAt } from "./useSlidingIndicator";

const colorKt = fileURLToPath(new URL("../../../app/src/main/java/com/laksh/coffee/ui/theme/Color.kt", import.meta.url));
const motionKt = fileURLToPath(new URL("../../../app/src/main/java/com/laksh/coffee/ui/theme/Motion.kt", import.meta.url));
const genCss = fileURLToPath(new URL("./tokens.gen.css", import.meta.url));

describe("colour tokens", () => {
  it("converts ARGB the way the spec's spot checks say", () => {
    expect(argbToCss("F01C1916")).toBe("rgb(28 25 22 / 0.941)");
    expect(argbToCss("99000000")).toBe("rgb(0 0 0 / 0.6)");
    expect(argbToCss("521B1611")).toBe("rgb(27 22 17 / 0.322)");
    expect(argbToCss("33F29B50")).toBe("rgb(242 155 80 / 0.2)");
    expect(argbToCss("FF8F430C")).toBe("#8F430C");
    expect(kebab("hairlineOnBg")).toBe("hairline-on-bg");
    expect(kebab("surface1")).toBe("surface1");
  });

  it.skipIf(!existsSync(colorKt))("tokens.gen.css is current with Color.kt (run `npm run tokens`)", () => {
    const expected = generateTokensCss(readFileSync(colorKt, "utf8"));
    const committed = readFileSync(genCss, "utf8").replace(/\r\n/g, "\n");
    expect(committed).toBe(expected);
    expect(committed).toContain("--bar: rgb(28 25 22 / 0.941);");
    expect(committed).toContain("--crema-ink: #8F430C;");
    expect(committed).toContain("--mood-done-a: #14301F;");
  });
});

const NAMED: Record<string, number> = {
  "Spring.DampingRatioNoBouncy": 1,
  "Spring.DampingRatioLowBouncy": 0.75,
  "Spring.DampingRatioMediumBouncy": 0.5,
  "Spring.DampingRatioHighBouncy": 0.2,
  "Spring.StiffnessHigh": 10_000,
  "Spring.StiffnessMedium": 1_500,
  "Spring.StiffnessMediumLow": 400,
  "Spring.StiffnessLow": 200,
  "Spring.StiffnessVeryLow": 50,
};

function num(raw: string | undefined): number | null {
  if (!raw) return null;
  const t = raw.trim();
  if (t in NAMED) return NAMED[t]!;
  const n = Number(t.replace(/f$/i, ""));
  return Number.isFinite(n) ? n : null;
}

/** Every `fun x(): … = spring(…)` and `val X = spring(…)` in Motion.kt, with its ζ and k. */
function kotlinSprings(src: string): Map<string, { zeta: number; k: number }> {
  const out = new Map<string, { zeta: number; k: number }>();
  const re = /(?:fun\s*(?:<[^>]*>\s*)?(\w+)\s*\([^)]*\)[^=]*=|val\s+(\w+)\s*(?::[^=]*)?=)\s*spring(?:<[^>]*>)?\(([^)]*)\)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src))) {
    const name = m[1] ?? m[2]!;
    const args = m[3]!;
    const zeta = num(/dampingRatio\s*=\s*([\w.]+)/.exec(args)?.[1]) ?? 1;
    const k = num(/stiffness\s*=\s*([\w.]+)/.exec(args)?.[1]) ?? 1500;
    out.set(name, { zeta, k });
  }
  return out;
}

describe("motion tokens", () => {
  it("derives Motion damping from Compose ζ and k at mass 1", () => {
    expect(dampingFor(0.85, 380)).toBeCloseTo(33.14, 2);
    expect(dampingFor(0.9, 700)).toBeCloseTo(47.62, 2);
    expect(dampingFor(1, 3800)).toBeCloseTo(123.29, 2);
    expect(spatialDefault.restDelta).toBe(0.5);
  });

  it("keeps spatial movement at ζ ≥ 0.9 (bounce is the tab icon only)", () => {
    for (const [name, s] of Object.entries(SPRINGS)) {
      if (name.startsWith("spatial") || name === "bounds") expect(s.zeta, name).toBeGreaterThanOrEqual(0.9);
    }
  });

  it("the liquid pill stretches with speed like Forge's (|v| x 7, capped at 30, 12% squash)", () => {
    expect(liquidTransform("x", 2, 0, 78, 78, 56)).toBe("translateX(156px) scale(1, 1)");
    // 2 slots a second: 14 px longer, 1.68 px thinner.
    expect(liquidTransform("x", 1.5, 2, 78, 78, 56)).toBe("translateX(117px) scale(1.179, 0.97)");
    // Fast: capped at 30 px whatever the speed, in both directions.
    expect(liquidTransform("x", 1, -40, 78, 78, 56)).toBe(liquidTransform("x", 1, 40, 78, 78, 56));
    expect(liquidTransform("x", 1, 40, 78, 78, 56)).toBe("translateX(78px) scale(1.385, 0.936)");
    // The rail stretches down its column.
    expect(liquidTransform("y", 1, 40, 64, 64, 64)).toBe("translateY(64px) scale(0.944, 1.469)");
  });

  it("solves the pill's spring up front: rests on the target, overshoot under a hundredth of a slot, retargets from its own state", () => {
    const p = solveSpring(0, 0, 3);
    expect(p.x.at(-1)).toBe(3);
    expect(p.v.at(-1)).toBe(0);
    expect(Math.max(...p.x)).toBeLessThan(3.01);
    expect((p.x.length - 1) * p.dt).toBeLessThan(0.8);
    const mid = springAt(p, 0.08);
    expect(mid.x).toBeGreaterThan(0.5);
    expect(mid.v).toBeGreaterThan(5); // fast mid-slide: the pill is at full stretch
    // Tapped back mid-slide: the new spring starts where and as fast as the old one was.
    const back = solveSpring(mid.x, mid.v, 0);
    expect(back.x[0]).toBe(mid.x);
    expect(back.v[0]).toBe(mid.v);
    expect(back.x.at(-1)).toBe(0);
  });

  it.skipIf(!existsSync(motionKt))("matches every spring Motion.kt defines under the same name", () => {
    const kt = kotlinSprings(readFileSync(motionKt, "utf8"));
    expect(kt.size).toBeGreaterThan(0);
    for (const name of ["settle", "bounce", "heavy", "quick"]) expect(kt.has(name), `Motion.kt ${name}()`).toBe(true);
    for (const [token, s] of Object.entries(SPRINGS)) {
      for (const kName of s.kotlin) {
        const k = kt.get(kName);
        if (!k) continue;
        expect({ token, zeta: s.zeta, k: s.k }, `web ${token} vs Motion.kt ${kName}`).toEqual({ token, zeta: k.zeta, k: k.k });
      }
    }
  });
});

describe("icons", () => {
  // Icon.tsx draws every glyph in viewBox "0 -960 960 960"; a glyph left on the 24 px grid is invisible.
  it("every glyph is on the 960 grid", () => {
    for (const [name, d] of Object.entries(ICONS)) {
      const nums = (d.match(/-?(?:\d+\.?\d*|\.\d+)/g) ?? []).map(Number);
      expect(Math.max(...nums.map(Math.abs)), name).toBeGreaterThan(100);
    }
  });
});
