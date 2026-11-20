package com.laksh.coffee.ui

import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.BrewerFamilies
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.screens.brew.BrewWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V41-LAST-ANDROID: no-scale routes on recipe cards, the drip machine's brew and its short name. */
class V41LastTest {

    // ------------------------------------------------------------- I2 no-scale routes

    @Test
    fun recipeRoutesReadSpoonsAndMlWithoutAScale() {
        assertEquals("30 g → 500 g", NoScale.route(30.0, 500.0, BrewMethod.PourOver, hasScale = true))
        assertEquals("6 tbsp → 500 ml", NoScale.route(30.0, 500.0, BrewMethod.PourOver, hasScale = false))
        // The basket sets an espresso or moka dose: it keeps its grams, the water reads ml.
        assertEquals("18 g → 36 ml", NoScale.route(18.0, 36.0, BrewMethod.Espresso, hasScale = false))
        assertEquals("15 g → 150 ml", NoScale.route(15.0, 150.0, BrewMethod.Moka, hasScale = false))
        // The card's two halves are the same numbers.
        assertEquals("6" to "tbsp", NoScale.coffee(30.0, BrewMethod.PourOver, hasScale = false))
        assertEquals("500" to "ml", NoScale.water(500.0, hasScale = false))
    }

    // ------------------------------------------------------------- I3 the machine pours

    @Test
    fun onlyTheDripMachinePoursForYou() {
        assertTrue(BrewerFamilies.machinePours(Brewers.byId("drip-machine")))
        listOf("hario-v60-02", "chemex", "drip-bag", "phin", "aeropress", "french-press", "espresso-machine", "moka-pot")
            .forEach { id -> assertFalse(id, BrewerFamilies.machinePours(assertNotNullBrewer(id))) }
        assertFalse(BrewerFamilies.machinePours(null))
    }

    private fun assertNotNullBrewer(id: String) = Brewers.byId(id).also { assertNotNull(id, it) }

    @Test
    fun aDripMachineBrewHasNoPourTarget() {
        val r = ExpertRecipes.byId("allpress-drip-machine")!!
        assertTrue(BrewerFamilies.machinePours(r.brewer))
        val pour = r.steps.first { it.targetWaterG != null }
        // A V60 pour would read "Pour to 1250 ml"; the machine's step reads in its own words.
        assertEquals("Pour to 1250 ml", BrewWords.lead(pour, hasScale = false))
        assertEquals("Let it brew", BrewWords.lead(pour, hasScale = false, machine = true))
        assertEquals("Let it brew", BrewWords.lead(pour, hasScale = true, machine = true))
        assertEquals("NEXT STEP · Let it brew", BrewWords.banner(pour, hasScale = false, machine = true))
        r.steps.indices.forEach { i ->
            listOf(BrewWords.step(r, i, hasScale = true), BrewWords.step(r, i, hasScale = false)).forEach { t ->
                assertFalse(t, t.contains("1250"))
                assertFalse(t, t.contains("Pour to"))
            }
        }
        assertEquals("The machine is brewing", BrewWords.MACHINE_BREWING)
    }

    // ------------------------------------------------------------- I5 short names

    @Test
    fun theDripMachinesShortNameIsWholeWords() {
        val b = Brewers.byId("drip-machine")!!
        assertEquals("Drip machine", b.shortName)
        assertTrue(b.shortName.length <= 14)
        assertFalse(b.shortName.endsWith("…"))
        // The 1.x names are unchanged.
        assertEquals("V60", Brewers.byId("hario-v60-02")!!.shortName)
        assertEquals("French press", Brewers.byId("french-press")!!.shortName)
    }
}
