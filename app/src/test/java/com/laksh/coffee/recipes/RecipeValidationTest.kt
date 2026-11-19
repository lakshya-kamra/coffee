package com.laksh.coffee.recipes

import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeStep
import com.laksh.coffee.domain.RecipeValidation
import com.laksh.coffee.domain.RecipeValidation.Code
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.recipes.ExpertRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the recipe builder enforces before Save (android-spec §7.7). */
class RecipeValidationTest {

    private fun step(kind: StepKind, start: Int, end: Int, target: Int? = null) =
        RecipeStep(kind, kind.label, "", start, end, target)

    private fun recipe(
        name: String = "Mine",
        dose: Double = 15.0,
        water: Double = 250.0,
        temp: Int = 93,
        brewer: String = "hario-v60-02",
        steps: List<RecipeStep> = listOf(
            step(StepKind.Bloom, 0, 45, 50),
            step(StepKind.Pour, 45, 90, 150),
            step(StepKind.Pour, 90, 135, 250),
            step(StepKind.Drawdown, 135, 180),
        ),
    ) = Recipe(
        id = "custom-00000000", name = name, author = "You", brewer = Brewers.resolve(brewer),
        doseG = dose, waterG = water, grind = "Medium-fine", tempC = temp, blurb = "", sourceNote = "",
        prep = emptyList(), tips = emptyList(), steps = steps, accent = 0xFFE9A855, isBuiltIn = false,
    )

    private fun codes(r: Recipe) = RecipeValidation.check(r).map { it.code }

    @Test
    fun aSoundRecipeHasNoIssues() {
        assertEquals(emptyList<Code>(), codes(recipe()))
        assertFalse(RecipeValidation.hasBlocking(RecipeValidation.check(recipe())))
    }

    @Test
    fun everyBuiltInRecipePasses() {
        // The builder must be able to save an unchanged copy of anything in the library.
        ExpertRecipes.all.forEach { r ->
            assertEquals("${r.id} fails its own rules", emptyList<Code>(), codes(r))
        }
    }

    @Test
    fun nameLength() {
        assertEquals(listOf(Code.Name), codes(recipe(name = "  ")))
        assertEquals(emptyList<Code>(), codes(recipe(name = "x".repeat(60))))
        assertEquals(listOf(Code.Name), codes(recipe(name = "x".repeat(61))))
    }

    @Test
    fun doseWaterAndTempBounds() {
        assertEquals(listOf(Code.Dose), codes(recipe(dose = 0.5)))
        assertEquals(listOf(Code.Dose), codes(recipe(dose = 100.5)))
        assertEquals(emptyList<Code>(), codes(recipe(dose = 1.0)))
        assertEquals(emptyList<Code>(), codes(recipe(dose = 100.0)))
        assertTrue(Code.Water in codes(recipe(water = 9.0)))
        assertTrue(Code.Water in codes(recipe(water = 2001.0)))
        assertEquals(listOf(Code.Temp), codes(recipe(temp = 101)))
        assertEquals(listOf(Code.Temp), codes(recipe(temp = -1)))
        assertEquals(emptyList<Code>(), codes(recipe(temp = 0)))
        assertEquals(emptyList<Code>(), codes(recipe(temp = 100)))
        // An unparseable number arrives as 0 or NaN and is reported, never crashed on.
        assertEquals(listOf(Code.Dose), codes(recipe(dose = Double.NaN)))
    }

    @Test
    fun stepCount() {
        assertEquals(listOf(Code.StepCount), codes(recipe(steps = emptyList())))
        val thirty = (0 until 30).map { step(StepKind.Stir, it * 10, it * 10 + 10) }
        assertEquals(emptyList<Code>(), codes(recipe(steps = thirty)))
        val thirtyOne = (0 until 31).map { step(StepKind.Stir, it * 10, it * 10 + 10) }
        assertEquals(listOf(Code.StepCount), codes(recipe(steps = thirtyOne)))
    }

    @Test
    fun aStepMustLastASecond() {
        val issues = RecipeValidation.check(
            recipe(steps = listOf(step(StepKind.Bloom, 0, 45, 250), step(StepKind.Swirl, 45, 45))),
        )
        assertEquals(listOf(Code.StepDuration), issues.map { it.code })
        assertEquals(1, issues.single().stepIndex)
    }

    @Test
    fun anHourIsTheLimitExceptForColdBrew() {
        val long = listOf(step(StepKind.Pour, 0, 60, 250), step(StepKind.Steep, 60, 3601))
        assertEquals(listOf(Code.TotalTime), codes(recipe(steps = long)))
        assertEquals(emptyList<Code>(), codes(recipe(steps = listOf(step(StepKind.Pour, 0, 60, 250), step(StepKind.Steep, 60, 3600)))))

        // Twelve hours in the fridge is a normal cold brew; over a day is not.
        val overnight = listOf(step(StepKind.Pour, 0, 60, 250), step(StepKind.Steep, 60, 43_200))
        assertEquals(emptyList<Code>(), codes(recipe(brewer = "cold-brew-jar", steps = overnight)))
        val tooLong = listOf(step(StepKind.Pour, 0, 60, 250), step(StepKind.Steep, 60, 86_401))
        assertEquals(listOf(Code.TotalTime), codes(recipe(brewer = "cold-brew-jar", steps = tooLong)))
    }

    @Test
    fun pourTargetsOnlyGoUp() {
        val issues = RecipeValidation.check(
            recipe(
                steps = listOf(
                    step(StepKind.Bloom, 0, 45, 60),
                    step(StepKind.Pour, 45, 90, 40),
                    step(StepKind.Swirl, 90, 100),
                    step(StepKind.Pour, 100, 150, 250),
                ),
            ),
        )
        assertEquals(listOf(Code.PourOrder), issues.map { it.code })
        assertEquals(1, issues.single().stepIndex)
        // Equal targets are allowed: a pause on the scale is not a mistake.
        assertEquals(
            emptyList<Code>(),
            codes(recipe(steps = listOf(step(StepKind.Bloom, 0, 45, 250), step(StepKind.Pour, 45, 90, 250)))),
        )
    }

    @Test
    fun theLastPourMustReachTheWaterAndOffersTheFix() {
        val issues = RecipeValidation.check(
            recipe(
                water = 500.0,
                steps = listOf(step(StepKind.Bloom, 0, 45, 60), step(StepKind.Pour, 45, 120, 480), step(StepKind.Drawdown, 120, 200)),
            ),
        )
        val issue = issues.single()
        assertEquals(Code.LastPour, issue.code)
        assertEquals(1, issue.stepIndex)
        assertTrue(issue.blocking)
        val fix = issue.fix
        assertNotNull(fix)
        assertEquals("Set the last pour to 500 g", fix!!.label)
        assertEquals(500, fix.waterG)
    }

    @Test
    fun aRecipeWithNoPourTargetsIsNotAskedForOne() {
        // Espresso and immersion recipes often have no cumulative targets at all.
        val issues = RecipeValidation.check(recipe(steps = listOf(step(StepKind.Steep, 0, 240), step(StepKind.Press, 240, 270))))
        assertTrue(issues.isEmpty())
        assertNull(issues.firstOrNull { it.code == Code.LastPour })
    }

    @Test
    fun waterIsComparedInWholeGrams() {
        // 250.4 g of water and a last pour of 250 g are the same thing on a scale.
        assertEquals(emptyList<Code>(), codes(recipe(water = 250.4)))
    }

    @Test
    fun issuesComeInAFixedOrder() {
        val bad = recipe(name = "", dose = 0.0, water = 0.0, temp = 120, steps = emptyList())
        assertEquals(listOf(Code.Name, Code.Dose, Code.Water, Code.Temp, Code.StepCount), codes(bad))
    }
}
