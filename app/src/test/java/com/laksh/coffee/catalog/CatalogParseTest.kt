package com.laksh.coffee.catalog

import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.BaseUrlCheck
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.grind.DialFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/ is read from the test classpath (app/build.gradle.kts), exactly as the APK packs it. */
class CatalogParseTest {

    private val kebab = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val methodIds = BrewMethod.entries.map { it.id }.toSet()

    private fun raw(path: String): JsonObject {
        val text = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parseToJsonElement(text) as JsonObject
    }

    private fun assertIds(what: String, ids: List<String>) {
        assertTrue("$what is empty", ids.isNotEmpty())
        assertEquals("$what has duplicate ids", ids.size, ids.toSet().size)
        ids.forEach { assertTrue("$what id '$it' is not lowercase-kebab", kebab.matches(it)) }
    }

    private fun whole(v: Double) = v == Math.floor(v)

    @Test
    fun everyFileParsesWithNoProblems() {
        Catalog.preloadAll()
        assertTrue("Catalog problems: ${Catalog.problems}", Catalog.problems.isEmpty())
        listOf(
            "catalog/grinders.json", "catalog/roasters.json", "catalog/origins.json", "catalog/beans.json",
            "catalog/brewers.json", "catalog/water.json", "catalog/providers.json", "recipes.json",
        ).forEach { path ->
            val root = raw(path)
            assertEquals("$path version", 1, root.getValue("version").jsonPrimitive.int)
            assertNotNull("$path updated", root["updated"])
        }
    }

    @Test
    fun idsAreUniqueAndKebab() {
        val beans = Catalog.beans
        assertIds("grinders", Catalog.grinders.map { it.id })
        assertIds("roasters", Catalog.roasters.map { it.id })
        assertIds("origins", Catalog.origins.map { it.id })
        assertIds("species", beans.species.map { it.id })
        assertIds("processes", beans.processes.map { it.id })
        assertIds("varietals", beans.varietals.map { it.id })
        assertIds("lotTypes", beans.lotTypes.map { it.id })
        assertIds("decafMethods", beans.decafMethods.map { it.id })
        assertIds("roastLevels", beans.roastLevels.map { it.id })
        assertIds("roastIntents", beans.roastIntents.map { it.id })
        assertIds("brewers", Catalog.brewers.map { it.id })
        assertIds("water", Catalog.water.map { it.id })
        assertIds("providers", Catalog.providers.map { it.id })
        assertIds("recipes", Catalog.recipes.map { it.id })
    }

    @Test
    fun crossReferencesHold() {
        val beans = Catalog.beans
        val processIds = beans.processes.map { it.id }.toSet()
        val varietalIds = beans.varietals.map { it.id }.toSet()
        val speciesIds = beans.species.map { it.id }.toSet()
        Catalog.origins.forEach { o ->
            o.commonProcesses.forEach { assertTrue("origin ${o.id}: process $it", it in processIds) }
            o.commonVarietals.forEach { assertTrue("origin ${o.id}: varietal $it", it in varietalIds) }
        }
        beans.varietals.forEach { v ->
            if (v.species.isNotEmpty()) assertTrue("varietal ${v.id}: species ${v.species}", v.species in speciesIds)
        }
        val brewersFile = raw("catalog/brewers.json").getValue("brewers")
        (brewersFile as kotlinx.serialization.json.JsonArray).forEach { b ->
            val method = (b as JsonObject)["method"]?.jsonPrimitive?.content
            assertTrue("brewer ${b["id"]}: method $method", method in methodIds)
        }
    }

    @Test
    fun grinderScalesAreUsable() {
        Catalog.grinders.forEach { g ->
            val s = g.setting
            assertTrue("${g.id}: burr ${g.burr}", g.burr in setOf("conical", "flat", "ghost", "blade", ""))
            assertTrue("${g.id}: style ${s.style}", s.style in setOf("clicks", "numbers", "stepless"))
            g.ranges.keys.forEach { assertTrue("${g.id}: range key $it", it in methodIds) }
            g.ranges.values.forEach { assertTrue("${g.id}: range ${it.from}-${it.to}", it.from <= it.to) }
            assertTrue("${g.id}: min ${s.min} max ${s.max}", s.min < s.max && s.step > 0)
            if (s.style == "clicks") {
                // The stored unit is total clicks from zero: a dial reading (6.0-8.0) here would be off by ×10.
                assertTrue("${g.id}: clicks bounds must be whole", whole(s.min) && whole(s.max))
                assertEquals("${g.id}: clicks step", 1.0, s.step, 0.0)
                g.ranges.values.forEach { assertTrue("${g.id}: clicks ranges must be whole", whole(it.from) && whole(it.to)) }
            }
            assertNotNull("${g.id}: dialFormat '${s.dialFormat}'", DialFormat.decode(s.dialFormat, s.clicksPerRotation))
        }
    }

    @Test
    fun theKUltraIsAHundredClicksATurnReadOnANumberedDial() {
        val k = Catalog.grinder("1zpresso-k-ultra")!!
        assertEquals(100, k.setting.clicksPerRotation)
        assertEquals(20.0, k.setting.micronsPerStep!!, 0.0)
        assertEquals("number-tick:10", k.setting.dialFormat)
        assertEquals("high", k.confidence)
        // Filter at 60-90 clicks is nowhere near the espresso range: the old "under one turn" rule would have distrusted it.
        assertTrue(k.ranges.getValue("espresso").to < 60.0)
    }

    @Test
    fun providersAreUsable() {
        Catalog.providers.forEach { p ->
            if (p.baseUrl.startsWith("https://")) {
                assertTrue("${p.id}: ${p.baseUrl}", BaseUrl.check(p.baseUrl) is BaseUrlCheck.Valid)
            }
            if (p.models.isNotEmpty()) {
                assertEquals("${p.id}: exactly one default model", 1, p.models.count { it.default })
            }
        }
    }
}
