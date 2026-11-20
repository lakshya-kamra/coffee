package com.laksh.coffee.domain

import com.laksh.coffee.domain.catalog.GlossaryTerm
import java.util.Locale

/**
 * Words explained where they appear (v4 §4.5). The terms live in shared/glossary.json (loaded as
 * `Catalog.glossary`); these helpers are pure. Web twin: domain/core/glossary.ts.
 */
object Glossary {

    private val STEP_TERMS = mapOf(
        StepKind.Bloom to "bloom",
        StepKind.Pour to "pour",
        StepKind.Swirl to "swirl",
        StepKind.Stir to "stir",
        StepKind.Steep to "steep",
        StepKind.Press to "press",
        StepKind.Drawdown to "drawdown",
    )

    /** The glossary id that explains a step kind; null for Prep and Serve, which need no explaining. */
    fun termForStepKind(kind: StepKind): String? = STEP_TERMS[kind]

    /** A term by id, or by one of its aliases (case-insensitive). */
    fun find(terms: List<GlossaryTerm>, key: String): GlossaryTerm? {
        val k = key.trim().lowercase(Locale.ROOT)
        if (k.isEmpty()) return null
        return terms.firstOrNull { it.id == k }
            ?: terms.firstOrNull { t -> t.term.lowercase(Locale.ROOT) == k || t.aliases.any { it.lowercase(Locale.ROOT) == k } }
    }

    // Most specific first: "medium-fine" must win over "medium" and "fine".
    private val GRIND_REFERENCES = listOf(
        Regex("^(extra|very)[\\s-]*fine\\b") to "like flour",
        Regex("^turkish\\b") to "like flour",
        Regex("^(extra|very)[\\s-]*coarse\\b") to "like cracked pepper",
        Regex("^(medium[\\s-]*fine|fine[\\s-]*medium)\\b") to "like table salt",
        Regex("^(medium[\\s-]*coarse|coarse[\\s-]*medium)\\b") to "like coarse sand",
        Regex("^fine\\b") to "like fine salt",
        Regex("^coarse\\b") to "like sea salt",
        Regex("^medium\\b") to "like sand",
    )

    /**
     * Something everyone has seen, for the grind word a recipe starts with: "Medium-fine" → "like
     * table salt". Null when the grind text does not start with one of the seven words.
     */
    fun grindReference(word: String): String? {
        val t = word.trim().lowercase(Locale.ROOT)
        return GRIND_REFERENCES.firstOrNull { (re, _) -> re.containsMatchIn(t) }?.second
    }
}
