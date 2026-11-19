package com.laksh.coffee.ui

import com.laksh.coffee.core.clipWords
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.ui.screens.gear.BeanWords
import com.laksh.coffee.ui.screens.home.HomeUiState
import com.laksh.coffee.ui.screens.insights.PassportStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Small wording rules that are easy to get wrong one screen at a time: a count of one is
 * singular, cut text ends on a whole word, and a value saved with a stray space never leaves a
 * gap in a sentence. Every value here is made up.
 */
class WordingTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 17)

    private fun at(daysAgo: Long): Long = today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun aCountOfOneIsSingular() {
        assertEquals(listOf("days", "day", "days"), listOf(0, 1, 2).map { unitWord(it, "day") })
        assertEquals(listOf("0 brews", "1 brew", "2 brews"), listOf(0, 1, 2).map { counted(it, "brew") })
        assertEquals("1 click", counted(1, "click"))
        assertEquals("1 analysis", counted(1, "analysis", "analyses"))
        assertEquals("4 analyses", counted(4, "analysis", "analyses"))
    }

    @Test
    fun cutTextEndsOnAWholeWord() {
        assertEquals("about 10…", clipWords("about 10 seconds finer than last time", 14))
        assertEquals("medium-fine", clipWords("  medium-fine  ", 14))
        // A word that ends exactly at the limit stays whole.
        assertEquals("83 clicks, 8.3…", clipWords("83 clicks, 8.3 on the dial", 14))
        // Punctuation left hanging by the cut goes too.
        assertEquals("medium-fine…", clipWords("medium-fine, like table salt", 14))
        assertEquals("Coarse, a step…", clipWords("Coarse, a step coarser than your usual paper-filter setting", 16))
        // One long word has no boundary to use.
        assertEquals("supercalifragi…", clipWords("supercalifragilistic", 14))
        // Short enough: only trimmed.
        assertEquals("6.5 by the dial", clipWords("6.5 by the dial ", 15))
        assertEquals("", clipWords("   ", 14))
    }

    @Test
    fun aValueSavedWithATrailingSpaceLeavesNoGapInInsights() {
        val bean = BeanEntity(id = 1, name = "Harbour Blend ", roaster = "Tidewater Roasters ", origin = "Peru ", region = "Cajamarca ")
        val grinder = GrinderEntity(id = 1, brand = "Test", model = "Mill ")
        val logs = listOf(
            BrewLogEntity(
                id = 2, startedAt = at(0), recipeId = "custom-1", recipeName = "Slow morning ", recipeAuthor = "Me ",
                beanId = 1, beanLabel = bean.display, grinderId = 1, grinderLabel = grinder.display,
                grindClicks = null, grindSetting = "6.5 by the dial ", tempC = 94, overall = 9,
            ),
            BrewLogEntity(
                id = 1, startedAt = at(1), recipeId = "custom-1", recipeName = "Slow morning ", recipeAuthor = "Me ",
                beanId = 1, beanLabel = bean.display, grinderId = 1, grinderLabel = grinder.display,
                grindClicks = null, grindSetting = "6.5 by the dial ", tempC = 94, overall = 7,
            ),
        )
        val bests = PassportStats.personalBests(logs, listOf(bean), today, zone)
        val cup = bests.bestCup!!
        assertEquals("Slow morning", cup.recipeName)
        // The Best cup line joins this with " · 94 °C": no double space before the dot.
        assertEquals("6.5 by the dial", cup.grindSetting)
        assertEquals("Harbour Blend", bests.bestCoffee!!.name)
        assertEquals("Tidewater Roasters", bests.bestCoffee!!.sub)

        val recipe = PassportStats.rankedRecipes(logs, emptyList()).single()
        assertEquals("Slow morning", recipe.name)
        assertEquals("Me", recipe.sub)

        // A coffee whose row is gone is read from the label stored with the brew.
        val orphan = PassportStats.rankedBeans(logs.map { it.copy(beanId = null) }, emptyList()).single()
        assertEquals("Harbour Blend", orphan.name)
        assertEquals("Tidewater Roasters", orphan.sub)

        // The grind card: a click grinder, two brews of one recipe.
        val clicked = logs.map { it.copy(grindClicks = 60 + it.id.toInt()) }
        val series = PassportStats.grindSeries(clicked, listOf(grinder.copy(clicksPerRotation = 30)))!!
        assertEquals("Mill", series.grinderLabel)
        assertEquals("Slow morning", series.recipeName)

        // Nothing stored was touched.
        assertEquals("6.5 by the dial ", logs.first().grindSetting)
        assertEquals("Harbour Blend ", bean.name)
    }

    @Test
    fun aCoffeesCardLineHasNoSpaceBeforeItsComma() {
        val bean = BeanEntity(name = "Harbour Blend", origin = "Peru ", region = " Cajamarca ")
        assertEquals("Peru, Cajamarca", BeanWords.meta(bean))
        assertEquals("Peru", BeanWords.meta(bean.copy(region = "  ")))
        assertFalse(" ," in BeanWords.meta(bean))
        // The label a brew carries is built from trimmed parts too.
        assertEquals("Tidewater — Harbour Blend", bean.copy(name = "Harbour Blend ", roaster = "Tidewater ").display)
        assertEquals("Test Mill", GrinderEntity(brand = "Test ", model = " Mill ").display)
    }

    @Test
    fun theRestingLineOnHomeNamesTheCoffeeWithoutItsStraySpace() {
        val bean = BeanEntity(id = 7, name = "Harbour Blend ", roastedOnEpochDay = today.toEpochDay() - 2, restDays = 10)
        val state = HomeUiState(beans = listOf(bean), settings = Settings(defaultBeanId = 7), today = today)
        assertEquals("Harbour Blend" to 8L, state.resting)
    }
}
