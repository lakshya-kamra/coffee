package com.laksh.coffee.grind

import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.domain.grind.DialReading
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.domain.grind.MoveSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrindScaleTest {

    private fun clicks(cpr: Int, microns: Double? = null, dialFormat: String = "", catalogId: String? = null) = GrinderEntity(
        brand = "Test", model = "Grinder", settingStyle = SettingStyle.Clicks,
        clicksPerRotation = cpr, micronsPerClick = microns, dialFormat = dialFormat, catalogId = catalogId,
        maxSetting = 200.0,
    )

    /** The owner's 1.x K-Ultra row: 10 a turn, no catalog link. */
    private val legacy = clicks(10)

    /** A K-Ultra made from the catalog: 100 a turn, 20 µm a click. */
    private val kUltra = clicks(100, 20.0, catalogId = "1zpresso-k-ultra")

    // ------------------------------------------------------------ spec vectors

    @Test
    fun legacyRowsReadExactlyAsOneX() {
        val s = legacy.scale()
        assertEquals(DialFormat.TurnsDotClicks(10), s.dial)
        assertEquals("7.5", s.reading(75.0))
        assertEquals("7 turns + 5 · dial 7.5", s.describe(75.0))
        assertEquals("7 turns + 5 · dial 7.5 · burr travel ≈ 1.5 mm", clicks(10, 20.0).scale().describe(75.0))
        assertEquals("75 clicks (7 turns + 5)", legacy.describeClicks(75))
    }

    @Test
    fun numberedDialVectors() {
        val s = kUltra.scale()
        assertEquals(DialFormat.NumberTick(100, 10), s.dial)
        assertEquals("7.5", s.reading(75.0))
        assertEquals("dial 7.5 · burr travel ≈ 1.5 mm", s.describe(75.0))
        assertEquals("1 turn + 2.5", s.reading(125.0))
        assertEquals("75 clicks (dial 7.5)", kUltra.describeClicks(75))

        val noMicrons = clicks(100, dialFormat = "number-tick:10")
        assertEquals("dial 1 turn + 2.5", noMicrons.scale().describe(125.0))

        val ninety = clicks(90, dialFormat = "number-tick:10")
        assertEquals("7.5", ninety.scale().reading(75.0))
        assertEquals("dial 7.5", ninety.scale().describe(75.0))
    }

    @Test
    fun theMicronCaptionIsBurrTravelInMillimetresNeverABareMicronFigure() {
        val s = kUltra.scale()
        // Clicks x microns per click is how far the burrs have moved, not a particle size.
        assertEquals("dial 7.7 · burr travel ≈ 1.54 mm", s.describe(77.0))
        assertEquals("dial 1.5 · burr travel ≈ 0.3 mm", s.describe(15.0))
        assertEquals("dial 0.0 · burr travel ≈ 0 mm", s.describe(0.0))
        assertTrue(listOf(0.0, 15.0, 77.0, 125.0).none { "µm" in s.describe(it) })
        // Left out where the line has no room for it.
        assertEquals("dial 7.7", s.describe(77.0, travel = false))
        assertEquals("1.54", GrindScale.millimetres(1540.0))
        assertEquals("0.01", GrindScale.millimetres(8.0))
        assertEquals("2", GrindScale.millimetres(2000.0))
    }

    @Test
    fun oneFullTurnOfANumberedRingReadsAsTheCountCarriedOn() {
        val s = kUltra.scale()
        // 1Zpresso writes 100 clicks as 10.0, not as one turn plus nothing.
        assertEquals("10.0", s.reading(100.0))
        assertEquals("dial 10.0", clicks(100, dialFormat = "number-tick:10").scale().describe(100.0))
        assertEquals("100 clicks (dial 10.0)", kUltra.describeClicks(100))
        assertEquals("9.9", s.reading(99.0))
        assertEquals("1 turn + 0.1", s.reading(101.0))
        assertEquals("2 turns + 0.0", s.reading(200.0))
        // Asked for in turns, it keeps the long form.
        assertEquals("1 turn + 0.0", s.reading(100.0, inTurns = true))
        // And what is written can be typed back.
        assertEquals(100.0, s.parseReading("10.0")!!, 0.0)
        assertEquals(100.0, s.parseReading("10")!!, 0.0)
        assertNull(s.parseReading("10.1"))
        assertNull(s.parseReading("11.0"))
        assertNull(s.parseReading("1+10.0"))
        // Other ring sizes: the turn ends where the numbers run out.
        assertEquals("9.0", clicks(90, dialFormat = "number-tick:10").scale().reading(90.0))
        assertEquals("10.0", clicks(30, dialFormat = "number-tick:3").scale().reading(30.0))
        assertEquals(30.0, clicks(30, dialFormat = "number-tick:3").scale().parseReading("10.0")!!, 0.0)
    }

    @Test
    fun aCountOfOneIsSingularInTheSnapshotStoredWithABrew() {
        assertEquals("13 clicks (1 turn + 3)", legacy.describeClicks(13))
        assertEquals("1 click (0 turns + 1)", legacy.describeClicks(1))
        assertEquals("1 click (dial 0.1)", kUltra.describeClicks(1))
    }

    @Test
    fun turnsAndClicksAndPlain() {
        val twelve = clicks(12)
        assertEquals(DialFormat.TurnsAndClicks(12), twelve.scale().dial)
        assertEquals("2 turns + 1", twelve.scale().reading(25.0))
        assertEquals("2 turns + 1", twelve.scale().describe(25.0))

        val niche = GrinderEntity(brand = "Niche", model = "Zero", settingStyle = SettingStyle.Stepless, step = 0.5, maxSetting = 50.0)
        val p = niche.scale()
        assertEquals(DialFormat.Plain, p.dial)
        assertNull(p.reading(14.5))
        assertEquals("", p.describe(14.5))
        assertEquals("14.5", p.format(14.5))
        assertEquals("18", p.format(18.0))
        assertEquals("LOWER settings are finer", p.finerDirection)
    }

    // ------------------------------------------------------------- resolution

    @Test
    fun aLinkedOneXRowKeepsItsReadingUntilTheUserAcceptsTheCatalog() {
        val linkedLegacy = clicks(10, catalogId = "1zpresso-k-ultra")
        val s = linkedLegacy.scale()
        assertEquals(DialFormat.TurnsDotClicks(10), s.dial)
        // Nothing numeric from a disagreeing catalog applies, so the 1.x rules stay in force.
        assertTrue(s.ranges.isEmpty())
        assertEquals(MoveSizes.Basis.LegacyTurn, s.moves(BrewMethod.PourOver).basis)
        // Nor its words: "100 clicks per full turn" next to a row that counts 10 would give the
        // prompt and the shared report two descriptions of one dial.
        assertEquals("", s.entryHint)
        assertEquals("", s.dialReadingHelp)
        assertTrue(kUltra.scale().entryHint.isNotBlank() && kUltra.scale().dialReadingHelp.isNotBlank())
    }

    @Test
    fun theCatalogSuppliesRangesWhenTheRowAgrees() {
        val s = kUltra.scale()
        assertEquals("high", s.confidence)
        assertEquals(30.0, s.espressoFloor!!, 0.0)
        assertTrue(s.rangeFor(BrewMethod.PourOver) != null)
        assertTrue(s.rangeSources.isNotEmpty())
    }

    @Test
    fun theUsersOwnChoiceWins() {
        assertEquals(DialFormat.Plain, clicks(100, dialFormat = "plain", catalogId = "1zpresso-k-ultra").scale().dial)
        assertEquals(DialFormat.TurnsAndClicks(100), clicks(100, dialFormat = "turns").scale().dial)
        // A dial format on a numbered grinder that does not click falls back to plain.
        val numbers = clicks(100, dialFormat = "number-tick:10").copy(settingStyle = SettingStyle.Numbers)
        assertEquals(DialFormat.Plain, numbers.scale().dial)
    }

    @Test
    fun formatsRoundTrip() {
        listOf(DialFormat.Plain, DialFormat.TurnsAndClicks(12), DialFormat.TurnsDotClicks(10), DialFormat.NumberTick(100, 10)).forEach {
            val cpr = when (it) {
                is DialFormat.TurnsAndClicks -> it.clicksPerTurn
                is DialFormat.TurnsDotClicks -> it.clicksPerTurn
                is DialFormat.NumberTick -> it.clicksPerRotation
                DialFormat.Plain -> 0
            }
            assertEquals(it, DialFormat.decode(it.encode(), cpr))
        }
        assertNull(DialFormat.decode("", 10))
        assertNull(DialFormat.decode("turns-dot", 100))
        assertNull(DialFormat.decode("number-tick:x", 100))
    }

    // ------------------------------------------------------- reading input

    @Test
    fun readingsGoBackToStoredClicks() {
        val s = kUltra.scale()
        assertEquals(75.0, s.fromReading(DialReading(number = 7, tick = 5))!!, 0.0)
        assertEquals(123.0, s.fromReading(DialReading(turns = 1, number = 2, tick = 3))!!, 0.0)
        assertNull(s.fromReading(DialReading(number = 7, tick = 10)))
        // "10.0" is one full turn, the way it is read out; nothing else past the ring's numbers is.
        assertEquals(100.0, s.fromReading(DialReading(number = 10, tick = 0))!!, 0.0)
        assertNull(s.fromReading(DialReading(number = 10, tick = 1)))
        assertNull(s.fromReading(DialReading(number = 11, tick = 0)))
        assertEquals(75.0, s.parseReading("7.5")!!, 0.0)
        assertEquals(75.0, s.parseReading("7,5")!!, 0.0)
        assertEquals(125.0, s.parseReading("1+2.5")!!, 0.0)
        assertEquals(80.0, s.parseReading("8")!!, 0.0)
        assertNull(s.parseReading("7.5.4"))

        val twelve = clicks(12).scale()
        assertEquals(38.0, twelve.parseReading("3 + 2")!!, 0.0)
        assertNull(twelve.parseReading("3 + 12"))
        assertEquals(75.0, legacy.scale().parseReading("7.5")!!, 0.0)

        val plain = GrinderEntity(brand = "B", model = "E", settingStyle = SettingStyle.Numbers).scale()
        assertEquals(14.5, plain.parseReading("14,5")!!, 0.0)
    }

    // ------------------------------------------------------------- move sizes

    @Test
    fun moveSizes() {
        val k = kUltra.scale().moves(BrewMethod.PourOver)
        assertEquals(MoveSizes(2.0, 6.0, 13.0, 25.0, MoveSizes.Basis.Microns), k)

        val l = legacy.scale().moves(BrewMethod.PourOver)
        assertEquals(MoveSizes(2.0, 10.0, 20.0, 20.0, MoveSizes.Basis.LegacyTurn), l)

        val comandante = clicks(0).scale().moves(null)
        assertEquals(MoveSizes(2.0, 5.0, 10.0, 20.0, MoveSizes.Basis.Generic), comandante)

        // A linked catalog grinder without microns sizes moves from its range.
        val encore = GrinderEntity(
            brand = "Baratza", model = "Encore", settingStyle = SettingStyle.Numbers,
            minSetting = 1.0, maxSetting = 40.0, catalogId = "baratza-encore",
        ).scale()
        val range = encore.rangeFor(BrewMethod.PourOver)
        if (range != null) assertEquals(MoveSizes.Basis.Range, encore.moves(BrewMethod.PourOver).basis)
        assertTrue(Catalog.grinder("baratza-encore") != null)
    }
}
