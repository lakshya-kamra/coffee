package com.laksh.coffee.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The rules a recipe must meet before the builder saves it. Pure: one issue per broken rule, in
 * the order the editor shows them. Every rule blocks Save; the last-pour rule also carries a
 * one-tap fix. The limits are the app's own sanity bounds, not anyone's brewing advice.
 */
object RecipeValidation {

    enum class Code { Name, Dose, Water, Temp, StepCount, StepDuration, TotalTime, PourOrder, LastPour }

    enum class Field { Name, Dose, Water, Temp, Steps }

    /** "Set the last pour to 500 g": the last step with a target pours to [waterG]. */
    data class Fix(val label: String, val waterG: Int)

    data class Issue(
        val code: Code,
        val field: Field,
        val message: String,
        /** Index into recipe.steps for step rules. */
        val stepIndex: Int? = null,
        val blocking: Boolean = true,
        val fix: Fix? = null,
    )

    const val NAME_MAX = 60
    const val DOSE_MIN = 1.0
    const val DOSE_MAX = 100.0
    const val WATER_MIN = 10.0
    const val WATER_MAX = 2000.0
    const val TEMP_MIN = 0
    const val TEMP_MAX = 100
    const val STEPS_MAX = 30
    const val TOTAL_MAX_SEC = 3600
    const val COLD_BREW_TOTAL_MAX_SEC = 86_400

    fun check(recipe: Recipe): List<Issue> {
        val issues = ArrayList<Issue>()
        val name = recipe.name.trim()
        if (name.isEmpty() || name.length > NAME_MAX) {
            issues += Issue(Code.Name, Field.Name, "Give it a name of 1 to $NAME_MAX characters.")
        }
        if (!(recipe.doseG >= DOSE_MIN && recipe.doseG <= DOSE_MAX)) {
            issues += Issue(Code.Dose, Field.Dose, "Dose needs to be between 1 and 100 g.")
        }
        if (!(recipe.waterG >= WATER_MIN && recipe.waterG <= WATER_MAX)) {
            issues += Issue(Code.Water, Field.Water, "Water needs to be between 10 and 2,000 g.")
        }
        if (recipe.tempC !in TEMP_MIN..TEMP_MAX) {
            issues += Issue(Code.Temp, Field.Temp, "Temperature needs to be between 0 and 100 °C.")
        }

        val steps = recipe.steps
        if (steps.isEmpty()) {
            issues += Issue(Code.StepCount, Field.Steps, "Add at least one step.")
        } else if (steps.size > STEPS_MAX) {
            issues += Issue(Code.StepCount, Field.Steps, "Keep it to $STEPS_MAX steps or fewer.")
        }
        steps.forEachIndexed { i, s ->
            if (s.endSec - s.startSec < 1) {
                issues += Issue(Code.StepDuration, Field.Steps, "Step ${i + 1} needs to last at least 1 second.", stepIndex = i)
            }
        }

        val coldBrew = recipe.brewer.method == BrewMethod.ColdBrew
        val limit = if (coldBrew) COLD_BREW_TOTAL_MAX_SEC else TOTAL_MAX_SEC
        if (recipe.totalSec > limit) {
            issues += Issue(
                Code.TotalTime,
                Field.Steps,
                if (coldBrew) "Cold brew can run up to 24 hours; this one is longer."
                else "The whole brew runs over an hour. Only cold brew can run longer.",
            )
        }

        // Pour targets are cumulative grams on the scale, so they can only go up.
        var lastIndex = -1
        var lastG = 0
        steps.forEachIndexed { i, s ->
            val t = s.targetWaterG ?: return@forEachIndexed
            if (lastIndex >= 0 && t < lastG) {
                issues += Issue(
                    Code.PourOrder,
                    Field.Steps,
                    "Step ${i + 1} pours to ${g(t)}, less than step ${lastIndex + 1} (${g(lastG)}). The scale only goes up.",
                    stepIndex = i,
                )
            }
            lastIndex = i
            lastG = t
        }
        // Targets are whole grams, so the recipe's water is compared rounded.
        val water = recipe.waterG.roundToInt()
        if (lastIndex >= 0 && recipe.waterG > 0 && lastG != water) {
            issues += Issue(
                Code.LastPour,
                Field.Steps,
                "The last pour ends at ${g(lastG)}, but the recipe uses ${g(water)}.",
                stepIndex = lastIndex,
                fix = Fix("Set the last pour to ${g(water)}", water),
            )
        }
        return issues
    }

    fun hasBlocking(issues: List<Issue>): Boolean = issues.any { it.blocking }

    private fun g(v: Int): String = String.format(Locale.US, "%,d g", v)
}
