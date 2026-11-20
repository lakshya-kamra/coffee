/**
 * Words explained where they appear (v4 §4.5). The terms live in shared/glossary.json (loaded by
 * src/catalog as `catalog.glossary`); these helpers are pure. Kotlin twin: domain/Glossary.kt.
 */
import type { StepKind } from "./recipe";

export interface GlossarySource {
  readonly who: string;
  readonly url: string;
  readonly note: string;
}

export interface GlossaryTerm {
  readonly id: string;
  /** As shown: "Drawdown", "Days off roast". */
  readonly term: string;
  /** One or two plain sentences. */
  readonly short: string;
  /** Optional longer text; "" when there is none. */
  readonly more: string;
  readonly aliases: readonly string[];
  readonly sources: readonly GlossarySource[];
}

const STEP_TERMS: Readonly<Partial<Record<StepKind, string>>> = {
  Bloom: "bloom",
  Pour: "pour",
  Swirl: "swirl",
  Stir: "stir",
  Steep: "steep",
  Press: "press",
  Drawdown: "drawdown",
};

/** The glossary id that explains a step kind; null for Prep and Serve, which need no explaining. */
export function termForStepKind(kind: StepKind): string | null {
  return STEP_TERMS[kind] ?? null;
}

/** A term by id, or by one of its aliases (case-insensitive). */
export function findTerm(terms: readonly GlossaryTerm[], key: string): GlossaryTerm | null {
  const k = key.trim().toLowerCase();
  if (!k) return null;
  return terms.find((t) => t.id === k) ?? terms.find((t) => t.term.toLowerCase() === k || t.aliases.some((a) => a.toLowerCase() === k)) ?? null;
}

// Most specific first: "medium-fine" must win over "medium" and "fine".
const GRIND_REFERENCES: readonly (readonly [RegExp, string])[] = [
  [/^(extra|very)[\s-]*fine\b/, "like flour"],
  [/^turkish\b/, "like flour"],
  [/^(extra|very)[\s-]*coarse\b/, "like cracked pepper"],
  [/^(medium[\s-]*fine|fine[\s-]*medium)\b/, "like table salt"],
  [/^(medium[\s-]*coarse|coarse[\s-]*medium)\b/, "like coarse sand"],
  [/^fine\b/, "like fine salt"],
  [/^coarse\b/, "like sea salt"],
  [/^medium\b/, "like sand"],
];

/**
 * Something everyone has seen, for the grind word a recipe starts with: "Medium-fine" → "like
 * table salt". Null when the grind text does not start with one of the seven words.
 */
export function grindReference(word: string): string | null {
  const t = word.trim().toLowerCase();
  for (const [re, ref] of GRIND_REFERENCES) if (re.test(t)) return ref;
  return null;
}
