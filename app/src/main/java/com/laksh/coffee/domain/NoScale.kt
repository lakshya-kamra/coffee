package com.laksh.coffee.domain

import com.laksh.coffee.core.halves
import kotlin.math.roundToInt

/** What [NoScale.amounts] gives: spoons, ml and mugs for a dose and water in grams. */
data class NoScaleAmounts(
    /** Level tablespoons, in halves, at least 1; null for espresso and moka (the basket sets the dose). */
    val coffeeTbsp: Double?,
    /** "6 level tablespoons"; "" for espresso and moka. */
    val coffee: String,
    /** "6 level tablespoons of coffee" ("… of beans" before a blade grinder); the basket line for espresso and moka. */
    val coffeeLine: String,
    val waterMl: Int,
    /** In halves, at least 1; null under 200 ml. */
    val mugs: Double?,
    /** "500 ml (2 mugs)" or "150 ml". */
    val water: String,
    /** Espresso and moka: how to fill the basket instead of spoons. */
    val basket: String?,
)

/**
 * No-scale brewing (v4 §4.3, D10): spoons, ml and mugs rendered from the stored grams. Grams stay
 * the truth; nothing here is ever saved. Web twin: domain/core/noScale.ts, same results.
 */
object NoScale {

    /** Grams of ground coffee in one level tablespoon (shared/schema/units.md). */
    const val GROUND_COFFEE_G_PER_LEVEL_TBSP = 5.0

    /** One mug, for "500 ml (2 mugs)" and the servings presets. */
    const val ML_PER_MUG = 250

    private const val MUGS_FROM_ML = 200
    private const val ESPRESSO_BASKET = "Fill the basket level, then tamp."
    private const val MOKA_BASKET = "Fill the basket level; don't press it down. Water to just below the valve."

    private fun toHalves(v: Double): Double = (v * 2).roundToInt() / 2.0

    /**
     * [form] "whole" means beans measured before grinding (a blade grinder at home), so the line
     * reads "of beans"; anything else is coffee already ground.
     */
    fun amounts(doseG: Double, waterG: Double, method: BrewMethod, form: String = ""): NoScaleAmounts {
        val waterMl = waterG.roundToInt().coerceAtLeast(0)
        val mugs = if (waterMl >= MUGS_FROM_ML) toHalves(waterMl / ML_PER_MUG.toDouble()).coerceAtLeast(1.0) else null
        val water = if (mugs == null) "$waterMl ml" else "$waterMl ml (${halves(mugs)} ${if (mugs == 1.0) "mug" else "mugs"})"
        val basket = when (method) {
            BrewMethod.Espresso -> ESPRESSO_BASKET
            BrewMethod.Moka -> MOKA_BASKET
            else -> null
        }
        if (basket != null) return NoScaleAmounts(null, "", basket, waterMl, mugs, water, basket)
        val tbsp = toHalves(doseG.coerceAtLeast(0.0) / GROUND_COFFEE_G_PER_LEVEL_TBSP).coerceAtLeast(1.0)
        val coffee = "${halves(tbsp)} level ${if (tbsp == 1.0) "tablespoon" else "tablespoons"}"
        return NoScaleAmounts(tbsp, coffee, "$coffee ${if (form == "whole") "of beans" else "of coffee"}", waterMl, mugs, water, null)
    }

    /** A prep line that needs a scale: weighing, zeroing or taring. */
    val SCALE_LINE = Regex("\\b(scales?|zero(?:ed)?|tare|weigh)\\b", RegexOption.IGNORE_CASE)

    private val GENERIC = listOf("Measure {water} of water in a jug first.", "Measure {coffee}.")

    /**
     * The prep list for someone without a scale: the lines that mention one are replaced, where
     * the first of them stood, by the recipe's prepNoScale, else by the basket line (espresso,
     * moka), else by the two generic measuring lines. Temperatures read in [tempUnit] ("c" | "f").
     */
    fun prep(recipe: Recipe, tempUnit: String, form: String = ""): List<String> {
        val a = amounts(recipe.doseG, recipe.waterG, recipe.brewer.method, form)
        fun fill(s: String) = s.replace("{coffee}", a.coffeeLine).replace("{water}", a.water)
        val replacement = when {
            recipe.prepNoScale.isNotEmpty() -> recipe.prepNoScale.map(::fill)
            a.basket != null -> listOf(a.basket)
            else -> GENERIC.map(::fill)
        }
        val out = mutableListOf<String>()
        var replaced = false
        for (line in recipe.prep) {
            if (!SCALE_LINE.containsMatchIn(line)) {
                out += line
            } else if (!replaced) {
                out += replacement
                replaced = true
            }
        }
        return out.map { Temperatures.render(it, tempUnit) }
    }

    /** "1 mug", "2 mugs", "3 mugs": 250 ml of water each, the dose from the recipe's ratio. */
    fun servings(recipe: Recipe, mugs: Int): Recipe = recipe.scaledToWater(ML_PER_MUG * mugs.toDouble())
}
