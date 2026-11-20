package com.laksh.coffee.ui

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.BrewerFamilies
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.recipes.ExpertRecipes
import androidx.compose.ui.input.key.Key
import com.laksh.coffee.domain.ShotTarget
import com.laksh.coffee.domain.Temperatures
import com.laksh.coffee.domain.preinfusion
import com.laksh.coffee.ui.screens.brew.AgainPrefill
import com.laksh.coffee.ui.screens.brew.BrewKeys
import com.laksh.coffee.ui.screens.brew.BrewPhase
import com.laksh.coffee.ui.screens.brew.BrewUiState
import com.laksh.coffee.ui.screens.brew.BrewLayouts
import com.laksh.coffee.ui.screens.brew.BrewWords
import com.laksh.coffee.ui.screens.brew.GrindChoice
import com.laksh.coffee.ui.screens.brew.RunLayout
import com.laksh.coffee.ui.screens.brew.ShotPhase
import com.laksh.coffee.ui.screens.brew.asksChime
import com.laksh.coffee.ui.screens.home.HomeUiState
import com.laksh.coffee.ui.screens.home.firstBrewChecklist
import com.laksh.coffee.ui.screens.home.firstBrewNeeds
import com.laksh.coffee.ui.screens.recipes.RecipeListState
import com.laksh.coffee.ui.screens.recipes.RecipeSort
import com.laksh.coffee.ui.screens.recipes.RecipeStats
import com.laksh.coffee.ui.screens.review.QuickReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V4-AND2: the pure rules behind brew, review, Today and Recipes. */
class BrewReviewTodayTest {

    // ------------------------------------------------------------------ layouts (§3.5)

    @Test
    fun theRunningLayoutFollowsTheWindow() {
        // Phones upright, tablets upright, foldables.
        assertEquals(RunLayout.Portrait, BrewLayouts.of(411f, 914f))
        assertEquals(RunLayout.Portrait, BrewLayouts.of(800f, 1280f))
        // Phones on their side and every window under 480 tall: short, never scrolling.
        assertEquals(RunLayout.Short, BrewLayouts.of(914f, 411f))
        assertEquals(RunLayout.Short, BrewLayouts.of(640f, 360f))
        assertEquals(RunLayout.Short, BrewLayouts.of(720f, 450f))
        // Tablets on their side, laptops, desktops.
        assertEquals(RunLayout.Stage, BrewLayouts.of(1280f, 800f))
        assertEquals(RunLayout.Stage, BrewLayouts.of(1024f, 768f))
        // Exactly 480 tall is no longer short; a square window is not wider than tall.
        assertEquals(RunLayout.Stage, BrewLayouts.of(700f, 480f))
        assertEquals(RunLayout.Portrait, BrewLayouts.of(700f, 700f))
        // Under 600 wide is never the stage, however it is turned.
        assertEquals(RunLayout.Portrait, BrewLayouts.of(590f, 500f))
    }

    @Test
    fun theStageClockIsTheClampAndBiggerThanThePhones() {
        assertEquals(96f, BrewLayouts.stageClock(500f), 0.01f)
        assertEquals(122.88f, BrewLayouts.stageClock(768f), 0.01f)
        assertEquals(200f, BrewLayouts.stageClock(1600f), 0.01f)
        // 1024x768: "about 123 px" clock and "about 54 px" grams (§3.5).
        assertEquals(53.76f, BrewLayouts.stageGrams(768f), 0.01f)
        // A tablet's clock is visibly larger than the phone's 88.
        assertTrue(BrewLayouts.stageClock(800f) > 88f)
        assertEquals(48f, BrewLayouts.shortClock(200f), 0.01f)
        assertEquals(96f, BrewLayouts.shortClock(900f), 0.01f)
        assertEquals(380f, BrewLayouts.stageColumn(900f), 0.01f)
        assertEquals(480f, BrewLayouts.stageColumn(2000f), 0.01f)
    }

    @Test
    fun thePortraitRingNeverShrinksBelowThePhoneRing() {
        // A short phone window still gets the 240 ring; the step card and page scroll instead.
        assertEquals(240f, BrewLayouts.portraitRing(411f, 600f, 16f, 430f), 0.01f)
        // A tall phone stops at the phone maximum, so the step card keeps its words.
        assertEquals(280f, BrewLayouts.portraitRing(411f, 914f, 16f, 430f), 0.01f)
        // Room to spare on a medium window: up to 360.
        assertEquals(360f, BrewLayouts.portraitRing(800f, 1280f, 16f, 430f), 0.01f)
        // Narrower than the ring itself: the width wins.
        assertEquals(200f, BrewLayouts.portraitRing(232f, 900f, 16f, 430f), 0.01f)
        assertTrue(BrewLayouts.portraitUsesBigClock(240f, 210))
        assertFalse(BrewLayouts.portraitUsesBigClock(230f, 210))
        // Four-digit clocks need a wider ring.
        assertFalse(BrewLayouts.portraitUsesBigClock(260f, 900))
        assertTrue(BrewLayouts.portraitUsesBigClock(330f, 900))
    }

    @Test
    fun setupGoesTwoColumnsOnWideAndLandscapeWindows() {
        assertFalse(BrewLayouts.setupTwoColumns(411f, 914f))
        assertTrue(BrewLayouts.setupTwoColumns(914f, 411f))
        assertTrue(BrewLayouts.setupTwoColumns(1280f, 800f))
        assertTrue(BrewLayouts.setupTwoColumns(900f, 1200f))
        assertFalse(BrewLayouts.setupTwoColumns(800f, 1280f))
    }

    // --------------------------------------------------------------- announcements (A4)

    @Test
    fun theScreenReaderHearsTheStepTheEndAndTenSecondsLeft() {
        val r = ExpertRecipes.all.first { it.steps.size >= 3 && it.steps[1].targetWaterG != null }
        val s = BrewWords.step(r, 1)
        assertTrue(s, s.startsWith("Step 2 of ${r.steps.size}, "))
        assertTrue(s, s.endsWith("Next at ${com.laksh.coffee.core.clock(r.steps[2].startSec)}."))
        assertEquals("Brew done, 3:32. Rate this brew.", BrewWords.done(212))
        val long = r.steps.first { it.durationSec > 30 }
        assertTrue(BrewWords.saysTenLeft(long, long.endSec - 10))
        assertFalse(BrewWords.saysTenLeft(long, long.endSec - 11))
        assertTrue(BrewWords.banner(r.steps[1]).startsWith("NEXT STEP · "))
    }

    // ------------------------------------------------------------------ shot mode (§4.9)

    @Test
    fun theShotButtonStartsThenStopsThenWaitsForTheYield() {
        assertEquals(ShotPhase.Ready, ShotPhase.of(running = false, elapsedMs = 0, stopped = false))
        assertEquals(ShotPhase.Pulling, ShotPhase.Ready.onPrimary())
        assertEquals(ShotPhase.Stopped, ShotPhase.Pulling.onPrimary())
        assertEquals(ShotPhase.Stopped, ShotPhase.Stopped.onPrimary())
        assertEquals("Start shot", ShotPhase.Ready.primaryLabel)
        assertEquals("Stop shot", ShotPhase.Pulling.primaryLabel)
        assertNull(ShotPhase.Stopped.primaryLabel)
        assertEquals(ShotPhase.Pulling, ShotPhase.of(running = true, elapsedMs = 3000, stopped = false))
        assertEquals(ShotPhase.Stopped, ShotPhase.of(running = false, elapsedMs = 28000, stopped = true))
    }

    @Test
    fun theShotTargetComesFromTheRecipeWhenItSaysOne() {
        val classic = ExpertRecipes.byId("3fe-classic-double")
        if (classic != null) {
            val t = ShotTarget.of(classic)
            assertTrue(t.stated)
            assertEquals("25–30 s", t.label)
            assertTrue(t.contains(27))
            assertFalse(t.contains(31))
        }
        val hedrick = ExpertRecipes.byId("hedrick-espresso")
        if (hedrick != null) {
            // "first drops around 8 to 12 seconds" is pre-infusion, not the shot.
            val t = ShotTarget.of(hedrick)
            assertFalse(t.stated)
            assertEquals(35, t.highSec)
            assertEquals(0 until 12, hedrick.preinfusion())
        }
    }

    // ------------------------------------------------------------- quick review (§4.4)

    @Test
    fun quickTogglesWriteTheSameFaultsAsTheDetailedReview() {
        assertEquals(listOf("Too sour", "Too bitter", "Too weak", "Too strong"), QuickReview.TOGGLES.map { it.first })
        assertEquals("Sour", QuickReview.faultFor("Too sour"))
        assertEquals("Bitter", QuickReview.faultFor("Too bitter"))
        assertEquals("Too weak", QuickReview.faultFor("Too weak"))
        assertEquals("Too strong", QuickReview.faultFor("Too strong"))
        assertNull(QuickReview.faultFor("Too hot"))

        var faults = listOf("Astringent")
        faults = QuickReview.toggle(faults, "Too sour")
        assertEquals(listOf("Astringent", "Sour"), faults)
        assertTrue(QuickReview.isOn("Too sour", faults))
        faults = QuickReview.toggle(faults, "Too sour")
        // The detailed fault chosen before stays.
        assertEquals(listOf("Astringent"), faults)
        // Every quick fault is in the detailed vocabulary too.
        QuickReview.TOGGLES.forEach { (_, f) -> assertTrue(f, f in com.laksh.coffee.domain.Descriptors.FAULTS) }

        assertTrue(QuickReview.isQuick("quick"))
        assertTrue(QuickReview.isQuick(""))
        assertFalse(QuickReview.isQuick("detailed"))
    }

    @Test
    fun aFrenchPressReviewHasNoDrawdownOrBed() {
        val s = com.laksh.coffee.domain.ReviewSections.of(Brewers.byId("french-press"))
        assertFalse(s.drawdown)
        assertFalse(s.bed)
        val v60 = com.laksh.coffee.domain.ReviewSections.of(Brewers.byId("hario-v60-02"))
        assertTrue(v60.drawdown)
        assertTrue(v60.bed)
    }

    // --------------------------------------------------------------- brew again (§4.10)

    private val clicky = GrinderEntity(id = 3, brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks)
    private val blade = GrinderEntity(id = 4, brand = "Any", model = "Blade grinder", settingStyle = SettingStyle.Stepless, settingUnit = "s")
    private val bean = BeanEntity(id = 9, name = "Kiamabara")

    @Test
    fun brewAgainTakesTheBrewsCoffeeGrinderGrindAndDose() {
        val log = BrewLogEntity(id = 12, recipeId = "hoffmann-v60", beanId = 9, grinderId = 3, grindClicks = 72, grindSetting = "72 clicks", doseG = 16.0, waterG = 260.0, tempC = 95, grindKind = "dial")
        val p = AgainPrefill.of(log, listOf(bean), listOf(clicky, blade))
        assertEquals(9L, p.beanId)
        assertEquals(3L, p.grinderId)
        assertFalse(p.preground)
        assertEquals(72, p.grindClicks)
        assertEquals("", p.grindSetting)
        assertNull(p.bladeSec)
        assertEquals(16.0, p.doseG, 0.0)
        assertEquals(260.0, p.waterG, 0.0)
        assertEquals(95, p.tempC)
    }

    @Test
    fun brewAgainKeepsPregroundAndBladeSecondsAndDropsWhatIsGone() {
        val pre = AgainPrefill.of(BrewLogEntity(grindKind = "preground", grindSetting = "As bought", grinderId = 3, beanId = 77), listOf(bean), listOf(clicky))
        assertTrue(pre.preground)
        assertNull(pre.grinderId)
        // A coffee deleted since is left out, not guessed.
        assertNull(pre.beanId)

        val b = AgainPrefill.of(BrewLogEntity(grinderId = 4, grindKind = "blade", grindSetting = "12 s", grindValue = 12.0), emptyList(), listOf(blade))
        assertEquals(4L, b.grinderId)
        assertEquals(12, b.bladeSec)

        val dial = AgainPrefill.of(BrewLogEntity(grinderId = 99, grindSetting = " 3.5 "), emptyList(), emptyList())
        assertNull(dial.grinderId)
        assertEquals("3.5", dial.grindSetting)
    }

    @Test
    fun aDeletedRecipeBrewsAgainAsAOneOff() {
        val log = BrewLogEntity(recipeId = "gone", recipeName = "My old one", brewerId = "french-press", doseG = 30.0, waterG = 500.0, totalTimeSec = 240, tempC = 94)
        val r = AgainPrefill.fallbackRecipe(log)
        assertEquals("Brew again", r.name)
        assertEquals("gone", r.id)
        assertEquals(1, r.steps.size)
        assertEquals("Brew", r.steps.single().title)
        assertEquals(240, r.totalSec)
        assertEquals(30.0, r.doseG, 0.0)
        assertEquals("french-press", r.brewer.id)
    }

    // ------------------------------------------------------------------ grind choice

    @Test
    fun pregroundStartsSelectedForGroundCoffeeOrNoGrinder() {
        assertTrue(GrindChoice.startsPreground(bean.copy(form = "ground"), buysGround = false, grinderCount = 2))
        assertTrue(GrindChoice.startsPreground(null, buysGround = true, grinderCount = 0))
        assertFalse(GrindChoice.startsPreground(null, buysGround = true, grinderCount = 1))
        assertFalse(GrindChoice.startsPreground(bean, buysGround = false, grinderCount = 0))
        assertEquals(GrindChoice.Preground, GrindChoice.of(true, clicky))
        assertEquals(GrindChoice.Blade, GrindChoice.of(false, blade))
        assertEquals(GrindChoice.Dial, GrindChoice.of(false, clicky))
        assertEquals(GrindChoice.Dial, GrindChoice.of(false, null))
    }

    @Test
    fun aBrewWithNoGrinderIsRecordedWithAnEmptyGrindKindAsOnTheWeb() {
        // Backup v2.1: "" is dial or unknown. Android used to write "dial" here.
        assertEquals("", GrindChoice.recordKind(false, null))
        assertEquals("dial", GrindChoice.recordKind(false, clicky))
        assertEquals("blade", GrindChoice.recordKind(false, blade))
        assertEquals("preground", GrindChoice.recordKind(true, null))
        assertEquals("preground", GrindChoice.recordKind(true, clicky))
    }

    // ------------------------------------------------------------------ brew keys

    @Test
    fun spaceAndTheArrowsBelongToAFocusedButton() {
        // A focused Next or Finish: Space presses it, the arrows move focus.
        listOf(Key.Spacebar, Key.Enter, Key.DirectionLeft, Key.DirectionRight, Key.DirectionDown).forEach {
            assertFalse(BrewKeys.reachesRoot(it, rootFocused = false))
            assertTrue(BrewKeys.reachesRoot(it, rootFocused = true))
        }
        // The letters work wherever focus is.
        listOf(Key.N, Key.F, Key.M, Key.X, Key.Escape).forEach { assertTrue(BrewKeys.reachesRoot(it, rootFocused = false)) }
    }

    // ------------------------------------------------------------------ auto-advance

    @Test
    fun withAutoAdvanceOffTheCardWaitsAfterTheClockPassesTheStep() {
        val r = ExpertRecipes.all.first { it.steps.size >= 3 && it.brewer.method != com.laksh.coffee.domain.BrewMethod.Espresso }
        val running = BrewUiState(loading = false, recipe = r, phase = BrewPhase.Running, settings = Settings(autoAdvanceSteps = false))
        assertFalse(running.waitingForTap)
        assertTrue(running.copy(clockStepIndex = 1).waitingForTap)
        // The setting on (the default): the card follows the clock, nothing waits.
        assertFalse(running.copy(clockStepIndex = 1, settings = Settings()).waitingForTap)
        assertFalse(running.copy(clockStepIndex = 1, done = true).waitingForTap)
        assertEquals("Time for the next step.", BrewWords.NEXT_STEP)
    }

    // ------------------------------------------------------------------ units

    @Test
    fun stepTitlesFollowTheTemperatureUnit() {
        val hybrid = ExpertRecipes.byId("kasuya-switch-hybrid")!!
        val f = Temperatures.renderSteps(hybrid, "f")
        assertTrue(f.steps.any { it.title.contains("158 °F") })
        assertFalse(f.steps.any { it.title.contains("°C") })
        assertTrue(Temperatures.renderSteps(hybrid, "c") === hybrid)
    }

    @Test
    fun theChimeIsAskedOnceForLongSteps() {
        val long = ExpertRecipes.all.first { r -> r.steps.any { it.durationSec > 60 } }
        assertTrue(asksChime(long, soundCuesAsked = false))
        assertFalse(asksChime(long, soundCuesAsked = true))
    }

    // ------------------------------------------------------------------ Today (§4.6)

    @Test
    fun aDripMachineOwnersFirstBrewIsNeverBorrowedFromAV60() {
        val library = ExpertRecipes.all
        val state = HomeUiState(recipes = library, settings = Settings(ownedBrewerIds = setOf("drip-machine"), hasScale = false))
        val pick = state.nextRecipe
        if (library.any { it.brewer.id == "drip-machine" }) {
            assertNotNull(pick)
            assertEquals("drip-machine", BrewerFamilies.of(pick!!.brewer))
            // Spoons and ml, a mug, no kettle and no scale for a machine with its own tank.
            val needs = firstBrewNeeds(pick, hasScale = false)
            assertFalse(needs.toString(), "Kettle" in needs)
            assertFalse(needs.toString(), "Kitchen scale" in needs)
            assertTrue(needs.toString(), needs.any { it.endsWith("tablespoons of coffee") })
            assertTrue(needs.toString(), needs.any { it.endsWith("ml water") })
            assertEquals("Fill the machine's tank with fresh water.", firstBrewChecklist(pick).first())
        } else {
            assertNull(pick)
        }
        assertTrue(pick == null || pick.brewer.id != "hario-v60-02")
    }

    @Test
    fun theFirstBrewChecklistIsTheSpecsForKettleMethods() {
        val fp = ExpertRecipes.all.first { it.brewer.id == "french-press" }
        assertEquals(
            listOf(
                "Boil the kettle, then let it sit for 30 seconds.",
                "Measure the coffee and the water.",
                "Press Start as you pour. We'll tell you each step.",
            ),
            firstBrewChecklist(fp),
        )
        assertTrue("Kettle" in firstBrewNeeds(fp, hasScale = true))
    }

    @Test
    fun anOwnedBrewerWithoutARecipeIsNamedNotFilledIn() {
        // A Melodrip matches nothing, so the person gets every recipe to choose from.
        val melodrip = HomeUiState(recipes = ExpertRecipes.all, settings = Settings(ownedBrewerIds = setOf("melodrip")))
        assertTrue(melodrip.firstPick.noRecipeFor.isEmpty())
        // No recipes for a family: nothing offered, the brewer named.
        val noneForPress = HomeUiState(
            recipes = ExpertRecipes.all.filter { BrewerFamilies.of(it.brewer) != BrewerFamilies.of(Brewers.resolve("french-press")) },
            settings = Settings(ownedBrewerIds = setOf("french-press")),
        )
        assertNull(noneForPress.nextRecipe)
        assertEquals(listOf("french-press"), noneForPress.firstPick.noRecipeFor)
    }

    // ---------------------------------------------------------------- Recipes (§3.2)

    @Test
    fun theListSortsWithoutLosingTheLibrarysOrderOnTies() {
        val library = ExpertRecipes.all
        val base = RecipeListState(library)
        assertEquals(library, base.filtered)
        val az = base.copy(sort = RecipeSort.AZ).filtered
        assertEquals(az.map { it.name.lowercase() }.sorted(), az.map { it.name.lowercase() })
        val a = library[3].id
        val b = library[7].id
        val stats = mapOf(a to RecipeStats(2, 9), b to RecipeStats(5, 6))
        val most = RecipeListState(library, sort = RecipeSort.MostBrewed, stats = stats).filtered
        assertEquals(listOf(b, a), most.take(2).map { it.id })
        val best = RecipeListState(library, sort = RecipeSort.BestRated, stats = stats).filtered
        assertEquals(listOf(a, b), best.take(2).map { it.id })
        // The rest keep the library's order.
        assertEquals(library.filter { it.id != a && it.id != b }, most.drop(2))
    }

    @Test
    fun aMelodripAloneDoesNotNarrowTheLibrary() {
        val s = RecipeListState(ExpertRecipes.all, ownedBrewerIds = setOf("melodrip"))
        assertFalse(s.hasOwned)
        assertEquals(ExpertRecipes.all, s.filtered)
    }
}
