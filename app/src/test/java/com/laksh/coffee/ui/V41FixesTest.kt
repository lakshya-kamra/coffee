package com.laksh.coffee.ui

import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.BrewReport
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeStep
import com.laksh.coffee.domain.ReviewSections
import com.laksh.coffee.domain.ShotTarget
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.shotTimeStatus
import com.laksh.coffee.ui.components.spokenValue
import com.laksh.coffee.ui.screens.brew.BrewWords
import com.laksh.coffee.ui.screens.home.playfulTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V41-ANDROID-FIXES: the rules behind the v2.1 verifier items (A1, A3, A4, A5, A7). */
class V41FixesTest {

    private fun recipe(method: BrewMethod, steps: List<RecipeStep>, dose: Double = 18.0, water: Double = 45.0, tips: List<String> = emptyList()) =
        Recipe(
            id = "r", name = "R", author = "", brewer = Brewer(id = "x", label = "X", method = method),
            doseG = dose, waterG = water, grind = "", tempC = 93, blurb = "", sourceNote = "",
            prep = emptyList(), tips = tips, steps = steps, accent = 0xFF000000L,
        )

    // ------------------------------------------------------------- A1 no-scale words

    @Test
    fun recipeProseSaysMlAndSpoonsWithoutAScale() {
        assertEquals("Pour all 500 ml straight onto the grounds in one go.", NoScale.text("Pour all 500 g straight onto the grounds in one go."))
        assertEquals("Bloom to 60 ml", NoScale.text("Bloom to 60 g"))
        assertEquals("Full pressure. Chase 45 ml.", NoScale.text("Full pressure. Chase 45 g."))
        // Coffee, said as coffee: in level tablespoons, in halves.
        assertEquals("As you did last time: 3 level tablespoons of coffee to 250 ml of water.", NoScale.text("As you did last time: 15 g of coffee to 250 g of water."))
        assertEquals("Add ½ level tablespoon more coffee.", NoScale.text("Add 2 g more coffee."))
        assertEquals("1 level tablespoon of ground coffee", NoScale.text("5 grams of ground coffee"))
        // Temperatures, ratios, percentages and clocks are left alone.
        val same = "93 °C, 1:16, 60% of the water by 1:15."
        assertEquals(same, NoScale.text(same))
    }

    @Test
    fun theBannerAndTheScreenReaderSayTheStepsOwnMl() {
        val bloom = RecipeStep(StepKind.Bloom, "Bloom to 60 g", "Pour 60 g quickly.", 0, 45, 60)
        val pour = RecipeStep(StepKind.Pour, "Up to 300 g", "Hit 300 g by 1:15.", 45, 75, 304)
        val steep = RecipeStep(StepKind.Steep, "All 200 g", "Pour all 500 g straight onto the grounds.", 75, 240, null)
        assertEquals("Bloom to 60 g", BrewWords.lead(bloom))
        assertEquals("Bloom to 60 ml", BrewWords.lead(bloom, hasScale = false))
        // The exact target, the number the step card and the readout show too.
        assertEquals("Pour to 304 ml", BrewWords.lead(pour, hasScale = false))
        assertEquals("All 200 ml", BrewWords.lead(steep, hasScale = false))
        assertEquals("NEXT STEP · Pour to 304 ml", BrewWords.banner(pour, hasScale = false))
        assertEquals("NEXT STEP · Pour to 304 g", BrewWords.banner(pour))
        val r = recipe(BrewMethod.PourOver, listOf(bloom, pour, steep), dose = 15.0, water = 250.0)
        assertEquals(
            "Step 3 of 3, All 200 ml. Pour all 500 ml straight onto the grounds.",
            BrewWords.step(r, 2, hasScale = false),
        )
        assertEquals("Step 1 of 3, Bloom to 60 ml. Pour 60 ml quickly. Next at 0:45.", BrewWords.step(r, 0, hasScale = false))
        // With a scale nothing changes.
        assertEquals("Step 1 of 3, Bloom to 60 g. Pour 60 g quickly. Next at 0:45.", BrewWords.step(r, 0))
    }

    @Test
    fun noBuiltInStepSaysGramsWithoutAScale() {
        val grams = Regex("\\d\\s*g\\b")
        ExpertRecipes.all.forEach { r ->
            r.steps.forEachIndexed { i, s ->
                listOf(BrewWords.lead(s, false), BrewWords.prose(s.title, false), BrewWords.prose(s.detail, false), BrewWords.step(r, i, false))
                    .forEach { t -> assertFalse("${r.id} step $i: $t", grams.containsMatchIn(t)) }
            }
        }
    }

    @Test
    fun amountsReadInTheSameUnitsAsSetup() {
        assertEquals("250" to "g", NoScale.water(250.0, hasScale = true))
        assertEquals("250" to "ml", NoScale.water(250.0, hasScale = false))
        assertEquals("1250" to "ml", NoScale.water(1250.0, hasScale = false))
        assertEquals("15" to "g", NoScale.coffee(15.0, BrewMethod.PourOver, hasScale = true))
        assertEquals("3" to "tbsp", NoScale.coffee(15.0, BrewMethod.PourOver, hasScale = false))
        assertEquals("14" to "tbsp", NoScale.coffee(70.0, BrewMethod.ColdBrew, hasScale = false))
        // The basket sets an espresso dose: it stays in grams, as setup shows it.
        assertEquals("18" to "g", NoScale.coffee(18.0, BrewMethod.Espresso, hasScale = false))
        assertEquals(304, NoScale.ml(304))
        assertEquals(55, NoScale.ml(55))
        assertEquals(0, NoScale.ml(-3))
    }

    // ------------------------------------------------------------ A3 detail cells

    @Test
    fun drawdownCellsOnlyForBrewersThatHaveADrawdown() {
        listOf("drip-machine", "french-press", "espresso-machine").forEach { id ->
            val b = Catalog.brewer(id) ?: return@forEach
            assertFalse(id, ReviewSections.of(b).drawdown)
        }
        Catalog.brewer("v60")?.let { assertTrue(ReviewSections.of(it).drawdown) }
    }

    // ------------------------------------------------------------- A4 one shot target

    @Test
    fun oneShotTargetEverywhere() {
        val hedrick = ExpertRecipes.byId("hedrick-espresso")
        if (hedrick != null) {
            val t = ShotTarget.of(hedrick)
            assertEquals("about 35 s", t.label)
            // Not the recipe's 0:45, which has "Stop and taste" in it.
            assertEquals(45, hedrick.totalSec)
            assertEquals(35, ShotTarget.targetSec(hedrick))
            assertEquals(Status.Good to "In the band", shotTimeStatus(36, t))
            assertEquals(Status.Attention to "Slow by 0:10", shotTimeStatus(45, t))
            // The first read agrees with shot mode: a 0:36 shot is fine, a 0:45 one ran long.
            val log = { sec: Int -> BrewLogEntity(recipeId = hedrick.id, doseG = 18.0, waterG = 45.0, totalTimeSec = sec) }
            assertTrue(BrewReport.quickRead(log(36), hedrick).none { it.startsWith("Your shot ran") })
            assertTrue(
                BrewReport.quickRead(log(45), hedrick).joinToString(),
                BrewReport.quickRead(log(45), hedrick).any { it.startsWith("Your shot ran 0:45, longer than the recipe's 0:35.") },
            )
        }
        val classic = ExpertRecipes.byId("3fe-classic-double")
        if (classic != null) {
            assertEquals(28, ShotTarget.targetSec(classic))
            assertEquals(Status.Attention to "Fast by 0:03", shotTimeStatus(22, ShotTarget.of(classic)))
        }
        // A shot with no time of its own gets shot mode's default band.
        assertEquals(ShotTarget.DEFAULT, ShotTarget.of(recipe(BrewMethod.Espresso, emptyList())))
        // A filter brew keeps its whole time.
        val filter = recipe(BrewMethod.PourOver, listOf(RecipeStep(StepKind.Pour, "", "", 0, 180, 250)))
        assertEquals(180, ShotTarget.targetSec(filter))
        assertEquals(Status.Unrated to "No time", shotTimeStatus(0, ShotTarget.DEFAULT))
    }

    @Test
    fun aSingleNumberShotAllowsTwoSecondsEitherSide() {
        val t = ShotTarget(35, 35, stated = false)
        assertTrue(t.contains(33))
        assertTrue(t.contains(37))
        assertFalse(t.contains(38))
        assertEquals(0, t.offBy(37))
        assertEquals(3, t.offBy(38))
        assertEquals(-3, t.offBy(32))
        val band = ShotTarget(25, 30, stated = true)
        assertFalse(band.contains(31))
        assertEquals(1, band.offBy(31))
    }

    // --------------------------------------------------------------- A5 TalkBack

    @Test
    fun aTickingNumberIsReadAsOneValue() {
        assertEquals("201 degrees Fahrenheit", spokenValue("201", "°F"))
        assertEquals("94 degrees Celsius", spokenValue("94", "°C"))
        assertEquals("15 grams", spokenValue("15", "g"))
        assertEquals("1 gram", spokenValue("1", "g"))
        assertEquals("8 out of 10", spokenValue("8", "/10"))
        assertEquals("Not rated", spokenValue("—", "/10"))
        assertEquals("24 clicks", spokenValue("24", "clicks"))
    }

    // ------------------------------------------------------------------ A7 Today

    @Test
    fun aQuietDayIsNotABrandNewLog() {
        assertEquals("No coffee yet.", playfulTitle(0))
        assertEquals("None yet today.", playfulTitle(0, hasHistory = true))
        assertEquals("Two down.", playfulTitle(2, hasHistory = true))
        assertEquals("3 today. Steady on.", playfulTitle(3))
    }
}
