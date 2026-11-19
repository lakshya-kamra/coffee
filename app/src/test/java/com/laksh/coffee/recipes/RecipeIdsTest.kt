package com.laksh.coffee.recipes

import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.recipes.ExpertRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Old logs point at recipe ids; these must resolve forever, with numbers that add up. */
class RecipeIdsTest {

    @Test
    fun legacyIdsResolveInOrderAtTheHead() {
        val all = ExpertRecipes.all
        ExpertRecipes.LEGACY_IDS.forEach { assertNotNull("$it missing", ExpertRecipes.byId(it)) }
        assertEquals(ExpertRecipes.LEGACY_IDS, all.take(ExpertRecipes.LEGACY_IDS.size).map { it.id })
    }

    @Test
    fun idsAreUniqueAndNeverCustom() {
        val ids = ExpertRecipes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertFalse("$it looks like a user recipe", it.startsWith("custom-")) }
    }

    @Test
    fun everyBuiltInAddsUp() {
        ExpertRecipes.all.forEach { r ->
            assertTrue("${r.id}: no steps", r.steps.isNotEmpty())
            assertTrue("${r.id}: built in", r.isBuiltIn)
            assertTrue("${r.id}: fidelity '${r.fidelity}'", r.fidelity in setOf("published", "adapted", "community"))
            assertNotNull("${r.id}: brewer ${r.brewer.id} not in the catalog", Catalog.brewer(r.brewer.id))
            r.steps.zipWithNext().forEach { (a, b) ->
                assertEquals("${r.id}: step '${b.title}' must start when '${a.title}' ends", a.endSec, b.startSec)
            }
            val targets = r.steps.mapNotNull { it.targetWaterG }
            assertEquals("${r.id}: pour targets must not go down", targets.sorted(), targets)
            assertEquals("${r.id}: last pour target must equal the water", r.waterG, targets.max().toDouble(), 0.0)
        }
    }

    @Test
    fun newRecipesCiteASource() {
        ExpertRecipes.all.drop(ExpertRecipes.LEGACY_IDS.size).forEach { r ->
            assertTrue("${r.id}: needs a source with a URL", r.sources.any { it.url.startsWith("https://") })
        }
    }

    @Test
    fun goldenNumbers() {
        val v60 = ExpertRecipes.byId("hoffmann-v60")!!
        assertEquals(30.0, v60.doseG, 0.0)
        assertEquals(500.0, v60.waterG, 0.0)
        assertEquals(100, v60.tempC)
        assertEquals(210, v60.totalSec)
        assertEquals(6, v60.steps.size)
        assertEquals("hario-v60-02", v60.brewer.id)
        assertEquals(0xFFE9A855, v60.accent)

        val kasuya = ExpertRecipes.byId("kasuya-46")!!
        assertEquals(20.0, kasuya.doseG, 0.0)
        assertEquals(300.0, kasuya.waterG, 0.0)
        assertEquals(92, kasuya.tempC)
        assertEquals(210, kasuya.totalSec)
        // Kasuya's own split, corrected 2026-11: 50 g then 70 g.
        assertEquals(50, kasuya.steps.first().targetWaterG)
    }
}
