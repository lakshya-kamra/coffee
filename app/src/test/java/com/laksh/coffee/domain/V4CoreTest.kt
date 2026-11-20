package com.laksh.coffee.domain

import com.laksh.coffee.core.halves
import com.laksh.coffee.core.screenDate
import com.laksh.coffee.core.screenDayLabel
import com.laksh.coffee.core.screenTime
import com.laksh.coffee.core.timeLabel
import com.laksh.coffee.core.todayEyebrow
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.prefs.initialReviewMode
import com.laksh.coffee.domain.catalog.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** v4 settings defaults, screen formats, catalog additions and log filters on the JVM. */
class V4CoreTest {

    @Test
    fun freshInstallsTakeTheRegionsUnitAndTheQuickReview() {
        assertEquals("f", Temperatures.defaultUnit(Locale.US))
        assertEquals("c", Temperatures.defaultUnit(Locale.UK))
        assertEquals("quick", initialReviewMode(hasBrews = false))
        assertEquals("detailed", initialReviewMode(hasBrews = true))
        val s = Settings()
        assertEquals(listOf("", true, "quick", false, false), listOf(s.experience, s.hasScale, s.reviewMode, s.buysGround, s.soundCuesAsked))
    }

    @Test
    fun screenFormatsFollowTheLocaleWhileExportsStayPinned() {
        val zone = ZoneId.of("UTC")
        val at = LocalDateTime.of(2026, 11, 19, 19, 5).atZone(zone).toInstant().toEpochMilli()
        assertEquals("EVENING · THU, NOV 19", todayEyebrow(at, Locale.US, zone))
        assertEquals("Nov 19", screenDate(at, Locale.US, zone))
        assertEquals("19 Nov", screenDate(at, Locale.UK, zone))
        assertTrue(screenTime(at, Locale.US, zone).startsWith("7:05"))
        assertEquals("Today", screenDayLabel(at, at, Locale.US, zone))
        assertEquals(listOf("1", "1½", "4½"), listOf(halves(1.0), halves(1.5), halves(4.5)))
        // The pinned 24-hour format exports use is untouched.
        assertEquals(5, timeLabel(at).length)
    }

    @Test
    fun glossarySouthIndianFilterBladeGrinderAndMainstreamRoasters() {
        assertTrue(Catalog.problems.toString(), Catalog.problems.none { it.startsWith("glossary.json") })
        assertTrue(Catalog.glossary.size >= 27)
        Catalog.glossary.forEach { t -> t.sources.forEach { assertTrue("${t.id} ${it.url}", it.url.startsWith("https://")) } }
        listOf(StepKind.Bloom, StepKind.Pour, StepKind.Swirl, StepKind.Stir, StepKind.Steep, StepKind.Press, StepKind.Drawdown)
            .forEach { assertNotNull(it.name, Catalog.term(Glossary.termForStepKind(it))) }
        assertNull(Glossary.termForStepKind(StepKind.Serve))
        assertEquals("extraction", Glossary.find(Catalog.glossary, "EY")?.id)

        val sif = Catalog.brewer("south-indian-filter")!!
        assertEquals(listOf("other", BrewMethod.PourOver, "metal"), listOf(sif.category, sif.method, sif.filter))
        val blade = Catalog.grinder("blade-grinder")!!
        assertEquals(listOf("stepless", "s"), listOf(blade.setting.style, blade.setting.settingUnit))
        val mainstream = Catalog.roasters.filter { it.mainstream }
        assertTrue(mainstream.size >= 19)
        mainstream.forEach { assertTrue(it.id, it.regions.isNotEmpty()) }
        assertFalse(Catalog.roaster("square-mile")!!.mainstream)
        listOf("tap-unfiltered", "tap-filtered", "bottled-spring").forEach { assertNotNull(it, Catalog.water(it)) }
    }

    @Test
    fun logFiltersSortAndCount() {
        val brews = listOf(
            BrewLogEntity(id = 1, startedAt = 1, overall = 9, recipeId = "hoffmann-v60", brewerId = "hario-v60-02", beanLabel = "A"),
            BrewLogEntity(id = 2, startedAt = 2, overall = 6, isFavourite = true, recipeName = "One-off", brewer = "Chemex", beanLabel = "B"),
            BrewLogEntity(id = 3, startedAt = 3, overall = 8, recipeId = "hoffmann-v60", brewerId = "hario-v60-02", notes = "Café crème", beanLabel = "A"),
        )
        fun ids(f: LogFilter, photos: Set<Long> = emptySet()) = LogQuery.filter(brews, f, photos).map { it.id }
        assertEquals(listOf(3L, 2L, 1L), ids(LogFilter()))
        assertEquals(listOf(1L, 3L, 2L), ids(LogFilter(sort = LogSort.Best)))
        assertEquals(listOf(3L, 1L), ids(LogFilter(rated8 = true, coffee = "A")))
        assertEquals(listOf(2L), ids(LogFilter(favourites = true)))
        assertEquals(listOf(2L), ids(LogFilter(recipe = "One-off")))
        assertEquals(listOf(2L), ids(LogFilter(brewer = "Chemex")))
        assertEquals(listOf(1L), ids(LogFilter(withPhotos = true), setOf(1L)))
        assertEquals(listOf(3L), ids(LogFilter(query = "cafe")))
        assertFalse(LogFilter().isActive)
        assertEquals(listOf("1 brew", "12 brews"), listOf(LogQuery.countLabel(1), LogQuery.countLabel(12)))
    }

    @Test
    fun levelNeedsAndPrepNoScaleSurviveTheSavedRecipeJson() {
        val r = Catalog.recipe("hoffmann-french-press")!!.copy(isBuiltIn = false)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = json.decodeFromString(Recipe.serializer(), json.encodeToString(Recipe.serializer(), r))
        assertEquals(listOf(r.level, r.needs, r.prepNoScale), listOf(back.level, back.needs, back.prepNoScale))
        val viaDraft = com.laksh.coffee.ui.recipes.RecipeDraft.from(r).toRecipe()
        assertEquals(listOf(r.level, r.needs, r.prepNoScale), listOf(viaDraft.level, viaDraft.needs, viaDraft.prepNoScale))
    }
}
