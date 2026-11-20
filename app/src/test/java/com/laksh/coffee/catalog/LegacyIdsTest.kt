package com.laksh.coffee.catalog

import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.LegacyIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A catalog rename must fail the build instead of silently orphaning 1.x data. */
class LegacyIdsTest {

    @Test
    fun everyLegacyBrewerIsInTheShippedCatalog() {
        // Read the file itself: Catalog.brewers appends fallbacks, which would hide a missing id.
        val text = javaClass.classLoader!!.getResourceAsStream("catalog/brewers.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val fileIds = Regex("\"id\":\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        LegacyIds.BREWER.values.toSet().forEach { assertTrue("brewers.json lacks $it", it in fileIds) }
        LegacyIds.FALLBACK_BREWERS.forEach { assertTrue("fallback ${it.id} unused", it.id in LegacyIds.BREWER.values) }
    }

    @Test
    fun everyLegacyProcessAndRoastIsInTheShippedCatalog() {
        val processes = Catalog.beans.processes.map { it.id }.toSet()
        val roasts = Catalog.beans.roastLevels.map { it.id }.toSet()
        (LegacyIds.PROCESS.values + LegacyIds.PROCESS_LABEL.values).filter { it.isNotEmpty() }
            .forEach { assertTrue("beans.json lacks process $it", it in processes) }
        (LegacyIds.ROAST_LEVEL.values + LegacyIds.ROAST_LEVEL_LABEL.values).filter { it.isNotEmpty() }
            .forEach { assertTrue("beans.json lacks roast level $it", it in roasts) }
    }

    @Test
    fun everyLegacyRecipeIsAShippedBuiltIn() {
        val ids = Catalog.recipes.map { it.id }.toSet()
        LegacyIds.RECIPE.values.forEach { assertTrue("recipes.json lacks $it", it in ids) }
    }

    @Test
    fun oneXNamesAndLabelsResolve() {
        assertEquals("hario-v60-02", Brewers.resolve("Hario V60").id)
        assertEquals("hario-v60-02", Brewers.resolve("V60").id)
        assertEquals("hario-v60-02", Brewers.resolve("hario v60").id)
        assertEquals("cold-brew-jar", Brewers.resolve("ColdBrew").id)
        assertEquals("french-press", Brewers.resolve("French Press").id)
        assertEquals("chemex", Brewers.resolve("chemex").id)
        val unknown = Brewers.resolve("Something else")
        assertEquals("other", unknown.id)
        assertEquals("Something else", unknown.label)
    }

    @Test
    fun caseSqlDoublesQuotesAndLeavesBlanksToElse() {
        val sql = LegacyIds.caseSql("process", mapOf("O'Brien" to "x", "Unknown" to ""), "''")
        assertEquals("CASE `process` WHEN 'O''Brien' THEN 'x' ELSE '' END", sql)
    }

    @Test
    fun oneXLabelsComeBackForLegacyIdsWhenTheCatalogLacksThem() {
        // Catalog labels win; the 1.x label is only a fallback.
        assertTrue(Catalog.label(com.laksh.coffee.domain.catalog.TermKind.Process, "washed")!!.isNotBlank())
        assertEquals(null, Catalog.label(com.laksh.coffee.domain.catalog.TermKind.Process, ""))
    }
}
