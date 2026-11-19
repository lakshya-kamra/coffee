package com.laksh.coffee.domain.ai

import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.recipes.ExpertRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The K-Ultra is 100 clicks a turn (1Zpresso's manual), so the 1.x "under one turn is finer than
 * espresso" rule can't apply to it: per-method catalog ranges replace it. 1.x rows saved at 10 a
 * turn keep the old rules until the user accepts the catalog's numbers (DataQualityTest).
 */
class GrindRulesTest {

    private val v60: Recipe = ExpertRecipes.byId("hoffmann-v60")!!

    private val kUltra = GrinderEntity(
        id = 1, brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks,
        clicksPerRotation = 100, micronsPerClick = 20.0, lowerIsFiner = true, maxSetting = 200.0,
        catalogId = "1zpresso-k-ultra",
    )

    private fun log(id: Long = 10, clicks: Int?) = BrewLogEntity(
        id = id,
        startedAt = 1_789_373_520_000L,
        recipeId = v60.id,
        recipeName = v60.name,
        brewer = v60.brewer.label,
        brewerId = v60.brewer.id,
        beanLabel = "Test",
        daysOffRoast = 14,
        grinderId = kUltra.id,
        grinderLabel = kUltra.display,
        grindClicks = clicks,
        doseG = 30.0,
        waterG = 500.0,
        tempC = 100,
        totalTimeSec = 208,
        drawdownSec = 102,
        beverageG = 439.0,
        taste = TasteProfile(5, 7, 3, 6, 8, 7, 7),
        overall = 8,
    )

    private fun codes(l: BrewLogEntity, peers: List<BrewLogEntity> = emptyList()) =
        DataQuality.check(l, v60, kUltra, null, peers).map { it.code }

    @Test
    fun theOwnersFilterSettingsAreTrusted() {
        listOf(60, 66, 75, 80, 85).forEach { c ->
            val found = codes(log(clicks = c))
            assertFalse("$c clicks", FlagCode.GRIND_UNDER_ONE_TURN in found)
            assertFalse("$c clicks", FlagCode.GRIND_TOO_FINE_FOR_METHOD in found)
        }
    }

    @Test
    fun aDialReadingTypedAsClicksIsCaughtByTheEspressoRange() {
        val f = DataQuality.check(log(clicks = 7), v60, kUltra, null).first { it.code == FlagCode.GRIND_TOO_FINE_FOR_METHOD }
        assertEquals(FlagSeverity.Distrust, f.severity)
        assertEquals(Fields.GRIND, f.field)
        assertTrue(f.message, f.message.contains("30–45"))
        assertTrue(f.message, f.message.contains("1Zpresso"))
        assertTrue(f.message, f.message.contains("stored as 75"))
        assertFalse(f.promptMessage, f.promptMessage.contains("7"))
        assertTrue(DataQuality.grindLooksTyped(7, kUltra.scale(), BrewMethod.PourOver))
        assertFalse(DataQuality.grindLooksTyped(7, kUltra.scale(), BrewMethod.Espresso))
        assertFalse(DataQuality.grindLooksTyped(75, kUltra.scale(), BrewMethod.PourOver))
    }

    @Test
    fun jumpsAreMeasuredInMicronsNotTurns() {
        val peers = listOf(log(id = 1, clicks = 75), log(id = 2, clicks = 76), log(id = 3, clicks = 74))
        assertFalse(FlagCode.GRIND_JUMP in codes(log(clicks = 90), peers)) // 15 clicks = 300 µm
        assertTrue(FlagCode.GRIND_JUMP in codes(log(clicks = 100), peers)) // 25 clicks = 500 µm
    }

    @Test
    fun movesOnANumberedDialSpeakInDialNumbersNotTurns() {
        val change = Change(variable = "grind", instruction = "75 -> 69 clicks, finer", grindClicksFrom = 75, grindClicksTo = 69)
        val (move, warnings) = GrindMove.resolve(change, kUltra, 75, BrewMethod.PourOver)
        assertNotNull(move)
        assertEquals("number-tick:10", move!!.dialFormat)
        assertEquals("6 clicks finer · dial 7.5 → 6.9", move.caption())
        assertTrue(warnings.isEmpty())

        val big = Change(variable = "grind", instruction = "finer", grindClicksFrom = 75, grindClicksTo = 50)
        val (_, w) = GrindMove.resolve(big, kUltra, 75, BrewMethod.PourOver)
        assertTrue(w.toString(), w.contains("That is more than 13 clicks in one go. Consider going halfway first."))

        // A full hundred clicks is a real turn only in clicks, never "one full turn" words on this dial.
        assertEquals("100 clicks coarser · dial 7.5 → 1 turn + 7.5", GrindMove(75, 175, 100, true, "number-tick:10").caption())
    }

    @Test
    fun thePromptGivesTheDialReadingAndAClearMissInClicks() {
        val l = log(clicks = 75)
        val built = AnalysisPrompt.brew(
            BrewPromptInput(
                log = l, recipe = v60, bean = null, grinder = kUltra, history = emptyList(),
                recipes = mapOf(v60.id to v60), beans = emptyMap(), grinders = mapOf(kUltra.id to kUltra),
            ),
        )
        assertTrue(built.user, built.user.contains("\"currentOnDial\":\"7.5\""))
        assertTrue(built.user, built.user.contains("\"dialFormat\":\"number-tick:10\""))
        assertTrue(
            built.user,
            built.user.contains("\"moveSizes\":{\"nudge\":2,\"clearMiss\":6,\"maxAtOnce\":13,\"basis\":\"20 µm per click (app rule of thumb)\"}"),
        )
        assertTrue(built.user, built.user.contains("6 clicks finer from 75 (dial 7.5) is 69 (dial 6.9)"))
        assertFalse(built.system.contains("one full turn"))
    }

    @Test
    fun micronsPerStepIsDescribedToTheModelAsBurrTravelNotParticleSize() {
        for (system in listOf(AnalysisPrompt.SYSTEM_BREW, AnalysisPrompt.SYSTEM_TREND)) {
            assertTrue(system, system.contains("micronsPerStep"))
            assertTrue(system, system.contains("burr travel from zero"))
            assertTrue(system, system.contains("not the size of the grounds"))
            assertTrue(system, system.contains("never state or imply a particle size from it"))
        }
    }

    @Test
    fun aFullTurnReadsTenPointZeroOnScreenButThePromptKeepsItsOwnWords() {
        // On screen: the way 1Zpresso writes it.
        assertEquals("10 clicks coarser · dial 9.0 → 10.0", GrindMove(90, 100, 100, true, "number-tick:10").caption())
        // In the prompt: unchanged, so the same brew gives the same inputHash here and on the web.
        val l = log(clicks = 100)
        val built = AnalysisPrompt.brew(
            BrewPromptInput(
                log = l, recipe = v60, bean = null, grinder = kUltra, history = emptyList(),
                recipes = mapOf(v60.id to v60), beans = emptyMap(), grinders = mapOf(kUltra.id to kUltra),
            ),
        )
        assertTrue(built.user, built.user.contains("\"currentOnDial\":\"1 turn + 0.0\""))
        assertTrue(built.user, built.user.contains("6 clicks finer from 100 (dial 1 turn + 0.0) is 94 (dial 9.4)"))
    }

    @Test
    fun aGrinderWithoutTurnsNeverTalksInTurns() {
        assertNull(GrindMove(20, 15, 0, true, "plain").dial())
        assertEquals("5 clicks finer", GrindMove(20, 15, 0, true, "plain").caption())
    }
}
