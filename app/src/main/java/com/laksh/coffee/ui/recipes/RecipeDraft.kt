package com.laksh.coffee.ui.recipes

import com.laksh.coffee.core.grams
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeSource
import com.laksh.coffee.domain.RecipeStep
import com.laksh.coffee.domain.StepKind
import kotlin.math.roundToInt
import kotlin.random.Random

enum class EditorMode { New, Edit, Duplicate }

/** One step while it is being edited: a duration and, for pours, the cumulative grams on the scale. */
data class StepDraft(
    /** Stable list key; never saved. */
    val key: Long,
    val kind: StepKind,
    val title: String,
    val detail: String,
    val durationSec: Int,
    /** Cumulative grams when this step ends, as typed; "" = no target. */
    val pourTo: String,
)

/**
 * What the recipe builder holds while you type. Numbers stay text until save, so "1." or an empty
 * field is never forced into a number mid-keystroke. Start and end seconds are derived
 * cumulatively on save, so reordering or resizing a step can never leave a gap. Pure Kotlin; the
 * web app's domain/editor/draft.ts is the same model.
 */
data class RecipeDraft(
    val id: String,
    val name: String,
    val author: String,
    val brewer: Brewer,
    val dose: String,
    val water: String,
    /** Always °C: the stepper moves 1 °C, and °F is display only. */
    val tempC: Int,
    val grind: String,
    val blurb: String,
    val prep: List<String>,
    val tips: List<String>,
    val steps: List<StepDraft>,
    val accent: Long,
    val fidelity: String,
    val sources: List<RecipeSource>,
    val basedOn: String?,
    val sourceNote: String,
    // v4: carried through unedited, so saving a recipe never drops them.
    val level: String = "",
    val needs: List<String> = emptyList(),
    val prepNoScale: List<String> = emptyList(),
) {
    val totalSec: Int get() = steps.sumOf { it.durationSec.coerceAtLeast(0) }

    /** Draft → Recipe. Unparseable numbers become 0, which validation then reports. */
    fun toRecipe(): Recipe {
        var t = 0
        val out = steps.map { s ->
            val start = t
            val end = t + s.durationSec.coerceAtLeast(0)
            t = end
            RecipeStep(
                kind = s.kind,
                title = s.title.trim().ifEmpty { s.kind.label },
                detail = s.detail.trim(),
                startSec = start,
                endSec = end,
                targetWaterG = parseAmount(s.pourTo)?.roundToInt(),
            )
        }
        return Recipe(
            id = id,
            name = name.trim(),
            author = author.trim(),
            brewer = brewer,
            doseG = parseAmount(dose) ?: 0.0,
            waterG = parseAmount(water) ?: 0.0,
            grind = grind.trim(),
            tempC = tempC,
            blurb = blurb.trim(),
            sourceNote = sourceNote,
            prep = prep.map { it.trim() }.filter { it.isNotEmpty() },
            tips = tips.map { it.trim() }.filter { it.isNotEmpty() },
            steps = out,
            accent = accent,
            isBuiltIn = false,
            fidelity = fidelity,
            sources = sources,
            basedOn = basedOn,
            level = level,
            needs = needs,
            prepNoScale = prepNoScale.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /** "+N g this pour": grams added since the previous target; null when this step has none. */
    fun pourDelta(index: Int): Int? {
        val here = parseAmount(steps.getOrNull(index)?.pourTo.orEmpty()) ?: return null
        var prev = 0.0
        for (i in index - 1 downTo 0) {
            val p = parseAmount(steps[i].pourTo)
            if (p != null) {
                prev = p
                break
            }
        }
        return (here - prev).roundToInt()
    }

    fun updateStep(index: Int, change: (StepDraft) -> StepDraft): RecipeDraft =
        copy(steps = steps.mapIndexed { i, s -> if (i == index) change(s) else s })

    fun moveStep(index: Int, by: Int): RecipeDraft {
        val to = index + by
        if (index !in steps.indices || to !in steps.indices) return this
        val list = steps.toMutableList()
        val moved = list.removeAt(index)
        list.add(to, moved)
        return copy(steps = list)
    }

    fun removeStep(index: Int): RecipeDraft = copy(steps = steps.filterIndexed { i, _ -> i != index })

    /** A new step after the last one, 30 s long; a pour's target is left for you. */
    fun addStep(kind: StepKind = StepKind.Pour): RecipeDraft =
        copy(steps = steps + StepDraft(nextKey(), kind, kind.label, "", 30, ""))

    /** The one-tap fix: the last step that has a target pours to the recipe's water. */
    fun setLastPour(waterG: Int): RecipeDraft {
        val i = steps.indexOfLast { parseAmount(it.pourTo) != null }
        return if (i < 0) this else updateStep(i) { it.copy(pourTo = waterG.toString()) }
    }

    /** Duration stepper: never below 1 s. */
    fun nudgeDuration(index: Int, by: Int): RecipeDraft =
        updateStep(index) { it.copy(durationSec = (it.durationSec + by).coerceAtLeast(1)) }

    companion object {
        /** Steps that put water on the scale: the editor always offers "pour to" for these. */
        val POUR_KINDS: Set<StepKind> = setOf(StepKind.Bloom, StepKind.Pour)

        /** The accent a brand-new recipe starts with. */
        const val NEW_RECIPE_ACCENT: Long = 0xFFE9A855

        private var keySeq = 0L

        @Synchronized
        fun nextKey(): Long = ++keySeq

        /** "custom-" + 8 lowercase hex. */
        fun newCustomId(random: Random = Random.Default): String =
            "custom-" + (1..8).joinToString("") { random.nextInt(16).toString(16) }

        private val AMOUNT = Regex("""^\d+(\.\d*)?$|^\.\d+$""")

        /** "18", "18.5", "1,5" → a number; "" or junk → null. */
        fun parseAmount(text: String): Double? {
            val t = text.trim().replace(',', '.')
            if (!AMOUNT.matches(t)) return null
            return t.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        fun from(r: Recipe): RecipeDraft = RecipeDraft(
            id = r.id,
            name = r.name,
            author = r.author,
            brewer = r.brewer,
            dose = grams(r.doseG),
            water = grams(r.waterG),
            tempC = r.tempC,
            grind = r.grind,
            blurb = r.blurb,
            prep = r.prep,
            tips = r.tips,
            steps = r.steps.map { s ->
                StepDraft(nextKey(), s.kind, s.title, s.detail, s.durationSec, s.targetWaterG?.toString().orEmpty())
            },
            accent = r.accent,
            fidelity = r.fidelity,
            sources = r.sources,
            basedOn = r.basedOn,
            sourceNote = r.sourceNote,
            level = r.level,
            needs = r.needs,
            prepNoScale = r.prepNoScale,
        )

        /** The blank V60-style template: one Bloom step, the numbers left for you to fill in. */
        fun blank(author: String, random: Random = Random.Default): RecipeDraft = RecipeDraft(
            id = newCustomId(random),
            name = "",
            author = author.trim().ifEmpty { "You" },
            brewer = Brewers.resolve("hario-v60-02"),
            dose = "",
            water = "",
            // The stepper needs somewhere to start; you set the real number (nobody's advice).
            tempC = 93,
            grind = "",
            blurb = "",
            prep = emptyList(),
            tips = emptyList(),
            steps = listOf(StepDraft(nextKey(), StepKind.Bloom, "Bloom", "", 45, "")),
            accent = NEW_RECIPE_ACCENT,
            fidelity = "custom",
            sources = emptyList(),
            basedOn = null,
            sourceNote = "",
        )

        /** A copy that is yours: new custom id, "(mine)", your name, and a note saying where it came from. */
        fun duplicate(original: Recipe, author: String, random: Random = Random.Default): RecipeDraft {
            val who = original.author.trim()
            return from(original).copy(
                id = newCustomId(random),
                name = "${original.name} (mine)",
                author = author.trim().ifEmpty { "You" },
                fidelity = "custom",
                basedOn = original.id,
                sources = original.sources,
                sourceNote = if (who.isEmpty()) "Based on ${original.name}." else "Based on $who's ${original.name}.",
            )
        }

        /**
         * Editing a built-in saves an override under the same id. Its numbers are now yours, so
         * the note says how to get the published version back.
         */
        fun overrideNote(original: Recipe): String {
            val who = original.author.trim()
            return "Your version of ${if (who.isEmpty()) "" else "$who's "}${original.name}. Reset to original for the published numbers."
        }
    }
}
