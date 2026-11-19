package com.laksh.coffee.domain.ai

import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrindMoveTest {

    private val kUltra = GrinderEntity(
        id = 1,
        brand = "1Zpresso",
        model = "K-Ultra",
        settingStyle = SettingStyle.Clicks,
        clicksPerRotation = 10,
        lowerIsFiner = true,
    )

    private fun grindChange(instruction: String, from: Int?, to: Int?) = Change(
        variable = "grind",
        instruction = instruction,
        from = from?.let { "$it clicks" }.orEmpty(),
        to = to?.let { "$it clicks" }.orEmpty(),
        grindClicksFrom = from,
        grindClicksTo = to,
    )

    @Test
    fun oneFullTurnFiner() {
        val move = GrindMove(77, 67, 10, lowerIsFiner = true)
        assertEquals(-10, move.delta)
        assertEquals(true, move.finer)
        assertEquals("77 → 67", move.numbers())
        assertEquals("10 clicks finer · one full turn · dial 7.7 → 6.7", move.caption())
    }

    @Test
    fun partTurnsAndPlainClicks() {
        assertEquals("13 clicks finer · 1 turn + 3 · dial 7.7 → 6.4", GrindMove(77, 64, 10, true).caption())
        assertEquals("3 clicks coarser · dial 6.6 → 6.9", GrindMove(66, 69, 10, true).caption())
        assertEquals("1 click finer", GrindMove(20, 19, 0, true).caption())
        assertEquals("16 clicks finer · 2 full turns", GrindMove(40, 24, 8, true).caption())
    }

    @Test
    fun directionFollowsTheGrinder() {
        val reversed = GrindMove(20, 25, 0, lowerIsFiner = false)
        assertEquals(true, reversed.finer)
        assertEquals("5 clicks finer", reversed.caption())
    }

    @Test
    fun noStartShowsOnlyTheTarget() {
        val move = GrindMove(null, 67, 10, true)
        assertNull(move.delta)
        assertNull(move.finer)
        assertEquals("67", move.numbers())
        assertEquals("dial 6.7", move.caption())
        assertEquals("", GrindMove(null, 67, 0, true).caption())
    }

    @Test
    fun resolveUsesTheLogNotTheModel() {
        val (move, warnings) = GrindMove.resolve(grindChange("75 -> 65 clicks, one full turn finer", 75, 65), kUltra, 77)
        assertNotNull(move)
        assertEquals(77, move!!.from)
        assertEquals(65, move.to)
        assertTrue(warnings.any { it.contains("your log says 77") })
    }

    @Test
    fun resolveWithoutATrustedStartKeepsOnlyTheTarget() {
        val (move, _) = GrindMove.resolve(grindChange("one full turn finer", 55, 67), kUltra, null)
        assertNotNull(move)
        assertNull(move!!.from)
        assertEquals("67", move.numbers())
    }

    @Test
    fun wordsThatDisagreeWithTheNumbersAreFlagged() {
        val (move, warnings) = GrindMove.resolve(grindChange("go finer", 77, 87), kUltra, 77)
        assertEquals(false, move!!.finer)
        assertTrue(warnings.any { it.startsWith("The model's words and numbers disagree: 77 → 87 is coarser") })
    }

    @Test
    fun moreThanTwoTurnsIsFlagged() {
        val (move, warnings) = GrindMove.resolve(grindChange("77 -> 50 clicks, finer", 77, 50), kUltra, 77)
        assertNotNull(move)
        assertTrue(warnings.contains("That is more than two full turns in one go. Consider going halfway first."))
        assertFalse(warnings.any { it.contains("disagree") })
    }

    @Test
    fun impossibleOrNonGrindChangesGiveNoMove() {
        val below = GrindMove.resolve(grindChange("finer", 77, -3), kUltra, 77)
        assertNull(below.first)
        assertEquals(1, below.second.size)

        assertNull(GrindMove.resolve(grindChange("same", 77, 77), kUltra, 77).first)
        assertNull(GrindMove.resolve(Change(variable = "temperature", grindClicksTo = 67), kUltra, 77).first)
        assertNull(GrindMove.resolve(grindChange("finer", 77, 67), kUltra.copy(settingStyle = SettingStyle.Numbers), 77).first)
        assertNull(GrindMove.resolve(grindChange("finer", 77, 67), null, 77).first)
    }

    @Test
    fun valueMovesParseMatchingUnits() {
        assertEquals(
            ValueMove("95", "100", "°C"),
            ChangeParse.valueMove(Change(variable = "temperature", from = "95 °C", to = "100 °C")),
        )
        assertEquals(
            ValueMove("1:16", "1:15", ""),
            ChangeParse.valueMove(Change(variable = "ratio", from = "1:16", to = "1:15")),
        )
        assertEquals(
            ValueMove("95", "100", "°C"),
            ChangeParse.valueMove(Change(variable = "temperature", from = "95 °C", to = "100°C")),
        )
        assertNull(ChangeParse.valueMove(Change(variable = "grind", from = "77 clicks", to = "67 clicks")))
        assertNull(ChangeParse.valueMove(Change(variable = "dose", from = "18 g", to = "20 °C")))
        assertNull(ChangeParse.valueMove(Change(variable = "pour", from = "", to = "slower")))
    }

    @Test
    fun movesSurviveTheStoredJson() {
        val result = StoredResult(grindMove = GrindMove(null, 67, 10, true))
        val back = AiJson.stored.decodeFromString(StoredResult.serializer(), AiJson.stored.encodeToString(StoredResult.serializer(), result))
        assertEquals(result, back)
    }
}
