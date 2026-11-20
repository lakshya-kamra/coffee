package com.laksh.coffee.ui

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.LogFilter
import com.laksh.coffee.domain.LogSort
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.ui.catalog.RoasterLike
import com.laksh.coffee.ui.catalog.RoasterOrder
import com.laksh.coffee.ui.onboarding.Onboarding
import com.laksh.coffee.ui.onboarding.OnboardingPrefs
import com.laksh.coffee.ui.onboarding.OnboardingState
import com.laksh.coffee.ui.screens.gear.BeanWords
import com.laksh.coffee.ui.screens.gear.InlineEdit
import com.laksh.coffee.ui.screens.gear.InlineSelection
import com.laksh.coffee.ui.screens.history.BrewCompare
import com.laksh.coffee.ui.screens.history.LogChip
import com.laksh.coffee.ui.screens.history.LogFilterUi
import com.laksh.coffee.ui.screens.history.LogMenu
import com.laksh.coffee.ui.screens.insights.insightsEmptyLine
import com.laksh.coffee.ui.screens.insights.nearestIndex
import com.laksh.coffee.ui.screens.insights.tickIndices
import com.laksh.coffee.ui.theme.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V4-AND3: Log filters, compare words, onboarding presets, the inline editor, roaster order, Insights. */
class V4ScreensTest {

    private val day = 86_400_000L

    private fun brew(
        id: Long,
        recipeId: String = "hoffmann-v60",
        recipeName: String = "Hoffmann V60",
        bean: String = "Square Mile — Kamwangi",
        overall: Int = 7,
        at: Long = id * day,
        brewerId: String? = "hario-v60-02",
    ) = BrewLogEntity(
        id = id,
        startedAt = at,
        recipeId = recipeId,
        recipeName = recipeName,
        beanLabel = bean,
        brewer = "Hario V60",
        brewerId = brewerId,
        overall = overall,
    )

    // ------------------------------------------------------------- log filters (§4.11)

    @Test
    fun logChipsToggleAndClearKeepsTheSort() {
        var f = LogFilter()
        f = LogFilterUi.toggle(f, LogChip.Favourites)
        f = LogFilterUi.toggle(f, LogChip.Rated8)
        assertTrue(LogFilterUi.isOn(f, LogChip.Favourites))
        assertTrue(LogFilterUi.isOn(f, LogChip.Rated8))
        assertFalse(LogFilterUi.isOn(f, LogChip.WithPhotos))
        f = LogFilterUi.toggle(f, LogChip.Favourites)
        assertFalse(f.favourites)

        f = f.copy(query = "kenya", sort = LogSort.Best)
        val cleared = LogFilterUi.cleared(f)
        assertFalse(cleared.isActive)
        assertEquals(LogSort.Best, cleared.sort)
    }

    @Test
    fun menuChipsPickAndUnpickWithTheExactCopy() {
        var f = LogFilter()
        f = LogFilterUi.pick(f, LogMenu.Coffee, "Square Mile — Kamwangi")
        assertEquals("Square Mile — Kamwangi", f.coffee)
        // Picking the chosen one again clears it, as does "Any".
        assertNull(LogFilterUi.pick(f, LogMenu.Coffee, "Square Mile — Kamwangi").coffee)
        assertNull(LogFilterUi.pick(f, LogMenu.Coffee, null).coffee)

        val logs = listOf(brew(1), brew(2), brew(3, recipeId = "onyx-v60", recipeName = "Onyx V60", bean = "Onyx — Geometry"))
        val coffees = LogFilterUi.options(logs, LogMenu.Coffee)
        // Most brewed first, and the chip reads the coffee's own name.
        assertEquals(listOf("Kamwangi", "Geometry"), coffees.map { it.label })
        assertEquals("Coffee ▾", LogFilterUi.menuLabel(LogMenu.Coffee, null, coffees))
        assertEquals("Kamwangi ▾", LogFilterUi.menuLabel(LogMenu.Coffee, "Square Mile — Kamwangi", coffees))
        assertEquals(listOf("hoffmann-v60", "onyx-v60"), LogFilterUi.options(logs, LogMenu.Recipe).map { it.key })
        assertEquals("Search coffee, recipe, notes", LogFilterUi.PLACEHOLDER)
    }

    @Test
    fun resultLineAndNoResultsFollowTheFilter() {
        assertNull(LogFilterUi.resultLine(12, LogFilter()))
        assertEquals("12 brews", LogFilterUi.resultLine(12, LogFilter(rated8 = true)))
        assertEquals("1 brew", LogFilterUi.resultLine(1, LogFilter(query = "x")))
        assertTrue(LogFilterUi.noResults(total = 5, shown = 0, f = LogFilter(query = "zzz")))
        // An empty log is the empty state, never "No brews match."
        assertFalse(LogFilterUi.noResults(total = 0, shown = 0, f = LogFilter(query = "zzz")))
        assertEquals("No brews match.", LogFilterUi.NO_RESULTS)
        assertEquals("Clear filters", LogFilterUi.CLEAR)
    }

    @Test
    fun keyboardStepsAreClamped() {
        val ids = listOf(30L, 20L, 10L)
        assertEquals(20L, LogFilterUi.step(ids, 30L, 1))
        assertEquals(10L, LogFilterUi.step(ids, 10L, 1))
        assertEquals(30L, LogFilterUi.step(ids, 30L, -1))
        assertEquals(30L, LogFilterUi.step(ids, null, 1))
        assertNull(LogFilterUi.step(emptyList(), null, 1))
    }

    // --------------------------------------------------------------- compare (§3.8)

    @Test
    fun compareSaysHowTheSecondBrewDiffers() {
        val a = brew(1, overall = 7).copy(grindClicks = 24, tempC = 94, doseG = 15.0, waterG = 250.0, totalTimeSec = 180, daysOffRoast = 10)
        val b = brew(4, overall = 8).copy(grindClicks = 19, tempC = 96, doseG = 16.0, waterG = 250.0, totalTimeSec = 200, daysOffRoast = 13, faults = listOf("Bitter"))
        val rows = BrewCompare.rows(a, b, plannedSec = { 180 }).associateBy { it.label }
        assertEquals("finer by 5 clicks", rows.getValue("GRIND").diff)
        assertEquals("2 °C hotter", rows.getValue("TEMP").diff)
        assertEquals("+0:20", rows.getValue("TIME VS PLAN").diff)
        assertEquals("+1", rows.getValue("OVERALL").diff)
        assertEquals("+1 g", rows.getValue("DOSE").diff)
        assertEquals("stronger", rows.getValue("RATIO").diff)
        assertEquals("+3 days off roast", rows.getValue("COFFEE").diff)
        assertEquals("3 days later", rows.getValue("DATE").diff)
        assertEquals("more faults", rows.getValue("FAULTS").diff)
        assertEquals("3:20 (plan 3:00)", rows.getValue("TIME VS PLAN").b)
        // Unchanged rows carry no word.
        assertNull(rows.getValue("RECIPE").diff)
        assertNull(rows.getValue("WATER").diff)
    }

    @Test
    fun compareUsesTheUsersDegreesAndTheMethodsWords() {
        val a = brew(1).copy(tempC = 92, grindClicks = 10)
        val b = brew(2).copy(tempC = 94, grindClicks = 12)
        val rows = BrewCompare.rows(a, b, units = Units(fahrenheit = true), labels = MethodLabels.of(BrewMethod.Espresso), lowerIsFiner = false)
        val byLabel = rows.associateBy { it.label }
        assertEquals("4 °F hotter", byLabel.getValue("TEMP").diff)
        // A dial numbered the other way: more is finer.
        assertEquals("finer by 2 clicks", byLabel.getValue("GRIND").diff)
        assertTrue(rows.any { it.label == "YIELD" })
        assertTrue(rows.any { it.label == "SHOT TIME VS PLAN" })
    }

    @Test
    fun compareHandlesPreGroundAndBlade() {
        val pre = brew(1).copy(grindKind = "preground", grindSetting = "As bought")
        val blade1 = brew(2).copy(grindKind = "blade", grindValue = 10.0, grinderLabel = "Blade")
        val blade2 = brew(3).copy(grindKind = "blade", grindValue = 14.0, grinderLabel = "Blade")
        assertEquals("As bought", BrewCompare.rows(pre, blade1).first { it.label == "GRIND" }.a)
        assertEquals("another grind", BrewCompare.rows(pre, blade1).first { it.label == "GRIND" }.diff)
        assertEquals("finer, +4 s", BrewCompare.rows(blade1, blade2).first { it.label == "GRIND" }.diff)
        assertEquals("−4", BrewCompare.signed(-4))
    }

    @Test
    fun comparePickerOffersTheSameRecipeThenTheSameCoffee() {
        val base = brew(5)
        val all = listOf(
            base,
            brew(1, recipeId = "onyx", recipeName = "Onyx", bean = "Other — Bag"),
            brew(2, recipeId = "onyx", recipeName = "Onyx"),
            brew(3),
            brew(4),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), BrewCompare.pickerOrder(base, all).map { it.id })
        assertEquals("Same recipe", BrewCompare.pickerGroup(base, all[3]))
        assertEquals("Same coffee", BrewCompare.pickerGroup(base, all[2]))
        assertEquals("Other brews", BrewCompare.pickerGroup(base, all[1]))
    }

    // ---------------------------------------------------------- onboarding (§4.1)

    private class FakePrefs : OnboardingPrefs {
        val writes = linkedMapOf<String, Any>()
        override suspend fun setExperience(value: String) { writes["experience"] = value }
        override suspend fun setReviewMode(mode: String) { writes["reviewMode"] = mode }
        override suspend fun setSound(on: Boolean) { writes["soundCues"] = on }
        override suspend fun setHasScale(on: Boolean) { writes["hasScale"] = on }
        override suspend fun setBuysGround(on: Boolean) { writes["buysGround"] = on }
        override suspend fun setTempUnit(unit: String) { writes["tempUnit"] = unit }
        override suspend fun setWeightUnit(unit: String) { writes["weightUnit"] = unit }
        override suspend fun setHomeArea(area: String) { writes["homeArea"] = area }
    }

    @Test
    fun experienceAnswersSaveTheirPresets() = runBlocking {
        val new = FakePrefs().also { Onboarding.saveYou(it, "new") }
        assertEquals(mapOf("experience" to "new", "reviewMode" to "quick", "soundCues" to true), new.writes)
        // The scale is asked on the next step, never written by the first.
        assertFalse("hasScale" in new.writes)

        val recipes = FakePrefs().also { Onboarding.saveYou(it, "recipes") }
        assertEquals(mapOf("experience" to "recipes", "reviewMode" to "quick"), recipes.writes)
        val tinker = FakePrefs().also { Onboarding.saveYou(it, "tinker") }
        assertEquals(mapOf("experience" to "tinker", "reviewMode" to "detailed"), tinker.writes)
        // Skipping the question writes nothing.
        assertTrue(FakePrefs().also { Onboarding.saveYou(it, "") }.writes.isEmpty())

        assertEquals(false, Onboarding.presetsFor("new").hasScale)
        assertNull(Onboarding.presetsFor("tinker").hasScale)
    }

    @Test
    fun theMeasureStepSavesTheScaleOnlyWhenAnswered() = runBlocking {
        val noScale = FakePrefs().also { Onboarding.saveMeasure(it, OnboardingState(hasScale = false, tempUnit = "f")) }
        assertEquals(false, noScale.writes["hasScale"])
        assertEquals("f", noScale.writes["tempUnit"])
        val skipped = FakePrefs().also { Onboarding.saveMeasure(it, OnboardingState(hasScale = null)) }
        assertFalse("hasScale" in skipped.writes)
    }

    @Test
    fun onboardingCopyAndOrder() {
        assertEquals(6, Onboarding.STEPS)
        assertEquals(listOf("I'm new to this", "I follow recipes", "I tinker"), Onboarding.EXPERIENCE_CHOICES.map { it.second })
        assertEquals(listOf("Tap water", "Filtered tap water", "Bottled spring water", "Not sure"), Onboarding.PLAIN_WATERS.map { it.label })
        assertEquals("", Onboarding.PLAIN_WATERS.last().waterId)
        assertEquals("drip-machine", Onboarding.COMMON_BREWER_IDS.first())
    }

    // ------------------------------------------------------- inline editor (§3.10)

    @Test
    fun inlineEditorAsksBeforeLeavingUnsavedChanges() {
        val s = InlineSelection("bean:1")
        assertEquals(InlineSelection("bean:2"), InlineEdit.requestSelect(s, "bean:2", dirty = false))
        val asking = InlineEdit.requestSelect(s, "bean:2", dirty = true)
        assertTrue(asking.asking)
        assertEquals("bean:1", asking.selected)
        assertEquals(InlineSelection("bean:2"), InlineEdit.answer(asking, discard = true))
        assertEquals(InlineSelection("bean:1"), InlineEdit.answer(asking, discard = false))
        // Back to the default (null) is a real answer, not "no question".
        val toDefault = InlineEdit.requestSelect(s, null, dirty = true)
        assertTrue(toDefault.asking)
        assertEquals(InlineSelection(null), InlineEdit.answer(toDefault, discard = true))
        // The same row again is never a question.
        assertFalse(InlineEdit.requestSelect(s, "bean:1", dirty = true).asking)
    }

    @Test
    fun dirtyIgnoresTrimmedTextAndBookkeeping() {
        val saved = BeanEntity(id = 3, name = "Kamwangi", roaster = "Square Mile", updatedAt = 10)
        assertFalse(InlineEdit.dirty(saved, saved.copy(name = "Kamwangi  ", updatedAt = 99)))
        assertTrue(InlineEdit.dirty(saved, saved.copy(roastedOnEpochDay = 20_000)))
        // "Ground for" without ground coffee is not a change worth asking about.
        assertFalse(InlineEdit.dirty(saved, saved.copy(groundFor = "filter")))
        assertTrue(InlineEdit.dirty(saved, saved.copy(form = "ground", groundFor = "filter")))
        val g = GrinderEntity(id = 1, brand = "1Zpresso", model = "K-Ultra")
        assertFalse(InlineEdit.dirty(g, g.copy(model = " K-Ultra ")))
        assertTrue(InlineEdit.dirty(g, g.copy(defaultFilterSetting = 72.0)))
        assertFalse(InlineEdit.canSave(BeanEntity(name = "  ")))
        assertTrue(InlineEdit.canSave(GrinderEntity(brand = "", model = "Encore")))
    }

    @Test
    fun beanFreshnessWordsAndFeet() {
        val today = 20_000L
        assertEquals("Resting", BeanWords.freshness(BeanEntity(name = "a", roastedOnEpochDay = today - 2), today))
        assertEquals("Resting", BeanWords.freshness(BeanEntity(name = "a", roastedOnEpochDay = today - 6, restDays = 10), today))
        assertEquals("Peak", BeanWords.freshness(BeanEntity(name = "a", roastedOnEpochDay = today - 12), today))
        assertEquals("Fading", BeanWords.freshness(BeanEntity(name = "a", roastedOnEpochDay = today - 45), today))
        assertNull(BeanWords.freshness(BeanEntity(name = "a"), today))
        assertEquals("5577" to "6234", BeanWords.altitudeInFeet("1700-1900"))
        assertEquals("1700-1900", BeanWords.altitudeFromFeet("5577", "6234"))
    }

    // ------------------------------------------------------- roaster order (§4.7)

    @Test
    fun roasterPickerPutsRegionThenSupermarketThenSpecialty() {
        val list = listOf(
            RoasterLike("onyx", "Onyx Coffee Lab", "North America"),
            RoasterLike("square-mile", "Square Mile", "Europe"),
            RoasterLike("folgers", "Folgers", "North America", true, listOf("North America")),
            RoasterLike("lavazza", "Lavazza", "Europe", true, listOf("Europe", "North America")),
            RoasterLike("bru", "Bru", "Asia", true, listOf("Asia")),
            RoasterLike("blue-tokai", "Blue Tokai", "Asia"),
            RoasterLike("april", "april coffee", "Europe"),
        )
        // The same groups the web's roasterOrder.test.ts expects.
        val g = RoasterOrder.groups(list, "Europe", setOf("onyx"))
        assertEquals(listOf(RoasterOrder.FAVOURITES, "Europe", RoasterOrder.MAINSTREAM, RoasterOrder.SPECIALTY), g.map { it.name })
        assertEquals(listOf("onyx"), g[0].ids)
        assertEquals(listOf("april", "lavazza", "square-mile"), g[1].ids)
        assertEquals(listOf("bru", "folgers"), g[2].ids)
        assertEquals(listOf("blue-tokai", "onyx"), g[3].ids)
        assertEquals(listOf(RoasterOrder.MAINSTREAM, RoasterOrder.SPECIALTY), RoasterOrder.groups(list, "", emptySet()).map { it.name })
    }

    // -------------------------------------------------------------- insights (§3.9)

    @Test
    fun chartTicksAndTapsAndTheEmptyLine() {
        assertEquals(listOf(0, 1, 2), tickIndices(3, 4))
        assertEquals(listOf(0, 3, 6, 9), tickIndices(10, 4))
        assertTrue(tickIndices(0, 4).isEmpty())
        assertEquals(2, nearestIndex(listOf(0.1f, 0.5f, 0.9f), 85f, 100f))
        assertNull(nearestIndex(emptyList(), 10f, 100f))
        assertEquals("Insights start after 3 brews. 2 to go.", insightsEmptyLine(1))
        assertNull(insightsEmptyLine(3))
    }
}
