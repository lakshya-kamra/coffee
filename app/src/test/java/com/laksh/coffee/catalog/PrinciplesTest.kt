package com.laksh.coffee.catalog

import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/principles.json: every statement sent to a model is short, sourced and well-formed. */
class PrinciplesTest {

    private val principles = Catalog.principles

    private val topics = setOf(
        "extraction", "grind", "agitation", "flow", "bed", "temperature", "ratio", "freshness", "water", "espresso", "recipe",
    )

    @Test
    fun theFileLoadsWithoutProblems() {
        assertTrue("shared/principles.json was not found or is empty", principles.size >= 20)
        assertTrue(Catalog.problems.toString(), Catalog.problems.none { it.startsWith("principles.json") })
    }

    @Test
    fun idsAreUniqueStableKebabCase() {
        val ids = principles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val kebab = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        ids.forEach { assertTrue(it, kebab.matches(it)) }
    }

    @Test
    fun everyPrincipleNamesAPersonAndLinksToASource() {
        principles.forEach { p ->
            assertTrue("${p.id}: who", p.who.isNotBlank())
            assertTrue("${p.id}: url ${p.url}", p.url.startsWith("https://") && p.url.length > 16 && ' ' !in p.url)
            assertTrue("${p.id}: note says where in the source", p.note.isNotBlank())
            // The id starts with the person's surname, so a list of ids reads as a list of people.
            val surname = p.who.substringBefore(" (").trim().substringAfterLast(' ').lowercase()
                .replace("é", "e")
            assertTrue("${p.id} should start with $surname", p.id.startsWith("$surname-"))
        }
    }

    @Test
    fun textIsShortPlainAndNamesNoUrl() {
        principles.forEach { p ->
            assertTrue("${p.id}: text is ${p.text.length} characters", p.text.length in 20..240)
            assertTrue("${p.id}: one line", '\n' !in p.text)
            assertTrue("${p.id}: no link in the text", "http" !in p.text)
        }
    }

    @Test
    fun topicsAndMethodsAreOnesTheAppKnows() {
        val methods = BrewMethod.entries.map { it.id }.toSet()
        principles.forEach { p ->
            assertTrue("${p.id}: topic ${p.topic}", p.topic in topics)
            p.appliesTo.forEach { assertTrue("${p.id}: method $it", it in methods) }
            assertEquals("${p.id}: appliesTo repeats a method", p.appliesTo.size, p.appliesTo.toSet().size)
        }
    }

    @Test
    fun anAggregatorOrAnUnreadPageIsNamedInTheNote() {
        // The two kinds of entry that were not read first-hand must say so.
        principles.filter { "youtube.com" in it.url }.forEach { p ->
            assertTrue("${p.id}: a video we could not read needs the write-up it was checked against", "aggregator" in p.note)
        }
        principles.filter { "baristahustle.com" in it.url }.forEach { p ->
            assertTrue(p.id, "not by opening it" in p.note)
        }
    }

    @Test
    fun everyFilterMethodGetsSomethingToReasonFrom() {
        listOf(BrewMethod.PourOver, BrewMethod.AeroPress, BrewMethod.FrenchPress, BrewMethod.Espresso).forEach { m ->
            val n = principles.count { it.appliesTo.isEmpty() || m.id in it.appliesTo }
            assertTrue("${m.id} has only $n principles", n >= 3)
        }
    }
}
