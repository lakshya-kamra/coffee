package com.laksh.coffee.domain.ai

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.recipes.ExpertRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DataQualityTest {

    private val v60: Recipe = ExpertRecipes.byId("hoffmann-v60")!!
    private val kasuya: Recipe = ExpertRecipes.byId("kasuya-46")!!
    private val aeropress: Recipe = ExpertRecipes.byId("hoffmann-aeropress")!!

    private val grinder = GrinderEntity(
        id = 1,
        brand = "1Zpresso",
        model = "K-Ultra",
        burrType = BurrType.Conical,
        burrSizeMm = "48",
        settingStyle = SettingStyle.Clicks,
        clicksPerRotation = 10,
        lowerIsFiner = true,
    )

    private val bean = BeanEntity(
        id = 1,
        name = "Harbour Blend",
        roaster = "Tidewater",
        roastLevelId = "light",
        roastedOnEpochDay = 20_000,
        createdAt = 1_000L,
    )

    // 2026-09-14 08:12 UTC; a fixed instant so nothing depends on when the test runs.
    private val brewedAt = 1_789_373_520_000L

    /** A clean Hoffmann V60 brew: every check should pass. */
    private fun v60Log(
        id: Long = 10,
        grindClicks: Int? = 66,
        total: Int = 208,
        drawdown: Int? = 102,
        cup: Double? = 439.0,
        taste: TasteProfile = TasteProfile(5, 7, 3, 6, 8, 7, 7),
        doseG: Double = 30.0,
        daysOffRoast: Long? = 17,
        startedAt: Long = brewedAt,
    ) = BrewLogEntity(
        id = id,
        // Fixed, as a saved brew's is: the hash reads it.
        uid = "brew-$id",
        startedAt = startedAt,
        recipeId = v60.id,
        recipeName = v60.name,
        recipeAuthor = v60.author,
        brewer = v60.brewer.label,
        beanId = bean.id,
        beanLabel = bean.display,
        daysOffRoast = daysOffRoast,
        grinderId = grinder.id,
        grinderLabel = grinder.display,
        grindSetting = grindClicks?.let { grinder.describeClicks(it) }.orEmpty(),
        grindClicks = grindClicks,
        doseG = doseG,
        waterG = 500.0,
        tempC = 100,
        totalTimeSec = total,
        drawdownSec = drawdown,
        beverageG = cup,
        taste = taste,
        overall = 8,
    )

    private fun kasuyaLog(total: Int, drawdown: Int) = BrewLogEntity(
        id = 20,
        startedAt = brewedAt,
        recipeId = kasuya.id,
        recipeName = kasuya.name,
        brewer = kasuya.brewer.label,
        beanId = bean.id,
        beanLabel = bean.display,
        daysOffRoast = 12,
        grinderId = grinder.id,
        grindClicks = 72,
        grindSetting = "72 clicks (7 turns + 2)",
        doseG = 20.0,
        waterG = 300.0,
        tempC = 92,
        totalTimeSec = total,
        drawdownSec = drawdown,
        beverageG = 262.0,
        taste = TasteProfile(6, 6, 3, 5, 7, 6, 7),
        overall = 7,
    )

    private fun codes(log: BrewLogEntity, recipe: Recipe? = v60, b: BeanEntity? = bean): List<FlagCode> =
        DataQuality.check(log, recipe, grinder, b).map { it.code }

    private fun flag(log: BrewLogEntity, code: FlagCode, recipe: Recipe? = v60, b: BeanEntity? = bean): DataFlag? =
        DataQuality.check(log, recipe, grinder, b).firstOrNull { it.code == code }

    // ------------------------------------------------------------- the checks

    @Test
    fun cupThatLeavesTooMuchInTheGroundsIsDistrusted() {
        val f = flag(v60Log(cup = 370.0), FlagCode.RETENTION_HIGH)
        assertNotNull(f)
        assertEquals(FlagSeverity.Distrust, f!!.severity)
        assertEquals(Fields.BEVERAGE, f.field)
        assertTrue(f.message.contains("370 g"))
    }

    @Test
    fun cupThatLeavesAlmostNothingInTheGroundsIsDistrusted() {
        val f = flag(v60Log(cup = 498.8), FlagCode.RETENTION_LOW)
        assertNotNull(f)
        assertEquals(FlagSeverity.Distrust, f!!.severity)
        assertFalse(FlagCode.BEVERAGE_OVER_WATER in codes(v60Log(cup = 498.8)))
    }

    @Test
    fun zeroClicksIsDistrusted() {
        val f = flag(v60Log(grindClicks = 0, total = 202), FlagCode.GRIND_ZERO)
        assertNotNull(f)
        assertEquals(FlagSeverity.Distrust, f!!.severity)
        assertTrue(f.message.contains("3:22"))
    }

    @Test
    fun drawdownAsLongAsTheBrewIsDistrusted() {
        val found = codes(v60Log(total = 202, drawdown = 202))
        assertTrue(FlagCode.DRAWDOWN_GE_TOTAL in found)
        assertFalse(FlagCode.DRAWDOWN_AFTER_END in found)
    }

    @Test
    fun kasuyaDrawdownMustFitAfterTheLastPour() {
        // The 4:6 last pour ends at 3:15, so a 3:34 brew leaves at most 19 s.
        assertTrue(FlagCode.DRAWDOWN_AFTER_END in codes(kasuyaLog(total = 214, drawdown = 98), kasuya))
        val clean = codes(kasuyaLog(total = 214, drawdown = 19), kasuya)
        assertFalse(FlagCode.DRAWDOWN_AFTER_END in clean)
        assertFalse(FlagCode.DRAWDOWN_GE_TOTAL in clean)
        assertFalse(FlagCode.DRAWDOWN_ZERO in clean)
    }

    @Test
    fun untouchedSlidersAreDistrusted() {
        val f = flag(v60Log(taste = TasteProfile()), FlagCode.TASTE_UNTOUCHED)
        assertNotNull(f)
        assertEquals(FlagSeverity.Distrust, f!!.severity)
    }

    @Test
    fun missingRoastDateOnALightRoastSaysWhyItMatters() {
        val undated = bean.copy(roastedOnEpochDay = null)
        val f = flag(v60Log(daysOffRoast = null), FlagCode.ROAST_DATE_MISSING, b = undated)
        assertNotNull(f)
        assertEquals(FlagSeverity.Note, f!!.severity)
        assertTrue(f.message.contains("first three weeks"))
    }

    @Test
    fun cleanBrewHasNoDistrustFlags() {
        val flags = DataQuality.check(v60Log(total = 208, drawdown = 102, cup = 439.0), v60, grinder, bean)
        assertTrue(flags.none { it.severity == FlagSeverity.Distrust })
    }

    @Test
    fun underOneTurnHidesTheGuessFromTheModel() {
        val f = flag(v60Log(grindClicks = 7), FlagCode.GRIND_UNDER_ONE_TURN)
        assertNotNull(f)
        assertTrue(f!!.message.contains("70 clicks"))
        assertFalse(f.promptMessage.contains("70"))
    }

    @Test
    fun distrustedFieldsCoverOnlyThatBrew() {
        val bad = v60Log(id = 1, grindClicks = 0)
        val good = v60Log(id = 2)
        val flags = DataQuality.check(bad, v60, grinder, bean) + DataQuality.check(good, v60, grinder, bean)
        assertEquals(setOf(Fields.GRIND), DataQuality.distrustedFields(flags, 1))
        assertTrue(DataQuality.distrustedFields(flags, 2).isEmpty())
    }

    // ------------------------------------------------------ last trusted clicks

    @Test
    fun lastTrustedClicksNeverAnchorsOnAnotherRecipe() {
        val target = v60Log(id = 5, grindClicks = 0)
        val aero = BrewLogEntity(
            id = 4,
            startedAt = brewedAt - 86_400_000L,
            recipeId = aeropress.id,
            recipeName = aeropress.name,
            brewer = aeropress.brewer.label,
            grinderId = grinder.id,
            grindClicks = 55,
            doseG = 11.0,
            waterG = 200.0,
            tempC = 100,
            totalTimeSec = 200,
        )
        assertNull(DataQuality.lastTrustedClicks(target, listOf(aero), emptyMap()))

        val earlierV60 = v60Log(id = 3, grindClicks = 67, startedAt = brewedAt - 2 * 86_400_000L)
        val found = DataQuality.lastTrustedClicks(target, listOf(aero, earlierV60), emptyMap())
        assertEquals(3L, found?.id)
    }

    @Test
    fun lastTrustedClicksSkipsDistrustedGrinds() {
        val target = v60Log(id = 5, grindClicks = 0)
        // 7 clicks on a V60 is the dial reading, not a grind: flagged, so never an anchor.
        val dialReading = v60Log(id = 4, grindClicks = 7, startedAt = brewedAt - 86_400_000L)
        val flags = DataQuality.check(dialReading, v60, grinder, bean).groupBy { it.brewId }
        assertNull(DataQuality.lastTrustedClicks(target, listOf(dialReading), flags))
        assertEquals(4L, DataQuality.lastTrustedClicks(target, listOf(dialReading), emptyMap())?.id)
    }

    // ------------------------------------------------------------ the prompt

    private fun brewInput(log: BrewLogEntity, history: List<BrewLogEntity> = emptyList(), b: BeanEntity = bean) =
        BrewPromptInput(
            log = log,
            recipe = v60,
            bean = b,
            grinder = grinder,
            history = history,
            recipes = mapOf(v60.id to v60),
            beans = mapOf(b.id to b),
            grinders = mapOf(grinder.id to grinder),
        )

    @Test
    fun promptSendsNothingDerivedFromDistrustedFields() {
        val log = v60Log(grindClicks = 0, total = 202, cup = 370.0)
        val built = AnalysisPrompt.brew(brewInput(log))
        // The logged setting as text. ("a full turn of 10 clicks" in the move sizes is the grinder's, not the log's.)
        assertFalse(built.user.contains("\"0 clicks"))
        assertFalse(built.user.contains("currentClicks\":0"))
        assertFalse(built.user.contains("4.3"))
        assertFalse(built.user.contains("370"))
        assertTrue(built.user.contains("\"grindClicks\":null"))
        // A click grinder's grindSetting is only a text snapshot of the dial words: never sent.
        assertFalse(built.user.contains("grindSetting"))
        assertTrue(built.user.contains("\"beverageG\":null"))
        assertTrue(built.user.contains("\"retainedGPerGCoffee\":null"))
        assertTrue(built.user.contains("\"currentClicks\":null"))
        assertTrue(built.user.contains("\"currentOnDial\":null"))
        // Both findings are still explained, in words that carry no number.
        assertTrue(built.user.contains("Grind was logged as zero"))
        assertTrue(built.user.contains("The cup weight means far more water"))
    }

    @Test
    fun earlierBrewsAreRedactedToo() {
        val older = v60Log(id = 3, cup = 370.0, startedAt = brewedAt - 86_400_000L)
        val built = AnalysisPrompt.brew(brewInput(v60Log(id = 4), history = listOf(older)))
        assertFalse(built.user.contains("4.3"))
        assertTrue(built.user.contains("\"distrusted\":[\"beverageG\"]"))
    }

    @Test
    fun redactedKeysCoverEveryDerivedValue() {
        val keys = AnalysisPrompt.redactedKeys(setOf(Fields.GRIND, Fields.BEVERAGE, Fields.TIME, Fields.RATIO))
        val expected = setOf(
            "grindClicks", "grindSetting", "grindValue", "currentClicks", "currentSetting", "currentOnDial",
            "beverageG", "retainedGPerGCoffee", "extractionYieldPct", "tdsPercent",
            "totalTimeSec", "vsRecipeSec",
            "doseG", "waterG", "ratio",
        )
        assertTrue(keys.containsAll(expected))
    }

    @Test
    fun sameDataGivesTheSameHashWhenTheClockMoves() {
        // Two beans that differ only in when the row was created, standing in for two clocks.
        val first = AnalysisPrompt.brew(brewInput(v60Log(), b = bean.copy(createdAt = 1_000L)))
        val later = AnalysisPrompt.brew(brewInput(v60Log(), b = bean.copy(createdAt = 9_999_999_999L)))
        assertEquals(first.user, later.user)
        assertEquals(first.inputHash, later.inputHash)
        assertEquals(AnalysisPrompt.HASH_SCHEME.length + 16, first.inputHash.length)
        assertTrue(first.user.contains("\"daysOffRoast\":17"))
    }

    @Test
    fun theHashDoesNotDependOnThePhonesTimeZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val home = AnalysisPrompt.brew(brewInput(v60Log()))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val abroad = AnalysisPrompt.brew(brewInput(v60Log()))
            assertEquals(home.inputHash, abroad.inputHash)
            // Dates are sent in UTC and say so.
            assertTrue(home.user.contains("\"2026-09-14 08:12Z\""))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun changingTheBrewChangesTheHash() {
        val a = AnalysisPrompt.brew(brewInput(v60Log(total = 208)))
        val b = AnalysisPrompt.brew(brewInput(v60Log(total = 212)))
        assertFalse(a.inputHash == b.inputHash)
    }

    @Test
    fun grinderBlockGivesDialReadingAndExample() {
        val built = AnalysisPrompt.brew(brewInput(v60Log(grindClicks = 77)))
        assertTrue(built.user.contains("\"currentOnDial\":\"7.7\""))
        assertTrue(built.user.contains("one full turn finer from 77 (dial 7.7) is 67 (dial 6.7)"))
        assertTrue(built.user.contains("\"finerDirection\":\"FEWER clicks is finer\""))
    }

    @Test
    fun trendOnAnEmptyLogDoesNotThrow() {
        val built = AnalysisPrompt.trend(TrendPromptInput(emptyList(), 0, emptyMap(), emptyMap(), emptyMap()))
        assertEquals(0, built.brewCount)
        assertEquals(0, built.ratedCount)
        assertEquals(0L, built.newestBrewAt)
        assertTrue(built.flags.isEmpty())
    }

    @Test
    fun zeroDoseDoesNotThrowAndSendsNoRatio() {
        val built = AnalysisPrompt.brew(brewInput(v60Log(doseG = 0.0)))
        assertTrue(built.flags.any { it.code == FlagCode.RATIO_IMPLAUSIBLE })
        assertTrue(built.user.contains("\"ratio\":null"))
        assertFalse(built.user.contains("Infinity"))
        assertFalse(built.user.contains("NaN"))
    }

    @Test
    fun trendCountsAndCapsAreReported() {
        val logs = (1..4).map { i -> v60Log(id = i.toLong(), startedAt = brewedAt - i * 86_400_000L) }
            .sortedByDescending { it.startedAt }
        val built = AnalysisPrompt.trend(
            TrendPromptInput(
                logs = logs,
                totalLogged = 70,
                recipes = mapOf(v60.id to v60),
                beans = mapOf(bean.id to bean),
                grinders = mapOf(grinder.id to grinder),
            ),
        )
        assertEquals(4, built.brewCount)
        assertEquals(4, built.ratedCount)
        assertEquals(logs.first().startedAt, built.newestBrewAt)
        assertTrue(built.user.contains("These are the latest 4 of 70 brews."))
        assertEquals(AnalysisPrompt.TREND_MAX_TOKENS, built.maxTokens)
    }
}
