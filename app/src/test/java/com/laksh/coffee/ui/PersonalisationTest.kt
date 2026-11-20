package com.laksh.coffee.ui

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.recipes.ExpertRecipes
import com.laksh.coffee.ui.onboarding.CustomGrinder
import com.laksh.coffee.ui.onboarding.Onboarding
import com.laksh.coffee.ui.recipes.RecipeDraft
import com.laksh.coffee.ui.screens.gear.BeanWords
import com.laksh.coffee.ui.screens.gear.GearState
import com.laksh.coffee.ui.screens.gear.cleaned
import com.laksh.coffee.ui.screens.gear.hasDetails
import com.laksh.coffee.ui.screens.gear.splitBrandModel
import com.laksh.coffee.ui.screens.home.HomeUiState
import com.laksh.coffee.ui.screens.home.firstForOwnedBrewer
import com.laksh.coffee.ui.screens.recipes.RecipeListState
import com.laksh.coffee.ui.theme.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Units, bean wording, onboarding's writes, the recipe-list filters and Home's suggestion. */
class PersonalisationTest {

    // --------------------------------------------------------------------------- units

    @Test
    fun temperaturesConvertFully() {
        val c = Units()
        assertEquals("93", c.temp(93))
        assertEquals("°C", c.tempUnit)
        assertEquals("93 °C", c.tempWithUnit(93))
        val f = Units(fahrenheit = true)
        assertEquals("199", f.temp(93))
        assertEquals("212", f.temp(100))
        assertEquals("32", f.temp(0))
        assertEquals("199 °F", f.tempWithUnit(93))
    }

    @Test
    fun bagsConvertButBrewTargetsAreNeverOffered() {
        assertEquals("250" to "g", Units().bigWeight(250.0))
        assertEquals("8.8" to "oz", Units(ounces = true).bigWeight(250.0))
        assertEquals("12.0" to "oz", Units(ounces = true).bigWeight(340.19))
        // What you type in ounces comes back as the same grams.
        assertEquals(340.19, Units(ounces = true).bigWeightToGrams(12.0), 0.01)
        assertEquals(250.0, Units().bigWeightToGrams(250.0), 0.0)
    }

    @Test
    fun unitsComeFromSettings() {
        assertEquals(Units(), Units.of(Settings()))
        assertEquals(Units(fahrenheit = true, ounces = true), Units.of(Settings(tempUnit = "f", weightUnit = "oz")))
    }

    // --------------------------------------------------------------------------- beans

    private val today = LocalDate.of(2026, 11, 18).toEpochDay()

    @Test
    fun restingOnlyWhenARestWasRecorded() {
        val roasted = BeanEntity(name = "Test", roastedOnEpochDay = today - 4)
        // No rest recorded: the app never guesses one.
        assertNull(BeanWords.restDaysLeft(roasted, today))
        assertEquals(3L, BeanWords.restDaysLeft(roasted.copy(restDays = 7), today))
        assertNull(BeanWords.restDaysLeft(roasted.copy(restDays = 4), today))
        assertNull(BeanWords.restDaysLeft(roasted.copy(restDays = 2), today))
        assertNull(BeanWords.restDaysLeft(BeanEntity(name = "Test", restDays = 7), today))
        assertEquals("ready in 3 days", BeanWords.restDetail(3))
        assertEquals("ready tomorrow", BeanWords.restDetail(1))
    }

    @Test
    fun theCardSaysWhereHowAndHowDark() {
        val bean = BeanEntity(
            name = "Chelbesa", origin = "Ethiopia", region = "Gedeb", processId = "washed", roastLevelId = "light",
        )
        assertEquals("Ethiopia, Gedeb · Washed · Light", BeanWords.meta(bean))
        assertEquals("Ethiopia, Gedeb · Washed · Light · Decaf", BeanWords.meta(bean.copy(isDecaf = true)))
        assertEquals("", BeanWords.meta(BeanEntity(name = "Bare")))
        // A 1.x free-text origin with no region still reads.
        assertEquals("Kenya", BeanWords.meta(BeanEntity(name = "AA", origin = "Kenya")))
    }

    @Test
    fun altitudeIsOneNumberOrALowHighPair() {
        assertEquals("1700", BeanWords.altitudeText("1700", ""))
        assertEquals("1700-1900", BeanWords.altitudeText("1700", "1900"))
        assertEquals("1700-1900", BeanWords.altitudeText("1900", "1700"))
        assertEquals("1800", BeanWords.altitudeText("1800", "1800"))
        assertEquals("1900", BeanWords.altitudeText("", "1900"))
        assertEquals("", BeanWords.altitudeText("", "x"))
        assertEquals("1700" to "1900", BeanWords.altitudeParts("1700-1900"))
        assertEquals("1700" to "1900", BeanWords.altitudeParts("1,700 to 1900 masl".replace(",", "")))
        assertEquals("1850" to "", BeanWords.altitudeParts("1850 m"))
        assertEquals("" to "", BeanWords.altitudeParts(""))
    }

    @Test
    fun customVarietalsAreAddedOnce() {
        assertEquals("Pink Bourbon", BeanWords.appendVarietal("", "Pink Bourbon"))
        assertEquals("Caturra, Pink Bourbon", BeanWords.appendVarietal("Caturra", " Pink Bourbon "))
        assertEquals("Caturra, Pink Bourbon", BeanWords.appendVarietal("Caturra, Pink Bourbon", "pink bourbon"))
        assertEquals("Caturra", BeanWords.appendVarietal("Caturra", "  "))
    }

    @Test
    fun theEditorKnowsWhatIsBelowTheFoldAndCleansUp() {
        // v4 §4.7: name, roaster, form, roast date, level and bag size sit above the fold.
        assertFalse(hasDetails(BeanEntity(name = "New", roaster = "Square Mile", roastedOnEpochDay = today, roastLevelId = "light", bagSizeG = 250.0)))
        assertTrue(hasDetails(BeanEntity(name = "Old", origin = "Kenya", region = "Nyeri", farm = "X")))
        assertTrue(hasDetails(BeanEntity(name = "Old", processId = "washed")))
        assertTrue(hasDetails(BeanEntity(name = "Old", tastingNotes = "peach")))

        val saved = cleaned(
            BeanEntity(
                name = "  Chelbesa ", roaster = " Sey ", decafMethodId = "swiss-water", isDecaf = false,
                currency = "gbp", priceAmount = 14.0,
            ),
        )
        assertEquals("Chelbesa", saved.name)
        assertEquals("Sey", saved.roaster)
        assertNull(saved.decafMethodId)
        assertEquals("GBP", saved.currency)
        // A currency with no price says nothing, so it is not kept.
        assertEquals("", cleaned(BeanEntity(name = "x", currency = "GBP")).currency)
        assertEquals("swiss-water", cleaned(BeanEntity(name = "x", isDecaf = true, decafMethodId = "swiss-water")).decafMethodId)
    }

    @Test
    fun everyOriginsQuickChipsResolve() {
        // The bean editor shows these as one-tap chips; an id the vocabulary lacks would vanish silently.
        var chips = 0
        Catalog.origins.forEach { o ->
            chips += o.commonVarietals.count { Catalog.varietal(it) != null }
            chips += o.commonProcesses.count { Catalog.process(it) != null }
        }
        assertTrue("no origin offers any quick chip", chips > 0)
        assertTrue(Catalog.beans.lotTypes.any { it.id == "single-estate" })
        assertTrue(Catalog.beans.processes.map { it.group }.toSet().isNotEmpty())
    }

    // ---------------------------------------------------------------------- onboarding

    private val kUltra = Catalog.grinder("1zpresso-k-ultra")!!

    @Test
    fun aNewGrinderIsSavedAsTheDefault() {
        val row = Onboarding.rowFor(kUltra, emptyList())
        assertEquals(0L, row.id)
        assertTrue(row.isDefault)
        assertEquals("1zpresso-k-ultra", row.catalogId)
        assertEquals(100, row.clicksPerRotation)
    }

    @Test
    fun runningSetupAgainNeverDuplicatesAGrinder() {
        val linked = GrinderEntity(id = 4, brand = "1Zpresso", model = "K-Ultra", clicksPerRotation = 10, catalogId = "1zpresso-k-ultra")
        val byId = Onboarding.rowFor(kUltra, listOf(GrinderEntity(id = 2, brand = "Comandante", model = "C40"), linked))
        assertEquals(4L, byId.id)
        assertTrue(byId.isDefault)
        // The row keeps the numbers you gave it; only "Use these numbers" changes those.
        assertEquals(10, byId.clicksPerRotation)
        // The list says 100 a turn, so the Grinders tab will ask which to keep.
        assertTrue(Onboarding.scaleQuestion(byId, kUltra))

        // Typed before the list existed, never linked: brand and model still match.
        val typed = GrinderEntity(id = 9, brand = "1zpresso", model = "k ultra", clicksPerRotation = 10)
        val nowLinked = Onboarding.rowFor(kUltra, listOf(typed))
        assertEquals(9L, nowLinked.id)
        assertEquals("1zpresso-k-ultra", nowLinked.catalogId)
        assertEquals(10, nowLinked.clicksPerRotation)
        // A grinder added fresh from the list has nothing to ask about.
        assertFalse(Onboarding.scaleQuestion(Onboarding.rowFor(kUltra, emptyList()), kUltra))
    }

    @Test
    fun aGrinderTypedByHand() {
        assertNull(Onboarding.rowFor(CustomGrinder(), emptyList()))
        val row = Onboarding.rowFor(CustomGrinder(" Acme ", " Burr 9 ", SettingStyle.Clicks, "24"), emptyList())!!
        assertEquals("Acme", row.brand)
        assertEquals("Burr 9", row.model)
        assertEquals(24, row.clicksPerRotation)
        assertTrue(row.isDefault)
        assertNull(row.catalogId)
        // Clicks per turn means nothing on a stepless dial.
        assertEquals(0, Onboarding.rowFor(CustomGrinder("Acme", "S", SettingStyle.Stepless, "24"), emptyList())!!.clicksPerRotation)
        val have = GrinderEntity(id = 3, brand = "ACME", model = "Burr-9")
        assertEquals(3L, Onboarding.rowFor(CustomGrinder("Acme", "Burr 9"), listOf(have))!!.id)
    }

    @Test
    fun brewerChipsLeadWithTheTenMostCommon() {
        val groups = Onboarding.brewerGroups(Catalog.brewers)
        assertEquals("Most common", groups.first().title)
        // v4 §4.1: what most people own, drip machine first.
        assertEquals(
            listOf(
                "drip-machine", "french-press", "hario-v60-02", "aeropress", "moka-pot",
                "espresso-machine", "drip-bag", "south-indian-filter", "phin", "cold-brew-jar",
            ),
            groups.first().brewers.map { it.first },
        )
        val ids = groups.flatMap { g -> g.brewers.map { it.first } }
        assertEquals("a brewer is listed twice", ids.size, ids.toSet().size)
        assertFalse("other" in ids)
        // Everything else is there, A to Z inside its category.
        assertEquals(Catalog.brewers.count { it.id != "other" }, ids.size)
        groups.drop(1).forEach { g ->
            val titles = g.brewers.map { it.second.lowercase() }
            assertEquals(g.title, titles.sorted(), titles)
        }
    }

    @Test
    fun brandAndModelSplitAtTheFirstSpace() {
        assertEquals("Baratza" to "Encore ESP", splitBrandModel(" Baratza Encore ESP "))
        assertEquals("Niche" to "", splitBrandModel("Niche"))
        assertEquals("" to "", splitBrandModel("  "))
    }

    @Test
    fun gearKnowsWhichGrindersHaveAScaleQuestionWaiting() {
        val g = GrinderEntity(id = 1, brand = "1Zpresso", model = "K-Ultra", uid = "abc")
        assertEquals("grinder-scale:abc", GearState.noticeId(g))
        assertTrue(GearState(grinders = listOf(g), settings = Settings(pendingNotices = setOf("grinder-scale:abc"))).scaleNotice(g))
        assertFalse(GearState(grinders = listOf(g)).scaleNotice(g))
    }

    // --------------------------------------------------------------------- recipe list

    private val library = ExpertRecipes.all
    private val mine = RecipeDraft.duplicate(ExpertRecipes.byId("hoffmann-aeropress")!!, "Me").toRecipe()

    @Test
    fun withNothingChosenEveryRecipeShows() {
        val s = RecipeListState(library)
        assertEquals(library, s.filtered)
        assertFalse(s.hasMine)
        assertFalse(s.hasOwned)
        assertFalse(s.myBrewers)
        assertFalse(s.hasFilters)
        assertEquals(library.map { it.author }.distinct().size, s.people)
    }

    @Test
    fun myBrewersIsOnByDefaultOnceYouOwnSome() {
        val s = RecipeListState(library, ownedBrewerIds = setOf("aeropress"))
        assertTrue(s.hasOwned)
        assertTrue(s.myBrewers)
        assertTrue(s.filtered.isNotEmpty())
        assertTrue(s.filtered.all { it.brewer.id == "aeropress" })
        // The brewer and author chips only offer what is left.
        assertEquals(listOf("aeropress"), s.brewers.map { it.id })
        assertEquals(s.filtered.map { it.author }.distinct().sorted(), s.authors)
        // Turned off, everything is back.
        assertEquals(library, s.copy(myBrewersChoice = false).filtered)
    }

    @Test
    fun ownedBrewersMatchByFamily() {
        // Someone with a V60 01 still gets the V60 02 recipes.
        val small = Catalog.brewers.firstOrNull { it.id != "hario-v60-02" && it.family == Brewers.resolve("hario-v60-02").family }
        org.junit.Assume.assumeTrue(small != null)
        val s = RecipeListState(library, ownedBrewerIds = setOf(small!!.id))
        assertTrue(s.filtered.any { it.brewer.id == "hario-v60-02" })
    }

    @Test
    fun mineShowsOnlyWhatIsYours() {
        val s = RecipeListState(library + mine, mine = true)
        assertTrue(s.hasMine)
        assertEquals(listOf(mine), s.filtered)
        assertTrue(s.hasFilters)
        assertFalse(s.mineEmpty)
        // With nothing of yours, "Mine" shows the guided empty state (Make your own, Duplicate).
        val none = RecipeListState(library, mine = true)
        assertTrue(none.mineEmpty)
        assertTrue(none.filtered.isEmpty())
        assertFalse(RecipeListState(library).mineEmpty)
        assertTrue(RecipeListState.isMine(mine))
        assertFalse(RecipeListState.isMine(library.first()))
        // A built-in you edited is yours too.
        assertTrue(RecipeListState.isMine(library.first().copy(isBuiltIn = false)))
    }

    @Test
    fun brewerAuthorAndSearchStack() {
        val v60 = Brewers.resolve("hario-v60-02")
        val byBrewer = RecipeListState(library, brewer = v60)
        assertTrue(byBrewer.filtered.isNotEmpty())
        assertTrue(byBrewer.filtered.all { it.brewer == v60 })

        val author = byBrewer.authors.first()
        val both = byBrewer.copy(author = author)
        assertTrue(both.filtered.isNotEmpty())
        assertTrue(both.filtered.all { it.brewer == v60 && it.author == author })

        val searched = both.copy(query = "zzzz-no-such-recipe")
        assertTrue(searched.filtered.isEmpty())

        // An author who has nothing for the chosen brewer stops counting, instead of blanking the list.
        val stray = library.first { it.brewer != v60 }.author.takeIf { a -> library.none { it.author == a && it.brewer == v60 } }
        if (stray != null) {
            val s = RecipeListState(library, brewer = v60, author = stray)
            assertNull(s.activeAuthor)
            assertEquals(byBrewer.filtered, s.filtered)
        }
    }

    // ---------------------------------------------------------------------------- home

    @Test
    fun aFreshInstallIsOfferedARecipeForABrewerItOwns() {
        assertNull(firstForOwnedBrewer(library, emptySet()))
        val forAeropress = firstForOwnedBrewer(library, setOf("aeropress"))
        assertNotNull(forAeropress)
        assertEquals("aeropress", forAeropress!!.brewer.id)

        // v4: a fresh install gets the guided first brew (FirstBrew.pick), still for a brewer it owns.
        val fresh = HomeUiState(recipes = library, settings = Settings(ownedBrewerIds = setOf("aeropress")))
        assertEquals("aeropress", fresh.nextRecipe?.brewer?.family)
        // Nothing owned: the easiest recipe that needs least, not simply the first in the list.
        assertEquals(
            com.laksh.coffee.domain.FirstBrew.pick(library, com.laksh.coffee.domain.FirstBrewSettings(emptySet(), true, false, ""), 0).recipe,
            HomeUiState(recipes = library).nextRecipe,
        )
        // What you brewed last still wins over what you own.
        val last = HomeUiState(recipes = library, settings = Settings(lastRecipeId = "kasuya-46", ownedBrewerIds = setOf("aeropress")))
        assertEquals("kasuya-46", last.nextRecipe?.id)
    }

    @Test
    fun thePersonaliseCardIsForExistingUsersUntilDismissed() {
        assertFalse(HomeUiState().showPersonalise)
        assertTrue(HomeUiState(settings = Settings(onboardingAuto = true)).showPersonalise)
        assertFalse(HomeUiState(settings = Settings(onboardingAuto = true, personaliseDismissed = true)).showPersonalise)
    }

    @Test
    fun homeSaysWhenTheDefaultCoffeeIsStillResting() {
        val day = LocalDate.of(2026, 11, 18)
        val bean = BeanEntity(id = 5, name = "Chelbesa", roastedOnEpochDay = day.toEpochDay() - 4, restDays = 7)
        val state = HomeUiState(beans = listOf(bean), settings = Settings(defaultBeanId = 5), today = day)
        assertEquals("Chelbesa" to 3L, state.resting)
        assertNull(state.copy(today = day.plusDays(3)).resting)
        assertNull(HomeUiState(beans = listOf(bean), today = day).resting)
        assertNull(HomeUiState(beans = listOf(bean.copy(restDays = null)), settings = Settings(defaultBeanId = 5), today = day).resting)
    }
}
