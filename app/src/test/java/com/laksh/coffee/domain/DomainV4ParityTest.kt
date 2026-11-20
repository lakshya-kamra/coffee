package com.laksh.coffee.domain

import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.ratioPhrase
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.ai.DataQuality
import com.laksh.coffee.domain.ai.FlagCode
import com.laksh.coffee.domain.catalog.Catalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * shared/fixtures/domain-v4-cases.json: the v4 domain functions give exactly these results. The
 * web app runs the same file (v4parity.test.ts), so both apps agree case for case.
 */
class DomainV4ParityTest {

    private val cases: JsonObject = run {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/domain-v4-cases.json")
        assertNotNull("fixtures/domain-v4-cases.json is not on the test classpath", stream)
        Json.parseToJsonElement(stream!!.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }

    private fun list(key: String): List<JsonObject> = cases.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.s(key: String): String = this[key].str().orEmpty()
    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    private fun brewer(id: String): Brewer = Catalog.brewer(id) ?: error("$id is not in brewers.json")

    /** A recipe with only what a case gives: brewer, numbers, one step for the total time. */
    private fun stub(
        brewer: Brewer,
        totalSec: Int = 180,
        doseG: Double = 15.0,
        waterG: Double = 250.0,
        id: String = "case",
        level: String = "",
        needs: List<String> = emptyList(),
        prep: List<String> = emptyList(),
        prepNoScale: List<String> = emptyList(),
    ) = Recipe(
        id = id, name = id, author = "", brewer = brewer, doseG = doseG, waterG = waterG, grind = "", tempC = 93, blurb = "",
        sourceNote = "", prep = prep, tips = emptyList(), steps = listOf(RecipeStep(StepKind.Pour, "", "", 0, totalSec, null)),
        accent = 0, isBuiltIn = true, level = level, needs = needs, prepNoScale = prepNoScale,
    )

    @Test
    fun proseTemperaturesUseTheSharedPattern() {
        assertEquals(cases.s("proseTempPattern"), Temperatures.PROSE_TEMP)
    }

    @Test
    fun noScaleAmounts() {
        list("noScaleAmounts").forEach { c ->
            val i = c.getValue("in").jsonObject
            val o = c.getValue("out").jsonObject
            val got = NoScale.amounts(i["doseG"]!!.jsonPrimitive.double, i["waterG"]!!.jsonPrimitive.double, BrewMethod.of(i.s("method")), i.s("form"))
            val want = NoScaleAmounts(
                coffeeTbsp = o["coffeeTbsp"]!!.jsonPrimitive.doubleOrNull,
                coffee = o.s("coffee"),
                coffeeLine = o.s("coffeeLine"),
                waterMl = o["waterMl"]!!.jsonPrimitive.int,
                mugs = o["mugs"]!!.jsonPrimitive.doubleOrNull,
                water = o.s("water"),
                basket = o["basket"].str(),
            )
            assertEquals(i.toString(), want, got)
        }
    }

    @Test
    fun noScalePrep() {
        list("noScalePrep").forEach { c ->
            val r = c.getValue("recipe").jsonObject
            val method = BrewMethod.of(r.s("method"))
            val recipe = stub(
                Brewer(id = "x", label = "X", method = method),
                doseG = r["doseG"]!!.jsonPrimitive.double, waterG = r["waterG"]!!.jsonPrimitive.double,
                prep = r.getValue("prep").strings(), prepNoScale = r.getValue("prepNoScale").strings(),
            )
            val unit = if (c["fahrenheit"]!!.jsonPrimitive.boolean) "f" else "c"
            assertEquals(r.toString(), c.getValue("out").strings(), NoScale.prep(recipe, unit, c.s("form")))
        }
    }

    @Test
    fun noScaleText() {
        // Added with v2.1.1 (A1); an older fixture without the key has nothing to check.
        if ("noScaleText" !in cases) return
        list("noScaleText").forEach { c ->
            val text = c["in"].str() ?: c.s("text")
            assertEquals(c.toString(), c.s("out"), NoScale.text(text))
        }
    }

    @Test
    fun servingsPreset() {
        list("servingsPreset").forEach { c ->
            val r = NoScale.servings(Catalog.recipe(c.s("recipe"))!!, c["mugs"]!!.jsonPrimitive.int)
            val o = c.getValue("out").jsonObject
            assertEquals(c.toString(), o["doseG"]!!.jsonPrimitive.double, r.doseG, 0.0)
            assertEquals(c.toString(), o["waterG"]!!.jsonPrimitive.double, r.waterG, 0.0)
        }
    }

    @Test
    fun renderTempsDurationsRatiosAndGrindWords() {
        list("renderTemps").forEach { c ->
            val i = c.getValue("in").jsonObject
            assertEquals(Temperatures.render(i.s("text"), i.s("unit")), c.s("out"), Temperatures.render(i.s("text"), i.s("unit")))
        }
        list("formatDuration").forEach { c -> assertEquals(c.s("out"), formatDuration(c["in"]!!.jsonPrimitive.int)) }
        list("ratioPhrase").forEach { c ->
            val i = c.getValue("in").jsonObject
            assertEquals(c.s("out"), ratioPhrase(i["doseG"]!!.jsonPrimitive.double, i["waterG"]!!.jsonPrimitive.double, i.s("method")))
        }
        list("grindReference").forEach { c -> assertEquals(c.s("in"), c["out"].str(), Glossary.grindReference(c.s("in"))) }
    }

    @Test
    fun familiesArtsSectionsAndLabels() {
        list("brewerFamily").forEach { c -> assertEquals(c.s("in"), c.s("out"), brewer(c.s("in")).family) }
        list("brewerArt").forEach { c -> assertEquals(c.s("in"), c.s("out"), brewer(c.s("in")).art.name) }
        list("reviewSections").forEach { c ->
            val id = c["in"].str()
            val s = ReviewSections.of(id?.let(::brewer))
            val o = c.getValue("out").jsonObject
            assertEquals(
                "$id",
                listOf(o["drawdown"]!!.jsonPrimitive.boolean, o["bed"]!!.jsonPrimitive.boolean, o["retained"]!!.jsonPrimitive.boolean, o.s("style")),
                listOf(s.drawdown, s.bed, s.retained, s.style.id),
            )
        }
        list("methodLabels").forEach { c ->
            val o = c.getValue("out").jsonObject
            val want = MethodLabels(o.s("water"), o.s("waterNote"), o.s("time"), o["longTimes"]!!.jsonPrimitive.boolean)
            assertEquals(c.s("in"), want, MethodLabels.of(BrewMethod.of(c.s("in"))))
        }
    }

    @Test
    fun defaultTempUnit() {
        list("defaultTempUnit").forEach { c -> assertEquals(c.s("in"), c.s("out"), Temperatures.defaultUnit(c.s("in"))) }
    }

    @Test
    fun matchesLogQuery() {
        val m = cases.getValue("matchesLogQuery").jsonObject
        val b = m.getValue("brew").jsonObject
        m.getValue("cases").jsonArray.map { it.jsonObject }.forEach { c ->
            val got = LogQuery.matches(
                c.s("query"), b.s("recipeName"), b.s("recipeAuthor"), b.s("beanLabel"), b.s("grinderLabel"), b.s("brewer"),
                b.s("notes"), b.s("nextTime"), b.getValue("descriptors").strings(),
            )
            assertEquals(c.s("query"), c["out"]!!.jsonPrimitive.boolean, got)
        }
    }

    @Test
    fun firstBrew() {
        val f = cases.getValue("firstBrew").jsonObject
        val recipes = f.getValue("recipes").jsonArray.map { it.jsonObject }.map { r ->
            stub(brewer(r.s("brewer")), id = r.s("id"), level = r.s("level"), needs = r.getValue("needs").strings())
        }
        f.getValue("cases").jsonArray.map { it.jsonObject }.forEach { c ->
            val s = c.getValue("settings").jsonObject
            val settings = FirstBrewSettings(
                ownedBrewerIds = s.getValue("ownedBrewerIds").strings(),
                hasScale = s["hasScale"]!!.jsonPrimitive.boolean,
                buysGround = s["buysGround"]!!.jsonPrimitive.boolean,
                experience = s.s("experience"),
            )
            val pick = FirstBrew.pick(recipes, settings, c["grinders"]!!.jsonPrimitive.int)
            val o = c.getValue("out").jsonObject
            assertEquals(s.toString(), listOf(o["recipe"].str(), o.getValue("noRecipeFor").strings()), listOf(pick.recipe?.id, pick.noRecipeFor))
        }
    }

    @Test
    fun quickReadOneCasePerTableCell() {
        list("quickRead").forEach { c ->
            val l = c.getValue("log").jsonObject
            val log = BrewLogEntity(
                faults = l["faults"]?.strings() ?: emptyList(),
                totalTimeSec = l["totalTimeSec"]?.jsonPrimitive?.int ?: 0,
                grindKind = l.s("grindKind"),
                bedShape = l["bedShape"].str()?.let { com.laksh.coffee.domain.backup.BackupIds.bed(it) } ?: BedShape.Unset,
                channeling = l["channeling"].str()?.let { com.laksh.coffee.domain.backup.BackupIds.channelling(it) } ?: Channeling.Unset,
                daysOffRoast = l["daysOffRoast"]?.jsonPrimitive?.intOrNull?.toLong(),
                extractionYield = l["extractionYield"]?.jsonPrimitive?.doubleOrNull,
            )
            val b = c["brewer"].str()?.let(::brewer)
            val r = (c["recipe"] as? JsonObject)?.let { stub(b ?: Brewer.unknown(""), it["totalSec"]!!.jsonPrimitive.int, it["doseG"]!!.jsonPrimitive.double) }
            val o = (c["opts"] as? JsonObject) ?: JsonObject(emptyMap())
            val opts = BrewReport.QuickReadOptions(
                brewer = b,
                hasScale = o["hasScale"]?.jsonPrimitive?.booleanOrNull ?: true,
                fahrenheit = o["fahrenheit"]?.jsonPrimitive?.booleanOrNull ?: false,
                timeIgnored = o["timeIgnored"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            assertEquals(c.s("name"), c.getValue("out").strings(), BrewReport.quickRead(log, r, opts))
        }
    }

    @Test
    fun grindMissingIsSkippedForPreGroundAndRecordedForBladeSeconds() {
        list("grindMissing").forEach { c ->
            val l = c.getValue("log").jsonObject
            val log = BrewLogEntity(
                id = 1, doseG = 15.0, waterG = 250.0, tempC = 93, totalTimeSec = 180,
                grindKind = l.s("grindKind"), grindSetting = l.s("grindSetting"),
                grindClicks = l["grindClicks"]?.jsonPrimitive?.intOrNull, grindValue = l["grindValue"]?.jsonPrimitive?.doubleOrNull,
            )
            val flags = DataQuality.check(log, null, null, null)
            assertEquals(c.s("name"), c["missing"]!!.jsonPrimitive.boolean, flags.any { it.code == FlagCode.GRIND_MISSING })
        }
    }

    @Test
    fun aDripMachineOwnersFirstBrewIsNeverAV60RecipeAgainstTheRealCatalog() {
        val pick = FirstBrew.pick(Catalog.recipes, FirstBrewSettings(listOf("drip-machine"), hasScale = false, buysGround = true, experience = "new"), 0)
        assertEquals("drip-machine", pick.recipe?.brewer?.id)
        Catalog.recipes.filter { it.brewer.id.startsWith("hario-v60") }.forEach { assertNotEquals(it.id, pick.recipe?.id) }
    }
}
