package com.laksh.coffee.domain.ai

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.Principle
import com.laksh.coffee.domain.recipes.ExpertRecipes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt version 2: the grinder's own scale, the full coffee, brewer, water, photos, units and principles. */
class AnalysisPromptTest {

    private val v60: Recipe = ExpertRecipes.byId("hoffmann-v60")!!
    private val kasuya: Recipe = ExpertRecipes.byId("kasuya-46")!!

    private val kUltra = GrinderEntity(
        id = 1, brand = "1Zpresso", model = "K-Ultra", burrType = BurrType.Conical, burrSizeMm = "48",
        settingStyle = SettingStyle.Clicks, clicksPerRotation = 100, micronsPerClick = 20.0,
        lowerIsFiner = true, maxSetting = 200.0, catalogId = "1zpresso-k-ultra",
    )

    private val encore = GrinderEntity(
        id = 2, brand = "Baratza", model = "Encore", settingStyle = SettingStyle.Numbers,
        clicksPerRotation = 0, lowerIsFiner = true, minSetting = 1.0, maxSetting = 40.0, step = 1.0,
        catalogId = "baratza-encore",
    )

    private val bean = BeanEntity(
        id = 5,
        name = "Halo Beriti",
        roaster = "",
        roasterId = Catalog.roasters.first().id,
        origin = "",
        originId = Catalog.origins.first { it.id == "ethiopia" }.id,
        region = "Yirgacheffe",
        farm = "Halo Beriti station lot 4",
        producer = "Smallholders",
        washingStation = "Halo Beriti",
        lotTypeId = Catalog.beans.lotTypes.first().id,
        speciesId = Catalog.beans.species.first().id,
        varietalIds = listOf(Catalog.beans.varietals.first().id),
        varietal = "Kurume, 74110",
        processId = "washed",
        roastLevelId = "light",
        roastIntentId = Catalog.beans.roastIntents.first().id,
        altitudeMasl = "2100",
        harvest = "2025/26",
        restDays = 10,
        tastingNotes = "Jasmine, peach",
        notes = "Second bag",
        isDecaf = false,
    )

    private fun log(
        id: Long = 10,
        recipe: Recipe = v60,
        grinder: GrinderEntity? = kUltra,
        clicks: Int? = 75,
        value: Double? = null,
        setting: String = "75 clicks (7 turns + 5)",
        waterId: String? = null,
        startedAt: Long = 1_789_373_520_000L,
        bed: BedShape = BedShape.Flat,
        // Fixed, as a saved brew's is: the hash reads it.
        uid: String = "brew-$id",
        beanId: Long? = bean.id,
    ) = BrewLogEntity(
        id = id,
        uid = uid,
        startedAt = startedAt,
        recipeId = recipe.id,
        recipeName = recipe.name,
        recipeAuthor = recipe.author,
        brewer = recipe.brewer.label,
        brewerId = recipe.brewer.id,
        beanId = beanId,
        beanLabel = "Halo Beriti",
        daysOffRoast = 14,
        grinderId = grinder?.id,
        grinderLabel = grinder?.display.orEmpty(),
        grindSetting = setting,
        grindClicks = clicks,
        grindValue = value,
        doseG = recipe.doseG,
        waterG = recipe.waterG,
        tempC = 96,
        totalTimeSec = recipe.totalSec,
        drawdownSec = 60,
        bedShape = bed,
        taste = TasteProfile(5, 7, 3, 6, 8, 7, 7),
        overall = 8,
        waterId = waterId,
    )

    private fun input(
        l: BrewLogEntity = log(),
        recipe: Recipe = v60,
        grinder: GrinderEntity? = kUltra,
        history: List<BrewLogEntity> = emptyList(),
        units: String = "c",
        principles: List<Principle> = emptyList(),
        photos: List<PhotoMeta> = emptyList(),
    ) = BrewPromptInput(
        log = l,
        recipe = recipe,
        bean = bean,
        grinder = grinder,
        history = history,
        recipes = mapOf(v60.id to v60, kasuya.id to kasuya),
        beans = mapOf(bean.id to bean),
        grinders = listOfNotNull(kUltra, encore).associateBy { it.id },
        photos = photos,
        units = units,
        principles = principles,
    )

    /** The JSON object on the line after [title]. */
    private fun block(user: String, title: String): JsonObject {
        val line = user.substringAfter("$title\n").substringBefore("\n")
        return Json.parseToJsonElement(line).jsonObject
    }

    private fun principle(id: String, topic: String, who: String = "Someone", appliesTo: List<String> = emptyList()) =
        Principle(id = id, topic = topic, appliesTo = appliesTo, text = "text of $id", who = who, url = "https://example.com/$id")

    // -------------------------------------------------------------- version

    @Test
    fun thisIsPromptVersionTwo() {
        assertEquals(2, AnalysisPrompt.VERSION)
    }

    // -------------------------------------------------------------- grinder

    @Test
    fun aNumberedClickDialIsDescribedTheWayItsOwnerReadsIt() {
        val g = block(AnalysisPrompt.brew(input()).user, "GRINDER")
        assertEquals("1Zpresso K-Ultra", g["name"]!!.jsonPrimitive.content)
        assertEquals("1zpresso-k-ultra", g["catalogId"]!!.jsonPrimitive.content)
        assertEquals("Conical, 48 mm", g["burrs"]!!.jsonPrimitive.content)
        assertEquals("75", g["currentClicks"]!!.jsonPrimitive.content)
        // 75 clicks from zero is "7.5" on the ring, not "7 turns + 5".
        assertEquals("7.5", g["currentOnDial"]!!.jsonPrimitive.content)

        val scale = g["scale"]!!.jsonObject
        assertEquals("clicks", scale["adjustsBy"]!!.jsonPrimitive.content)
        assertEquals("clicks", scale["unit"]!!.jsonPrimitive.content)
        assertEquals("FEWER clicks is finer", scale["finerDirection"]!!.jsonPrimitive.content)
        assertEquals("100", scale["clicksPerTurn"]!!.jsonPrimitive.content)
        assertEquals("20", scale["micronsPerStep"]!!.jsonPrimitive.content)
        assertEquals("number-tick:10", scale["dialFormat"]!!.jsonPrimitive.content)
        // The catalog's own words for how the dial reads and what to type.
        val catalog = Catalog.grinder("1zpresso-k-ultra")!!.setting
        assertEquals(catalog.dialReading, scale["howTheDialReads"]!!.jsonPrimitive.content)
        assertEquals(catalog.entryHint, scale["whatTheyType"]!!.jsonPrimitive.content)
    }

    @Test
    fun moveSizesComeInTheGrindersUnitsAndSayTheyAreTheAppsRuleOfThumb() {
        val moves = block(AnalysisPrompt.brew(input()).user, "GRINDER")["moveSizes"]!!.jsonObject
        assertEquals("2", moves["nudge"]!!.jsonPrimitive.content)
        assertEquals("6", moves["clearMiss"]!!.jsonPrimitive.content)
        assertEquals("13", moves["maxAtOnce"]!!.jsonPrimitive.content)
        assertEquals("20 µm per click (app rule of thumb)", moves["basis"]!!.jsonPrimitive.content)
    }

    @Test
    fun theStartingRangeNamesItsMethodAndWhoItComesFrom() {
        val range = block(AnalysisPrompt.brew(input()).user, "GRINDER")["startingRange"]!!.jsonObject
        val catalog = Catalog.grinder("1zpresso-k-ultra")!!
        assertEquals("pourover", range["method"]!!.jsonPrimitive.content)
        assertEquals(catalog.ranges.getValue("pourover").from, range["from"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(catalog.ranges.getValue("pourover").to, range["to"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val who = range["from_who"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(who.isNotEmpty())
        assertEquals(who.distinct(), who)
        assertTrue(who.all { w -> catalog.rangeSources.any { it.who == w } })
    }

    @Test
    fun theExampleShowsWhichWayIsFinerOnTheDial() {
        val g = block(AnalysisPrompt.brew(input()).user, "GRINDER")
        assertEquals("6 clicks finer from 75 (dial 7.5) is 69 (dial 6.9)", g["example"]!!.jsonPrimitive.content)
    }

    @Test
    fun aClickGrindersTextSnapshotIsNeverSent() {
        val older = log(id = 9, clicks = 80, setting = "80 clicks (8 turns + 0)", startedAt = 1_789_287_120_000L)
        val built = AnalysisPrompt.brew(input(history = listOf(older)))
        // "75 clicks (7 turns + 5)" was written with the 1.x ten-click turn; the scale now says otherwise.
        assertFalse(built.user, built.user.contains("grindSetting"))
        assertFalse(built.user, built.user.contains("7 turns + 5"))
        assertFalse(built.user, built.user.contains("8 turns + 0"))
        assertTrue(built.user.contains("\"grindClicks\":75"))
        assertTrue(built.user.contains("\"grindClicks\":80"))
        assertEquals(75, built.grindAnchor)
        assertNull(built.valueAnchor)
    }

    @Test
    fun aNumberedDialSendsItsDialNumberAndAnExampleInDialUnits() {
        val l = log(grinder = encore, clicks = null, value = 14.0, setting = "14")
        val built = AnalysisPrompt.brew(input(l = l, grinder = encore))
        val g = block(built.user, "GRINDER")
        assertEquals("numbered dial", g["scale"]!!.jsonObject["adjustsBy"]!!.jsonPrimitive.content)
        assertEquals("LOWER settings are finer", g["scale"]!!.jsonObject["finerDirection"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, g["scale"]!!.jsonObject["clicksPerTurn"])
        assertEquals("14", g["currentSetting"]!!.jsonPrimitive.content)
        assertFalse(g.containsKey("currentClicks"))
        assertEquals(14.0, built.valueAnchor!!, 0.0)
        assertNull(built.grindAnchor)
        // The Encore has no microns in the catalog, so its move sizes are a share of the pour-over range.
        val moves = g["moveSizes"]!!.jsonObject
        assertTrue(moves["basis"]!!.jsonPrimitive.content, moves["basis"]!!.jsonPrimitive.content.contains("pour-over starting range"))
        val clearMiss = moves["clearMiss"]!!.jsonPrimitive.content.toDouble()
        assertEquals("${num(clearMiss)} finer from 14 is ${num(14 - clearMiss)}", g["example"]!!.jsonPrimitive.content)
        // For a dial, the number and the typed text both go in the brew row.
        assertTrue(built.user.contains("\"grindValue\":14"))
        assertTrue(built.user.contains("\"grindSetting\":\"14\""))
    }

    private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    @Test
    fun aDistrustedDialNumberIsAnchoredOnTheLastTrustedBrew() {
        val older = log(id = 9, grinder = encore, clicks = null, value = 16.0, setting = "16", startedAt = 1_789_287_120_000L)
        val l = log(grinder = encore, clicks = null, value = null, setting = "")
        val built = AnalysisPrompt.brew(input(l = l, grinder = encore, history = listOf(older)))
        val g = block(built.user, "GRINDER")
        assertEquals(JsonNull, g["currentSetting"])
        assertEquals("16", g["lastTrustedSetting"]!!.jsonPrimitive.content)
        assertTrue(g["lastTrustedFrom"]!!.jsonPrimitive.content.startsWith("brew #9, "))
        assertEquals(16.0, built.valueAnchor!!, 0.0)
    }

    @Test
    fun aRowSavedAtTenClicksATurnKeepsItsOneFullTurn() {
        // The owner's 1.x K-Ultra row: nothing changes for it until they accept the catalog's numbers.
        val legacy = kUltra.copy(clicksPerRotation = 10, micronsPerClick = null, catalogId = null, maxSetting = 40.0)
        val built = AnalysisPrompt.brew(input(l = log(clicks = 77), grinder = legacy))
        val g = block(built.user, "GRINDER")
        assertEquals("7.7", g["currentOnDial"]!!.jsonPrimitive.content)
        assertEquals("one full turn finer from 77 (dial 7.7) is 67 (dial 6.7)", g["example"]!!.jsonPrimitive.content)
        assertEquals("a full turn of 10 clicks is a clear miss (app rule of thumb)", g["moveSizes"]!!.jsonObject["basis"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, g["startingRange"])
    }

    @Test
    fun theSystemPromptTellsTheModelToUseTheGrindersOwnScale() {
        val s = AnalysisPrompt.SYSTEM_BREW
        assertTrue(s.contains("grindClicksFrom/To for click grinders, grindValueFrom/To for numbered or stepless dials"))
        assertTrue(s.contains("grinder.scale.finerDirection"))
        assertTrue(s.contains("grinder.moveSizes"))
        assertTrue(s.contains("never more than maxAtOnce"))
        assertTrue(s.contains("grinder.startingRange is a sanity check, not a target"))
        assertTrue(s.contains("Never quote a principle that isn't listed."))
        assertTrue(s.contains("never read a grind setting off a photo"))
        assertFalse(s.contains("one full turn"))
        assertTrue(AnalysisPrompt.SYSTEM_TREND.contains("moveSizes"))
        assertTrue(AnalysisPrompt.brew(input()).user.contains("\"grindValueFrom\": null"))
    }

    // ------------------------------------------------ coffee, brewer, water

    @Test
    fun theCoffeeBlockCarriesEverythingTheBagSays() {
        val c = block(AnalysisPrompt.brew(input()).user, "COFFEE")
        val roaster = Catalog.roasters.first()
        assertEquals(roaster.name, c["roaster"]!!.jsonPrimitive.content)
        assertEquals("Ethiopia", c["origin"]!!.jsonPrimitive.content)
        assertEquals("Yirgacheffe", c["growingRegion"]!!.jsonPrimitive.content)
        assertEquals("Halo Beriti station lot 4", c["farm"]!!.jsonPrimitive.content)
        assertEquals("Smallholders", c["producer"]!!.jsonPrimitive.content)
        assertEquals("Halo Beriti", c["washingStation"]!!.jsonPrimitive.content)
        assertEquals(Catalog.beans.lotTypes.first().label, c["lotType"]!!.jsonPrimitive.content)
        assertEquals(Catalog.beans.species.first().label, c["species"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(Catalog.beans.varietals.first().label, "Kurume", "74110"),
            c["varietals"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("Washed", c["process"]!!.jsonPrimitive.content)
        assertEquals(Catalog.process("washed")!!.group, c["processGroup"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, c["decaf"])
        assertEquals("Light", c["roastLevel"]!!.jsonPrimitive.content)
        assertEquals(Catalog.beans.roastIntents.first().label, c["roastIntent"]!!.jsonPrimitive.content)
        assertEquals("2100", c["altitudeM"]!!.jsonPrimitive.content)
        assertEquals("2025/26", c["harvest"]!!.jsonPrimitive.content)
        assertEquals("14", c["daysOffRoast"]!!.jsonPrimitive.content)
        assertEquals("10", c["restDaysSuggested"]!!.jsonPrimitive.content)
        assertEquals("Jasmine, peach", c["roasterNotes"]!!.jsonPrimitive.content)
        assertEquals("Second bag", c["bagNotes"]!!.jsonPrimitive.content)
    }

    @Test
    fun theBrewerAndTheWaterGetBlocksOfTheirOwn() {
        val water = Catalog.water.first()
        val built = AnalysisPrompt.brew(input(l = log(waterId = water.id)))
        val brewer = block(built.user, "BREWER")
        assertEquals(v60.brewer.label, brewer["name"]!!.jsonPrimitive.content)
        assertEquals("pourover", brewer["method"]!!.jsonPrimitive.content)
        assertEquals(v60.brewer.filter, brewer["filter"]!!.jsonPrimitive.content)
        assertEquals(v60.brewer.category, brewer["category"]!!.jsonPrimitive.content)
        val w = block(built.user, "WATER")
        assertEquals(water.label, w["label"]!!.jsonPrimitive.content)
        assertEquals(water.kind, w["kind"]!!.jsonPrimitive.content)
        assertTrue(w.containsKey("ghPpm") && w.containsKey("khPpm"))

        assertTrue(AnalysisPrompt.brew(input()).user.contains("WATER\nnot recorded"))
    }

    // ---------------------------------------------------- photos and units

    @Test
    fun attachedPhotosAreNamedInOrderAndChangeTheHash() {
        val none = AnalysisPrompt.brew(input())
        assertTrue(none.user.contains("PHOTOS\nnone"))
        assertEquals(0, none.photoCount)

        val photos = listOf(
            PhotoMeta(uid = "1a2b3c4d-0000-0000-0000-000000000000", kind = "bed", takenAt = 1_789_373_640_000L, bytes = 217_000),
            PhotoMeta(uid = "9f8e7d6c-0000-0000-0000-000000000000", kind = "cup", takenAt = 1_789_373_700_000L, bytes = 180_500),
        )
        val two = AnalysisPrompt.brew(input(photos = photos))
        assertTrue(two.user, two.user.contains("PHOTOS\n2 attached, in this order: #1 bed (2026-09-14 08:14Z, 1a2b3c4d, 212 KB), #2 cup (2026-09-14 08:15Z, 9f8e7d6c, 176 KB)"))
        assertEquals(2, two.photoCount)
        assertFalse(none.inputHash == two.inputHash)
        // Unticking one photo is an edit too.
        assertFalse(two.inputHash == AnalysisPrompt.brew(input(photos = photos.take(1))).inputHash)
        // Never more than three, however many the caller passes.
        val many = (1..5).map { PhotoMeta("uid-$it", "bed", 1_789_373_640_000L, 1000) }
        assertEquals(3, AnalysisPrompt.brew(input(photos = many)).photoCount)
    }

    @Test
    fun fahrenheitAddsALineForThePersonButTheDataStaysCelsius() {
        val c = AnalysisPrompt.brew(input(units = "c"))
        val f = AnalysisPrompt.brew(input(units = "f"))
        assertFalse(c.user.contains("USER UNITS"))
        assertTrue(f.user.contains("USER UNITS\nWrite temperatures in °F for this person; the data is in °C."))
        assertTrue(f.user.contains("\"tempC\":96"))
        // Switching units is not an edit to the brew.
        assertEquals(c.inputHash, f.inputHash)
        val trend = AnalysisPrompt.trend(TrendPromptInput(listOf(log()), 1, mapOf(v60.id to v60), mapOf(bean.id to bean), mapOf(kUltra.id to kUltra), units = "f"))
        assertTrue(trend.user.contains("USER UNITS"))
    }

    // ----------------------------------------------------------- principles

    @Test
    fun principlesAreFilteredToTheBrewsMethod() {
        val list = listOf(
            principle("pour", "grind", appliesTo = listOf("pourover")),
            principle("espresso", "espresso", appliesTo = listOf("espresso")),
            principle("press", "grind", appliesTo = listOf("frenchPress", "aeropress")),
            principle("everyone", "water"),
        )
        val built = AnalysisPrompt.brew(input(principles = list))
        assertTrue(built.user.contains("PRINCIPLES (from the people whose methods these are)"))
        assertTrue(built.user.contains("text of pour"))
        assertTrue(built.user.contains("text of everyone"))
        assertFalse(built.user.contains("text of espresso"))
        assertFalse(built.user.contains("text of press"))
        // Each line carries who said it; the url stays in the app.
        assertTrue(built.user.contains("{\"who\":\"Someone\",\"topic\":\"grind\",\"text\":\"text of pour\"}"))
        assertFalse(built.user.contains("example.com"))
    }

    @Test
    fun atMostTenPrinciplesAreSentAndTheyCoverTheTopics() {
        val many = (1..8).map { principle("grind-$it", "grind") } +
            (1..8).map { principle("bed-$it", "bed") } +
            (1..8).map { principle("water-$it", "water") }
        val chosen = AnalysisPrompt.principlesFor(many, setOf(BrewMethod.PourOver), author = "", topics = listOf("bed", "grind"))
        assertEquals(10, chosen.size)
        // One per topic in the asked order, then round again; file order inside a topic.
        assertEquals(
            listOf("bed-1", "grind-1", "water-1", "bed-2", "grind-2", "water-2", "bed-3", "grind-3", "water-3", "bed-4"),
            chosen.map { it.id },
        )
    }

    @Test
    fun theRecipeAuthorsOwnPrinciplesComeFirst() {
        val list = listOf(
            principle("a", "extraction", who = "Scott Rao"),
            principle("b", "recipe", who = "Tetsu Kasuya"),
            principle("c", "grind", who = "Matt Perger (Barista Hustle)"),
            principle("d", "recipe", who = "Tetsu Kasuya"),
        )
        val forKasuya = AnalysisPrompt.principlesFor(list, setOf(BrewMethod.PourOver), author = kasuya.author, topics = listOf("extraction"))
        assertEquals(listOf("b", "d", "a", "c"), forKasuya.map { it.id })
        val forPerger = AnalysisPrompt.principlesFor(list, setOf(BrewMethod.PourOver), author = "Matt Perger", topics = listOf("extraction"))
        assertEquals("c", forPerger.first().id)
        val forNobody = AnalysisPrompt.principlesFor(list, setOf(BrewMethod.PourOver), author = "", topics = listOf("extraction"))
        assertEquals(listOf("a", "b", "c", "d"), forNobody.map { it.id })
    }

    @Test
    fun aTroubledBedPutsBedAndAgitationFirst() {
        val calm = AnalysisPrompt.topicsFor(log(), BrewMethod.PourOver, null)
        assertEquals("extraction", calm.first())
        val crater = AnalysisPrompt.topicsFor(log(bed = BedShape.Crater), BrewMethod.PourOver, null)
        assertEquals(listOf("bed", "agitation", "flow"), crater.take(3))
        assertEquals("espresso", AnalysisPrompt.topicsFor(log(), BrewMethod.Espresso, null).first())
        assertTrue("water" in AnalysisPrompt.topicsFor(log(), BrewMethod.PourOver, Catalog.water.first()))
        assertFalse("water" in calm)
    }

    @Test
    fun newPrinciplesNeverMakeAnUntouchedBrewLookEdited() {
        val without = AnalysisPrompt.brew(input())
        val with = AnalysisPrompt.brew(input(principles = Catalog.principles))
        assertFalse(without.user == with.user)
        assertEquals(without.inputHash, with.inputHash)
        // The brew's own data still does.
        assertFalse(without.inputHash == AnalysisPrompt.brew(input(l = log(clicks = 73))).inputHash)
    }

    @Test
    fun theRealPrinciplesFileFeedsTheRealPrompt() {
        assertTrue("shared/principles.json was not found", Catalog.principles.isNotEmpty())
        val built = AnalysisPrompt.brew(input(l = log(recipe = kasuya), recipe = kasuya, principles = Catalog.principles))
        val lines = built.user.substringAfter("PRINCIPLES (from the people whose methods these are)\n[\n").substringBefore("\n]").lines()
        assertEquals(10, lines.size)
        // Brewing Kasuya's recipe: his own principles lead.
        assertTrue(lines.first(), lines.first().contains("\"who\":\"Tetsu Kasuya\""))
        // Nothing meant for another method slips in.
        val aeropressOnly = Catalog.principles.filter { it.appliesTo == listOf("aeropress") }
        assertTrue(aeropressOnly.isNotEmpty())
        aeropressOnly.forEach { assertFalse(it.id, built.user.contains(it.text)) }
        assertNotNull(built.inputHash)
    }

    // ---------------------------------------------------------------- trend

    @Test
    fun theTrendPromptDescribesEachGrindersScaleAndEachBrewsBrewerAndWater() {
        val water = Catalog.water.first()
        val logs = listOf(
            log(id = 3, waterId = water.id),
            log(id = 2, grinder = encore, clicks = null, value = 15.0, setting = "15", startedAt = 1_789_287_120_000L),
            log(id = 1, startedAt = 1_789_200_720_000L),
        )
        val built = AnalysisPrompt.trend(
            TrendPromptInput(
                logs = logs,
                totalLogged = 3,
                recipes = mapOf(v60.id to v60),
                beans = mapOf(bean.id to bean),
                grinders = mapOf(kUltra.id to kUltra, encore.id to encore),
                principles = listOf(principle("p", "grind", appliesTo = listOf("pourover")), principle("e", "espresso", appliesTo = listOf("espresso"))),
            ),
        )
        val u = built.user
        assertTrue(u, u.contains("{\"id\":1,\"name\":\"1Zpresso K-Ultra\",\"scale\":{\"adjustsBy\":\"clicks\""))
        assertTrue(u, u.contains("{\"id\":2,\"name\":\"Baratza Encore\",\"scale\":{\"adjustsBy\":\"numbered dial\""))
        assertTrue(u.contains("\"moveSizes\":{\"nudge\":2,\"clearMiss\":6,\"maxAtOnce\":13"))
        assertTrue(u.contains("\"brewer\":\"${v60.brewer.label}\""))
        assertTrue(u.contains("\"water\":\"${water.label}\""))
        assertTrue(u.contains("\"grindValue\":15"))
        assertTrue(u.contains("\"origin\":\"Ethiopia\""))
        assertTrue(u.contains("text of p"))
        assertFalse(u.contains("text of e\""))
        assertNull(built.grindAnchor)
        assertEquals(0, built.photoCount)
    }

    // ------------------------------------------------- the hash and row ids

    /** The same three brews as another install would hold them: every row id different, every uid the same. */
    private class Renumbered(val logs: List<BrewLogEntity>, val beans: Map<Long, BeanEntity>, val grinders: Map<Long, GrinderEntity>)

    private fun trendLogs() = listOf(
        log(id = 3),
        log(id = 2, grinder = encore, clicks = null, value = 15.0, setting = "15", startedAt = 1_789_287_120_000L),
        log(id = 1, startedAt = 1_789_200_720_000L),
    )

    /** New ids, and the two grinders swap places so their id order is the other way round. */
    private fun renumbered(logs: List<BrewLogEntity>): Renumbered {
        val grinderIds = mapOf(kUltra.id to 902L, encore.id to 901L)
        return Renumbered(
            logs = logs.map { it.copy(id = it.id + 400, beanId = it.beanId?.let { 77L }, grinderId = it.grinderId?.let(grinderIds::get)) },
            beans = mapOf(77L to bean.copy(id = 77)),
            grinders = listOf(kUltra, encore).associate { grinderIds.getValue(it.id) to it.copy(id = grinderIds.getValue(it.id)) },
        )
    }

    private fun trendInput(logs: List<BrewLogEntity>, beans: Map<Long, BeanEntity>, grinders: Map<Long, GrinderEntity>) =
        TrendPromptInput(logs = logs, totalLogged = logs.size, recipes = mapOf(v60.id to v60), beans = beans, grinders = grinders)

    @Test
    fun aBrewRestoredUnderNewRowIdsHashesTheSame() {
        // The target's grind is distrusted, so the prompt names an earlier brew ("brew #9") too.
        val earlier = log(id = 9, clicks = 78, startedAt = 1_789_287_120_000L)
        val here = AnalysisPrompt.brew(input(l = log(clicks = 0), history = listOf(earlier)))
        assertTrue(here.user, here.user.contains("TASK: Diagnose brew #10 "))
        assertTrue(here.user, here.user.contains("\"lastTrustedFrom\":\"brew #9, "))
        assertTrue(here.user, here.user.contains("- #10 "))
        assertTrue(here.inputHash, AnalysisPrompt.isCurrentHash(here.inputHash))

        val moved = renumbered(listOf(log(clicks = 0), earlier))
        val there = AnalysisPrompt.brew(
            BrewPromptInput(
                log = moved.logs[0],
                recipe = v60,
                bean = moved.beans.getValue(77),
                grinder = moved.grinders.getValue(902),
                history = moved.logs.drop(1),
                recipes = mapOf(v60.id to v60, kasuya.id to kasuya),
                beans = moved.beans,
                grinders = moved.grinders,
            ),
        )
        assertTrue(there.user, there.user.contains("TASK: Diagnose brew #410 "))
        assertEquals("only the row ids differ, and the hash reads none of them", here.inputHash, there.inputHash)

        // The data still counts: one click of difference is an edit on either install.
        val edited = AnalysisPrompt.brew(input(l = log(clicks = 0), history = listOf(earlier.copy(grindClicks = 79))))
        assertFalse(here.inputHash == edited.inputHash)
        // And a different brew with the same numbers is not the same brew.
        assertFalse(here.inputHash == AnalysisPrompt.brew(input(l = log(clicks = 0, uid = "another"), history = listOf(earlier))).inputHash)
    }

    @Test
    fun aLogRestoredUnderNewRowIdsHashesTheSame() {
        val logs = trendLogs()
        val here = AnalysisPrompt.trend(trendInput(logs, mapOf(bean.id to bean), mapOf(kUltra.id to kUltra, encore.id to encore)))
        val moved = renumbered(logs)
        val there = AnalysisPrompt.trend(trendInput(moved.logs, moved.beans, moved.grinders))

        // What the model reads does change: its ids are the rows', and the grinder list follows them.
        assertTrue(here.user.indexOf("\"name\":\"1Zpresso K-Ultra\"") < here.user.indexOf("\"name\":\"Baratza Encore\""))
        assertTrue(there.user.indexOf("\"name\":\"1Zpresso K-Ultra\"") > there.user.indexOf("\"name\":\"Baratza Encore\""))
        assertTrue(there.user, there.user.contains("{\"id\":403,"))
        assertTrue(there.user, there.user.contains("\"coffeeId\":77,"))
        assertEquals(here.inputHash, there.inputHash)

        val edited = logs.map { if (it.id == 2L) it.copy(overall = 2) else it }
        val changed = AnalysisPrompt.trend(trendInput(edited, mapOf(bean.id to bean), mapOf(kUltra.id to kUltra, encore.id to encore)))
        assertFalse(here.inputHash == changed.inputHash)
    }

    @Test
    fun aCoffeeDeletedBeforeTheBackupDoesNotChangeTheHashOnRestore() {
        // A brew keeps the row id of a coffee that was deleted; a backup has no uid to write for
        // it, so the restored brew has no coffee id at all.
        val logs = trendLogs().map { if (it.id == 1L) it.copy(beanId = 55) else it }
        val restored = logs.map { if (it.id == 1L) it.copy(beanId = null) else it }
        val grinders = mapOf(kUltra.id to kUltra, encore.id to encore)
        assertEquals(
            AnalysisPrompt.trend(trendInput(logs, mapOf(bean.id to bean), grinders)).inputHash,
            AnalysisPrompt.trend(trendInput(restored, mapOf(bean.id to bean), grinders)).inputHash,
        )
    }

    @Test
    fun brewsOfADeletedCoffeeAreGroupedAsARestoreLeavesThem() {
        // Three brews of a coffee since deleted (they keep its row id, 55) and one logged with
        // no coffee, far from them. A restore brings all four back with no coffee id.
        val history = listOf(
            log(id = 9, clicks = 60, beanId = 55, startedAt = 1_789_287_120_000L),
            log(id = 8, clicks = 60, beanId = 55, startedAt = 1_789_200_720_000L),
            log(id = 7, clicks = 60, beanId = 55, startedAt = 1_789_114_320_000L),
        )
        val restored = history.map { it.copy(beanId = null) }
        fun brew(l: BrewLogEntity, earlier: List<BrewLogEntity>) = AnalysisPrompt.brew(input(l = l, history = earlier).copy(bean = null))
        val target = log(clicks = 190, beanId = null)
        val before = brew(target, history)
        val after = brew(target, restored)
        assertTrue(after.flags.any { it.code == FlagCode.GRIND_JUMP && it.brewId == target.id })
        assertEquals(after.flags, before.flags)
        assertEquals(after.inputHash, before.inputHash)

        // The same for the brew a distrusted grind is anchored on: the newest, on either side.
        val anchors = listOf(
            log(id = 9, clicks = 70, beanId = 55, startedAt = 1_789_287_120_000L),
            log(id = 8, clicks = 80, beanId = null, startedAt = 1_789_200_720_000L),
        )
        val a = brew(log(clicks = 0, beanId = null), anchors)
        val b = brew(log(clicks = 0, beanId = null), anchors.map { it.copy(beanId = null) })
        assertTrue(a.user, a.user.contains("\"lastTrustedFrom\":\"brew #9, "))
        assertEquals(b.inputHash, a.inputHash)

        // And across a log: the trend checks every brew against the ones before it.
        val grinders = mapOf(kUltra.id to kUltra)
        val dangling = AnalysisPrompt.trend(trendInput(listOf(target) + history, emptyMap(), grinders))
        val clean = AnalysisPrompt.trend(trendInput(listOf(target) + restored, emptyMap(), grinders))
        assertEquals(clean.flags, dangling.flags)
        assertEquals(clean.inputHash, dangling.inputHash)
        // The model is still shown which brews shared the coffee.
        assertTrue(dangling.user, dangling.user.contains("\"coffeeId\":55,"))
    }

    @Test
    fun whetherPhotosAreSentIsASettingAndTheHashDoesNotReadIt() {
        val bed = PhotoMeta(uid = "1a2b3c4d-0000-0000-0000-000000000000", kind = "bed", takenAt = 1_789_373_640_000L, bytes = 217_000)
        val cup = PhotoMeta(uid = "9f8e7d6c-0000-0000-0000-000000000000", kind = "cup", takenAt = 1_789_373_700_000L, bytes = 180_500)
        // Made with a vision model; read again where the settings attach nothing.
        val sent = AnalysisPrompt.brew(input(photos = listOf(bed)).copy(brewPhotos = listOf(bed)))
        val unsent = AnalysisPrompt.brew(input().copy(brewPhotos = listOf(bed)))
        assertTrue(sent.user.contains("PHOTOS\n1 attached"))
        assertTrue(unsent.user.contains("PHOTOS\nnone"))
        assertEquals(listOf(1, 0), listOf(sent.photoCount, unsent.photoCount))
        assertEquals(sent.inputHash, unsent.inputHash)
        // A photo added to the brew is still an edit.
        assertFalse(sent.inputHash == AnalysisPrompt.brew(input().copy(brewPhotos = listOf(bed, cup))).inputHash)
        assertFalse(sent.inputHash == AnalysisPrompt.brew(input().copy(brewPhotos = emptyList())).inputHash)
    }

    @Test
    fun aHashFromBeforeUidsIsToldApart() {
        assertTrue(AnalysisPrompt.brew(input()).inputHash.matches(Regex("s2:[0-9a-f]{16}")))
        assertTrue(AnalysisPrompt.isCurrentHash("s2:0123456789abcdef"))
        assertFalse("what 2.0.0 stored", AnalysisPrompt.isCurrentHash("0123456789abcdef"))
        assertFalse(AnalysisPrompt.isCurrentHash(""))
    }
}
