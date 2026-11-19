package com.laksh.coffee.domain.ai

import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grind hero for numbered and stepless dials: the start is the log's, the direction the grinder's. */
class GrindValueMoveTest {

    private val encore = GrinderEntity(
        id = 2, brand = "Baratza", model = "Encore", settingStyle = SettingStyle.Numbers,
        lowerIsFiner = true, minSetting = 1.0, maxSetting = 40.0, step = 1.0, catalogId = "baratza-encore",
    )

    private val kUltra = GrinderEntity(
        id = 1, brand = "1Zpresso", model = "K-Ultra", settingStyle = SettingStyle.Clicks,
        clicksPerRotation = 100, micronsPerClick = 20.0, catalogId = "1zpresso-k-ultra",
    )

    private fun grind(to: Double?, from: Double? = null, words: String = "one step finer") =
        Change(variable = "grind", instruction = words, grindValueFrom = from, grindValueTo = to)

    @Test
    fun theMoveStartsFromTheLogAndReadsInDialNumbers() {
        val (move, warnings) = GrindValueMove.resolve(grind(to = 13.0, from = 14.0), encore, trustedFrom = 14.0, method = BrewMethod.PourOver)
        assertNotNull(move)
        assertEquals("14 → 13", move!!.numbers())
        assertEquals("finer by 1", move.caption())
        assertEquals(true, move.finer)
        assertEquals("plain", move.dialFormat)
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test
    fun halfStepsKeepTheirDecimal() {
        val ode = encore.copy(model = "Ode", step = 0.1, minSetting = 1.0, maxSetting = 11.0, catalogId = null)
        val (move, _) = GrindValueMove.resolve(grind(to = 5.2, words = "coarser"), ode, trustedFrom = 4.5)
        assertEquals("4.5 → 5.2", move!!.numbers())
        assertEquals("coarser by 0.7", move.caption())
        assertEquals(false, move.finer)
    }

    @Test
    fun theModelsOwnStartIsNeverUsed() {
        val (move, warnings) = GrindValueMove.resolve(grind(to = 13.0, from = 20.0), encore, trustedFrom = 14.0, method = BrewMethod.PourOver)
        assertEquals(14.0, move!!.from!!, 0.0)
        assertTrue(warnings.any { it.contains("started from 20") && it.contains("your log says 14") })
    }

    @Test
    fun wordsThatDisagreeWithTheNumbersAreCalledOut() {
        // "Finer", but 14 → 16 is coarser on a dial where lower is finer.
        val (move, warnings) = GrindValueMove.resolve(grind(to = 16.0, words = "go finer"), encore, trustedFrom = 14.0, method = BrewMethod.PourOver)
        assertEquals(false, move!!.finer)
        assertTrue(warnings.toString(), warnings.any { it.contains("words and numbers disagree") && it.contains("14 → 16 is coarser") })

        val higherIsFiner = encore.copy(lowerIsFiner = false)
        val (other, none) = GrindValueMove.resolve(grind(to = 16.0, words = "go finer"), higherIsFiner, trustedFrom = 14.0)
        assertEquals(true, other!!.finer)
        assertTrue(none.none { it.contains("disagree") })
    }

    @Test
    fun aMoveBiggerThanMaxAtOnceGetsAWarning() {
        val limit = encore.scale().moves(BrewMethod.PourOver).maxAtOnce
        val (_, calm) = GrindValueMove.resolve(grind(to = 14.0 - limit), encore, trustedFrom = 14.0, method = BrewMethod.PourOver)
        assertTrue(calm.none { it.contains("in one go") })
        val (_, big) = GrindValueMove.resolve(grind(to = 14.0 - limit - 1), encore, trustedFrom = 14.0, method = BrewMethod.PourOver)
        assertTrue(big.toString(), big.any { it.startsWith("That is more than ${encore.scale().format(limit)} in one go.") })
    }

    @Test
    fun withNoTrustedStartOnlyTheTargetIsShown() {
        val (move, warnings) = GrindValueMove.resolve(grind(to = 13.0, from = 14.0), encore, trustedFrom = null)
        assertNull(move!!.from)
        assertEquals("13", move.numbers())
        assertEquals("", move.caption())
        assertNull(move.finer)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun thereIsNoMoveWhenThereIsNothingToShow() {
        // Not a grind change, no number, no change, a click grinder, no grinder.
        assertNull(GrindValueMove.resolve(Change(variable = "temperature", grindValueTo = 13.0), encore, 14.0).first)
        assertNull(GrindValueMove.resolve(grind(to = null), encore, 14.0).first)
        assertNull(GrindValueMove.resolve(grind(to = 14.0), encore, 14.0).first)
        assertNull(GrindValueMove.resolve(grind(to = 13.0), kUltra, 14.0).first)
        assertNull(GrindValueMove.resolve(grind(to = 13.0), null, 14.0).first)
        val (move, warnings) = GrindValueMove.resolve(grind(to = -1.0), encore, 14.0)
        assertNull(move)
        assertEquals(1, warnings.size)
    }

    @Test
    fun aClickGrinderStillGetsItsClickMoveAndNeverAValueMove() {
        val change = Change(variable = "grind", instruction = "75 -> 69 clicks, finer", grindClicksFrom = 75, grindClicksTo = 69, grindValueFrom = 7.5, grindValueTo = 6.9)
        assertNotNull(GrindMove.resolve(change, kUltra, 75, BrewMethod.PourOver).first)
        assertNull(GrindValueMove.resolve(change, kUltra, 7.5, BrewMethod.PourOver).first)
    }

    @Test
    fun theStoredEnvelopeRoundTripsBothMoves() {
        val stored = StoredResult(
            brew = BrewAnalysis(headline = "h"),
            valueMove = GrindValueMove(from = 14.0, to = 13.0, lowerIsFiner = true, unit = "", dialFormat = "plain"),
        )
        val text = AiJson.stored.encodeToString(StoredResult.serializer(), stored)
        assertEquals(stored, AiJson.stored.decodeFromString(StoredResult.serializer(), text))
        // A 1.x row has no valueMove key at all.
        val old = AiJson.stored.decodeFromString(StoredResult.serializer(), """{"brew":{"headline":"h"},"grindMove":{"from":77,"to":67,"clicksPerTurn":10,"lowerIsFiner":true}}""")
        assertNull(old.valueMove)
        assertEquals(67, old.grindMove!!.to)
    }
}
