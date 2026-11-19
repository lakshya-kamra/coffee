/**
 * Port of A§3.2 `DialFormat`: how a stored grind number is shown as a dial reading.
 * The stored number never changes meaning: total clicks from zero on click grinders (K-Ultra
 * dial 7.5 is stored as 75, lead decision 2), the printed dial number on the others.
 */

export type DialFormat =
  /** "14.5": what you type is what you see. */
  | { readonly kind: "plain" }
  /** "3 turns + 2". */
  | { readonly kind: "turns"; readonly clicksPerTurn: number }
  /** "7.5" = 7 turns + 5 clicks. 1.x behaviour, only for cpr ≤ 10. */
  | { readonly kind: "turns-dot"; readonly clicksPerTurn: number }
  /** A numbered dial with a number every `ticksPerNumber` clicks (1Zpresso-style external dials). cpr 0 = never wraps. */
  | { readonly kind: "number-tick"; readonly clicksPerRotation: number; readonly ticksPerNumber: number };

export const PLAIN: DialFormat = Object.freeze({ kind: "plain" });

export const DialFormats = {
  plain: (): DialFormat => PLAIN,
  turnsAndClicks: (clicksPerTurn: number): DialFormat => ({ kind: "turns", clicksPerTurn }),
  turnsDotClicks: (clicksPerTurn: number): DialFormat => ({ kind: "turns-dot", clicksPerTurn }),
  numberTick: (clicksPerRotation: number, ticksPerNumber = 10): DialFormat => ({ kind: "number-tick", clicksPerRotation, ticksPerNumber }),
};

/** "plain" | "turns" | "turns-dot" | "number-tick:<n>". */
export function encodeDialFormat(f: DialFormat): string {
  switch (f.kind) {
    case "plain": return "plain";
    case "turns": return "turns";
    case "turns-dot": return "turns-dot";
    case "number-tick": return `number-tick:${f.ticksPerNumber}`;
  }
}

/** null for "" (auto) or anything that does not decode with this clicks-per-rotation. */
export function decodeDialFormat(s: string | null | undefined, cpr: number | null | undefined): DialFormat | null {
  const v = (s ?? "").trim();
  if (!v) return null;
  const c = cpr ?? 0;
  if (v === "plain") return PLAIN;
  if (v === "turns") return c > 0 ? DialFormats.turnsAndClicks(c) : null;
  if (v === "turns-dot") return c > 0 && c <= 10 ? DialFormats.turnsDotClicks(c) : null;
  const m = /^number-tick:(\d+)$/.exec(v);
  if (m) {
    // As Kotlin: 1..10 ticks per number; cpr 0 = the dial never wraps (or the wrap is unknown).
    const n = Number(m[1]);
    return n >= 1 && n <= 10 ? DialFormats.numberTick(c, n) : null;
  }
  return null;
}

/** Whole turns are a real unit on this dial, so "one full turn" is something a person can do. */
export function talksInTurns(f: DialFormat): boolean {
  return f.kind === "turns" || f.kind === "turns-dot";
}

/** The dial shows a number to read out ("dial 7.5"), not just a click count. */
export function showsDialNumber(f: DialFormat): boolean {
  return f.kind === "turns-dot" || f.kind === "number-tick";
}

/** True for formats whose words depend on clicks per rotation. */
export function usesRotation(f: DialFormat): boolean {
  return f.kind !== "plain";
}
