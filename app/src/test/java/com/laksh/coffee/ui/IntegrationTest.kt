package com.laksh.coffee.ui

import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.components.DialPart
import com.laksh.coffee.ui.components.DialSteps
import com.laksh.coffee.ui.components.GrindStart
import com.laksh.coffee.ui.nav.AppTab
import com.laksh.coffee.ui.nav.FirstLaunch
import com.laksh.coffee.ui.nav.Routes
import com.laksh.coffee.ui.onboarding.Onboarding
import com.laksh.coffee.ui.recipes.EditorMode
import com.laksh.coffee.ui.recipes.RecipeDraft
import com.laksh.coffee.ui.screens.gear.priceText
import com.laksh.coffee.ui.screens.insights.PassportStats
import com.laksh.coffee.ui.screens.recipes.RecipeListState
import com.laksh.coffee.ui.theme.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wiring package: the first-launch rule, the new routes, units for totals, and brew setup's dial. */
class IntegrationTest {

    // ---------------------------------------------------------------- first launch

    @Test
    fun onlyAnEmptyInstallThatNeverFinishedSetupIsSentToIt() {
        assertTrue(FirstLaunch.shouldOnboard(onboardingDone = false, hasData = false))
        // Upgraded from 1.x: brews exist, the flag has not been written yet. Never onboarded.
        assertFalse(FirstLaunch.shouldOnboard(onboardingDone = false, hasData = true))
        // Finished or skipped: it does not come back by itself, even after everything is deleted.
        assertFalse(FirstLaunch.shouldOnboard(onboardingDone = true, hasData = false))
        assertFalse(FirstLaunch.shouldOnboard(onboardingDone = true, hasData = true))
    }

    // ---------------------------------------------------------------------- routes

    @Test
    fun theBuilderRouteCarriesItsModeAndTheNewRoutesAreNotTabs() {
        assertEquals("recipeEdit?mode=new&from=recipes", Routes.recipeEdit(null, EditorMode.New))
        EditorMode.entries.forEach { mode ->
            assertEquals(mode, Routes.editorMode(mode.name.lowercase()))
        }
        // Anything unreadable opens a blank recipe instead of editing the wrong one.
        assertEquals(EditorMode.New, Routes.editorMode(null))
        assertEquals(EditorMode.New, Routes.editorMode("nonsense"))
        // Setup and the builder are pages pushed over the tabs, never a tab selection.
        assertNull(AppTab.forRoute(Routes.ONBOARDING))
        assertNull(AppTab.forRoute(Routes.RECIPE_EDIT))
        assertEquals(AppTab.Today, AppTab.forRoute(Routes.HOME))
    }

    // ----------------------------------------------------------------------- units

    @Test
    fun lifetimeTotalsFollowTheWeightUnit() {
        assertEquals("850" to "g", Units().totalWeight(850.0))
        assertEquals("1.2" to "kg", Units().totalWeight(1234.0))
        val oz = Units(ounces = true)
        assertEquals("8.8" to "oz", oz.totalWeight(250.0))
        // One pound is 453.59 g; past it the total reads in pounds.
        assertEquals("15.9" to "oz", oz.totalWeight(450.0))
        assertEquals("2.7" to "lb", oz.totalWeight(1234.0))
    }

    // ------------------------------------------------------------- brew setup dial

    /** The owner's kind of row: saved by 1.x at 10 clicks a turn with 40 as the top of its range. */
    private val legacy = GrinderEntity(
        brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks, clicksPerRotation = 10,
        maxSetting = 40.0, catalogId = "1zpresso-k-ultra",
    )

    @Test
    fun aGrindPastTheSavedRangeIsNeverPulledBackIntoIt() {
        val s = legacy.scale()
        // 75 clicks on a row that says 40 is the top: a tap moves it by one step, not to 40.
        assertEquals(74.0, DialSteps.step(s, 75.0, DialPart.Mark, -1)!!, 0.0)
        assertEquals(65.0, DialSteps.step(s, 75.0, DialPart.Turns, -1)!!, 0.0)
        // Up is still held: the range does not grow by tapping.
        assertNull(DialSteps.step(s, 75.0, DialPart.Mark, 1))
        // Inside the range nothing changed.
        assertEquals(40.0, DialSteps.step(s, 39.0, DialPart.Mark, 1)!!, 0.0)
        assertNull(DialSteps.step(s, 40.0, DialPart.Mark, 1))
        // Brew setup gives the steppers the click dial's own room (three digits).
        val wide = s.copy(max = maxOf(s.max, 999.0))
        assertEquals(76.0, DialSteps.step(wide, 75.0, DialPart.Mark, 1)!!, 0.0)
        assertEquals(85.0, DialSteps.step(wide, 75.0, DialPart.Turns, 1)!!, 0.0)
    }

    @Test
    fun theSeededKindOfGrinderOffersAStartingPointForPourOverOnly() {
        // The row setup saves when the K-Ultra is picked from the list.
        val row = Onboarding.rowFor(Catalog.grinder("1zpresso-k-ultra")!!, emptyList())
        val scale = row.scale()
        val start = GrindStart.of(scale, BrewMethod.PourOver)
        assertNotNull(start)
        // The middle of 1Zpresso's 75 to 90, on a real click.
        assertEquals(82.0, start!!.value, 1.0)
        assertTrue(start.detail.contains("1Zpresso"))
        // A row whose turn size disagrees with the catalog has a notice pending and gets no hint.
        assertNull(GrindStart.of(legacy.scale(), BrewMethod.PourOver))
    }

    // ----------------------------------------------------------------- seed recipe

    @Test
    fun aScaledCopyOfABuiltInIsARecipeOfYourOwn() {
        // What the debug seed and Recipe detail's Duplicate both save.
        val base = ExpertRecipes.byId("hoffmann-v60")!!
        val mine = RecipeDraft.duplicate(base.scaledToDose(15.0), author = "").toRecipe()
        assertEquals(15.0, mine.doseG, 0.0)
        assertEquals(250.0, mine.waterG, 0.0)
        assertEquals("custom", mine.fidelity)
        assertEquals(base.id, mine.basedOn)
        assertTrue(mine.id.startsWith("custom-"))
        // Saved recipes are stored as not built-in, which is what puts them under "Mine".
        assertTrue(RecipeListState.isMine(mine.copy(isBuiltIn = false)))
        assertFalse(RecipeListState.isMine(base))
    }

    @Test
    fun aRecipeIdFromAnImportedFileCannotBreakItsRoute() {
        // The app's own ids are one path segment as they are.
        assertEquals("recipe/hoffmann-v60?from=recipes", Routes.recipe("hoffmann-v60", "recipes"))
        Catalog.recipes.forEach { assertEquals(it.id, Routes.segment(it.id)) }
        assertEquals("user-1a2b_3c.4", Routes.segment("user-1a2b_3c.4"))
        // A slash, a hash or a question mark from a hand-edited backup stays inside the segment.
        assertEquals("recipe/my%2Fv60?from=home", Routes.recipe("my/v60", "home"))
        assertEquals("brew/iced%232%3F%20x", Routes.brew("iced#2? x"))
        assertEquals("my/v60", java.net.URLDecoder.decode(Routes.segment("my/v60"), "UTF-8"))
    }

    // ---------------------------------------------------------------------- brewers

    @Test
    fun aBrewerIsOneStampWhateverWordsItsBrewsWereSavedWith() {
        val v60 = Brewers.byId("hario-v60-02")!!
        val title = CatalogDocs.brewerTitle(v60)
        assertTrue("the saved label names the maker", title.startsWith("Hario"))
        assertEquals(v60, Brewers.resolveOrNull(title))
        val logs = listOf(
            // 1.x, and 1.x through the upgrade (label and id).
            BrewLogEntity(id = 1, brewer = "Hario V60"),
            BrewLogEntity(id = 2, brewer = "Hario V60", brewerId = "hario-v60-02"),
            // The catalog's bare name, and the label 2.0 saves.
            BrewLogEntity(id = 3, brewer = v60.label, brewerId = "hario-v60-02"),
            BrewLogEntity(id = 4, brewer = title, brewerId = "hario-v60-02"),
            BrewLogEntity(id = 5, brewer = "Chemex"),
            BrewLogEntity(id = 6, brewer = "Grandad's percolator"),
            BrewLogEntity(id = 7, brewer = ""),
        )
        val stamps = PassportStats.stamps(logs)
        assertEquals(listOf(4, 1, 1), stamps.map { it.count })
        assertEquals(v60, stamps[0].brewer)
        assertEquals("chemex", stamps[1].brewer?.id)
        // A brewer the app does not know is still a stamp, under its own words.
        assertNull(stamps[2].brewer)
        assertEquals("Grandad's percolator", stamps[2].label)
    }

    // ------------------------------------------------------------------------ price

    @Test
    fun aPriceReadsBackAsItWasTyped() {
        assertEquals("12.99", priceText(12.99))
        assertEquals("12.49", priceText(12.49))
        assertEquals("13", priceText(13.0))
        assertEquals("0.5", priceText(0.5))
        assertEquals("1250", priceText(1250.0))
        assertEquals("", priceText(Double.NaN))
    }
}
